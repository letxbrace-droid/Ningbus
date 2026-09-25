package fr.ningbus.arbitre

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * Les surfaces de l'application, dessinées plutôt que déclarées.
 *
 * Trois raisons de ne pas les mettre en ressources XML. La couleur du verdict
 * n'est connue qu'à l'exécution, et il faudrait sinon trois fichiers par
 * forme. La lueur se fabrique par empilement d'anneaux, ce qu'un `<shape>` ne
 * sait pas faire. Et surtout : une forme calculée reste juste quand on change
 * une couleur du thème, là où douze fichiers finissent toujours par diverger.
 */
object Peinture {

    /**
     * Une carte qui rayonne de la couleur de son verdict.
     *
     * Android ne sait pas dessiner d'ombre colorée autour d'une forme — la
     * seule ombre native est noire, et invisible sur un fond noir. La lueur
     * est donc composée : quelques anneaux concentriques de plus en plus
     * transparents autour du contour plein. De loin, l'œil lit un halo ; de
     * près, il lit des traits fins, ce qui n'a aucune importance puisqu'on ne
     * regarde jamais un cadre de près.
     *
     * @param intensite part de la couleur mélangée au fond de la carte.
     */
    fun lueur(
        contexte: Context,
        couleur: Int,
        rayonDp: Float = 18f,
        anneaux: Int = 4,
        intensite: Float = 0.10f,
    ): Drawable {
        val densite = contexte.resources.displayMetrics.density
        val trait = (1.5f * densite).toInt().coerceAtLeast(1)
        val ecart = (2.5f * densite).toInt()

        val couches = ArrayList<Drawable>(anneaux + 1)
        // Du plus large et du plus pâle vers le plus serré : c'est l'ordre de
        // dessin, et donc celui qui donne l'impression de dégradé.
        for (rang in anneaux downTo 1) {
            couches += GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = (rayonDp + rang * 2.5f) * densite
                setColor(Color.TRANSPARENT)
                setStroke(trait, ColorUtils.setAlphaComponent(couleur, 10 + (anneaux - rang) * 16))
            }
        }
        couches += GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = rayonDp * densite
            setColor(
                ColorUtils.blendARGB(
                    ContextCompat.getColor(contexte, R.color.nuit_carte),
                    couleur,
                    intensite,
                )
            )
            setStroke(trait, ColorUtils.setAlphaComponent(couleur, 210))
        }

        val pile = LayerDrawable(couches.toTypedArray())
        for (rang in couches.indices) pile.setLayerInset(rang, rang * ecart, rang * ecart, rang * ecart, rang * ecart)
        return pile
    }

    /** Une surface teintée, sans halo : pour les listes, où le halo bruiterait. */
    fun carte(contexte: Context, couleur: Int, intensite: Float = 0.12f): GradientDrawable =
        GradientDrawable().apply {
            val densite = contexte.resources.displayMetrics.density
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * densite
            setColor(
                ColorUtils.blendARGB(
                    ContextCompat.getColor(contexte, R.color.nuit_carte),
                    couleur,
                    intensite,
                )
            )
            setStroke((1.5f * densite).toInt(), ColorUtils.setAlphaComponent(couleur, 120))
        }

    /**
     * Le dégradé d'un bouton d'action.
     *
     * Une seule action par écran le mérite : c'est ce qui la distingue des
     * trois autres boutons qui l'entourent. Deux dégradés sur le même écran,
     * et il n'y a plus d'action principale.
     */
    fun degrade(contexte: Context, de: Int, vers: Int, rayonDp: Float = 28f): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(de, vers)).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = rayonDp * contexte.resources.displayMetrics.density
        }

    /**
     * La tuile carrée qui porte une icône.
     *
     * Elle sert de repère de lecture : dans une grille de neuf chiffres, l'œil
     * retrouve « le carburant » à sa tuile bien avant d'avoir lu la légende.
     */
    fun tuile(contexte: Context, couleur: Int): GradientDrawable =
        GradientDrawable().apply {
            val densite = contexte.resources.displayMetrics.density
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * densite
            setColor(ColorUtils.setAlphaComponent(couleur, 38))
        }

    /** Le fond arrondi d'un bandeau d'avertissement. */
    fun bandeau(contexte: Context, couleur: Int): GradientDrawable =
        GradientDrawable().apply {
            val densite = contexte.resources.displayMetrics.density
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * densite
            setColor(ColorUtils.setAlphaComponent(couleur, 28))
            setStroke((1f * densite).toInt(), ColorUtils.setAlphaComponent(couleur, 90))
        }
}

/**
 * Un anneau de progression, dessiné à la main.
 *
 * Il sert à la confiance, et il vaut mieux qu'un pourcentage écrit : 93 % se
 * lit, un anneau presque fermé se voit. La différence compte parce que ce
 * chiffre-là n'est jamais celui qu'on cherche — on le croise.
 *
 * Une barre de progression circulaire native existe, mais elle tourne en
 * boucle ou impose son épaisseur ; trente lignes de dessin coûtent moins cher
 * que de lutter contre elle.
 */
class Anneau @JvmOverloads constructor(
    contexte: Context,
    attributs: AttributeSet? = null,
) : View(contexte, attributs) {

    private val densite = resources.displayMetrics.density
    private val cadre = RectF()

    private val pinceau = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * densite
    }

    /** De 0 à 100. Au-delà des bornes, l'anneau se contente de saturer. */
    var pourcent: Int = 0
        set(valeur) {
            field = valeur.coerceIn(0, 100)
            invalidate()
        }

    var couleur: Int = Color.WHITE
        set(valeur) {
            field = valeur
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val marge = pinceau.strokeWidth / 2f
        cadre.set(marge, marge, width - marge, height - marge)

        // Le cercle éteint d'abord : sans lui, un anneau à 20 % ressemble à
        // une virgule posée là par erreur.
        pinceau.color = ColorUtils.setAlphaComponent(couleur, 46)
        canvas.drawArc(cadre, 0f, 360f, false, pinceau)

        pinceau.color = couleur
        canvas.drawArc(cadre, -90f, 360f * pourcent / 100f, false, pinceau)
    }
}
