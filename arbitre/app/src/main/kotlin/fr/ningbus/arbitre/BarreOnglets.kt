package fr.ningbus.arbitre

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * La barre d'onglets, dessinée au pinceau plutôt qu'assemblée en vues.
 *
 * Quatre `LinearLayout` avec une icône et un libellé chacun donnaient une
 * barre correcte et sans caractère : on ne peut animer que ce que le système
 * expose — une échelle, une teinte, une opacité — et jamais le passage d'un
 * onglet à l'autre, qui est pourtant le seul moment où cette barre existe
 * vraiment. Un indicateur qui se déplace doit être dessiné, parce qu'il
 * n'appartient à aucun des deux onglets qu'il relie.
 *
 * Ce qui se joue ici, du plus visible au plus subtil :
 *
 *  - **un indicateur qui voyage** au lieu de clignoter d'une case à l'autre.
 *    C'est lui qui dit « tu viens de là, tu vas là », et une interface qui le
 *    dit paraît rapide même quand elle ne l'est pas ;
 *  - **un ressort, pas une courbe.** Un interpolateur joue une durée fixe et
 *    recommence de zéro si on le relance ; un ressort part de la vitesse
 *    qu'il avait. Appuyer trois fois de suite sur trois onglets donne un
 *    mouvement continu au lieu de trois saccades ;
 *  - **l'étirement.** L'indicateur s'allonge dans le sens du déplacement, à
 *    proportion de sa vitesse, et reprend sa forme en arrivant. C'est le
 *    vieux principe du dessin animé, et c'est ce qui sépare une animation
 *    d'un déplacement ;
 *  - **les libellés qui n'apparaissent que sous l'onglet actif**, en fondu
 *    lié à la position de l'indicateur. Trois mots en moins à lire, et
 *    l'icône active qui remonte pour leur faire place ;
 *  - **une onde au doigt**, partie du point touché.
 *
 * Tout est en Kotlin et n'ajoute pas un kilo-octet à l'application : un
 * `Canvas` sait faire ces cinq choses, et la question n'a jamais été le
 * langage.
 */
class BarreOnglets @JvmOverloads constructor(
    contexte: Context,
    attributs: AttributeSet? = null,
) : View(contexte, attributs) {

    /** Un onglet : ce qu'on dessine, et ce qu'on lit. */
    class Element(val icone: Drawable, val titre: String)

    // --- Le ressort ---------------------------------------------------------
    //
    // Réglé, pas choisi au hasard. La raideur donne le temps de parcours — à
    // 380, un saut d'un onglet à l'autre prend environ 280 ms. L'amortissement
    // est placé juste sous le régime critique : l'indicateur dépasse d'un
    // cheveu et revient, ce qui se ressent comme de la matière. Au-dessus il
    // rebondit comme un jouet, en dessous il glisse comme de l'huile.

    private companion object {
        const val RAIDEUR = 380f
        const val AMORTISSEMENT = 26f

        /** En dessous, le mouvement est fini : on rend la main au système. */
        const val REPOS_POSITION = 0.001f
        const val REPOS_VITESSE = 0.02f

        /** Allongement maximal de l'indicateur en pleine course. */
        const val ETIREMENT_MAX = 0.42f

        /** Durée de l'onde au doigt, en millisecondes. */
        const val ONDE_MS = 420f
    }

    private val densite = resources.displayMetrics.density

    private var elements: List<Element> = emptyList()
    private var surChoix: (Int) -> Unit = {}

    /** L'onglet choisi, en index entier. */
    private var actif = 0

    /** Où en est l'indicateur, en index **fractionnaire** : 1,6 = entre deux. */
    private var position = 0f
    private var vitesse = 0f
    private var dernierInstant = 0L

    /** Centre et âge de l'onde au doigt. */
    private var ondeX = 0f
    private var ondeY = 0f
    private var ondeDebut = 0L
    private var ondeVive = false

    private var indexPresse = -1

    // --- Pinceaux, alloués une fois -----------------------------------------
    //
    // onDraw() passe soixante fois par seconde pendant un déplacement : y
    // allouer des objets ferait travailler le ramasse-miettes exactement
    // pendant l'animation, et produirait les à-coups qu'on cherche à
    // supprimer. Les pinceaux et la forme de travail vivent donc ici.
    //
    // Deux dégradés font exception et se refont à chaque image, ceux de
    // l'indicateur : ils suivent une forme qui se déplace et s'étire, et un
    // dégradé ne se déplace pas sans être refait ou re-matricé. Deux petits
    // objets par image, contre quatre nœuds de vue animés par le système dans
    // la version précédente.

    private val pinceauFond = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pinceauContour = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * densite
    }
    private val pinceauIndicateur = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pinceauLueur = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pinceauOnde = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pinceauTexte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10.5f * densite
        isFakeBoldText = true
    }

    private val forme = RectF()

    private val couleurActive = ContextCompat.getColor(contexte, R.color.primaire)
    private val couleurClaire = ContextCompat.getColor(contexte, R.color.bleu_clair)
    private val couleurDormante = ContextCompat.getColor(contexte, R.color.gris)
    private val couleurFond = ContextCompat.getColor(contexte, R.color.nuit_carte)
    private val couleurRelief = ContextCompat.getColor(contexte, R.color.nuit_relief)

    init {
        isClickable = true
        // Le fond est peint ici : un arrière-plan déclaré par-dessus
        // masquerait la lueur de l'indicateur, qui déborde de la forme.
        setWillNotDraw(false)
    }

    /**
     * Installe les onglets. À appeler une fois.
     *
     * @param surChoix reçoit l'index choisi, uniquement sur un vrai appui —
     *   jamais sur un changement d'onglet programmé, sans quoi la sélection
     *   initiale déclencherait une navigation.
     */
    fun poser(elements: List<Element>, surChoix: (Int) -> Unit) {
        this.elements = elements
        this.surChoix = surChoix
        position = actif.toFloat()
        majDescription()
        invalidate()
    }

    /**
     * Désigne l'onglet actif.
     *
     * @param anime faux pour la pose initiale, où il n'y a rien à raconter :
     *   une barre qui s'anime au démarrage donne l'impression d'un écran qui
     *   n'a pas fini de charger.
     */
    fun choisir(index: Int, anime: Boolean = true) {
        if (index !in elements.indices) return
        val change = index != actif
        actif = index
        majDescription()
        if (!anime) {
            position = index.toFloat()
            vitesse = 0f
            invalidate()
            return
        }
        if (change) performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        lancer()
    }

    private fun lancer() {
        if (dernierInstant == 0L) dernierInstant = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    // --- Dessin -------------------------------------------------------------

    override fun onSizeChanged(l: Int, h: Int, ancienL: Int, ancienH: Int) {
        super.onSizeChanged(l, h, ancienL, ancienH)
        if (h <= 0) return
        pinceauFond.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            ColorUtils.blendARGB(couleurFond, couleurRelief, 0.85f),
            couleurFond,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (elements.isEmpty()) return

        val anime = avancerRessort()
        val largeurCase = width.toFloat() / elements.size
        val rayon = height / 2f

        // 1. Le fond : un dégradé vertical très court, plus clair en haut.
        //    C'est ce qui fait qu'une surface paraît posée sur l'écran plutôt
        //    que découpée dedans. Il ne dépend que de la hauteur, donc il est
        //    fabriqué une fois pour toutes dans onSizeChanged.
        forme.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(forme, rayon, rayon, pinceauFond)

        pinceauContour.color = ColorUtils.setAlphaComponent(couleurActive, 28)
        forme.inset(pinceauContour.strokeWidth / 2f, pinceauContour.strokeWidth / 2f)
        canvas.drawRoundRect(forme, rayon, rayon, pinceauContour)

        // 2. L'indicateur, et sa lueur. L'étirement suit la vitesse : il
        //    s'allonge en partant, reprend sa forme en arrivant.
        val etirement = min(ETIREMENT_MAX, abs(vitesse) * 0.030f)
        val centreX = (position + 0.5f) * largeurCase
        val demiLargeur = largeurCase * (0.34f + etirement * 0.5f)
        val demiHauteur = height * (0.34f - etirement * 0.06f)
        val centreY = height / 2f
        forme.set(
            centreX - demiLargeur, centreY - demiHauteur,
            centreX + demiLargeur, centreY + demiHauteur,
        )
        val rayonPilule = demiHauteur

        pinceauLueur.shader = RadialGradient(
            centreX, centreY, demiLargeur * 1.9f,
            ColorUtils.setAlphaComponent(couleurActive, 70),
            ColorUtils.setAlphaComponent(couleurActive, 0),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(centreX, centreY, demiLargeur * 1.9f, pinceauLueur)

        pinceauIndicateur.shader = LinearGradient(
            forme.left, 0f, forme.right, 0f,
            ColorUtils.setAlphaComponent(couleurActive, 64),
            ColorUtils.setAlphaComponent(couleurClaire, 40),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(forme, rayonPilule, rayonPilule, pinceauIndicateur)

        pinceauContour.color = ColorUtils.setAlphaComponent(couleurClaire, 120)
        canvas.drawRoundRect(forme, rayonPilule, rayonPilule, pinceauContour)

        // 3. L'onde au doigt, rognée à la forme de la barre.
        dessinerOnde(canvas, rayon)

        // 4. Icônes et libellés. Chaque onglet est traité selon sa *distance*
        //    à l'indicateur, et non selon un booléen actif/inactif : c'est ce
        //    qui fait que les deux onglets se répondent pendant le trajet au
        //    lieu de basculer d'un coup à mi-chemin.
        for ((index, element) in elements.withIndex()) {
            val proximite = (1f - abs(position - index)).coerceIn(0f, 1f)
            dessinerOnglet(canvas, index, element, proximite, largeurCase)
        }

        if (anime) postInvalidateOnAnimation()
    }

    private fun dessinerOnglet(
        canvas: Canvas,
        index: Int,
        element: Element,
        proximite: Float,
        largeurCase: Float,
    ) {
        val centreX = (index + 0.5f) * largeurCase

        // L'icône remonte pour laisser la place au libellé, qui n'existe que
        // sous l'onglet actif. Le déplacement et le fondu sont pilotés par la
        // même proximité : le texte ne peut donc pas apparaître avant que la
        // place soit faite.
        val montee = 7f * densite * proximite
        val centreY = height / 2f - montee
        val taille = 22f * densite * (1f + 0.16f * proximite)
        val demi = (taille / 2f).toInt()

        element.icone.setTint(ColorUtils.blendARGB(couleurDormante, couleurActive, proximite))
        element.icone.alpha = (255 * (0.62f + 0.38f * proximite)).toInt()
        element.icone.setBounds(
            (centreX - demi).toInt(), (centreY - demi).toInt(),
            (centreX + demi).toInt(), (centreY + demi).toInt(),
        )
        element.icone.draw(canvas)

        if (proximite <= 0.02f) return
        pinceauTexte.color = ColorUtils.setAlphaComponent(
            couleurActive, (255 * proximite * proximite).toInt().coerceIn(0, 255),
        )
        canvas.drawText(
            element.titre,
            centreX,
            centreY + taille / 2f + 11f * densite,
            pinceauTexte,
        )
    }

    private fun dessinerOnde(canvas: Canvas, rayonBarre: Float) {
        if (!ondeVive) return
        val age = (SystemClock.uptimeMillis() - ondeDebut) / ONDE_MS
        if (age >= 1f) {
            ondeVive = false
            return
        }
        val rayonMax = hypot(width.toFloat(), height.toFloat()) / 3f
        pinceauOnde.shader = null
        pinceauOnde.color = ColorUtils.setAlphaComponent(
            couleurClaire, (46 * (1f - age)).toInt().coerceIn(0, 255),
        )
        canvas.save()
        forme.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.clipRect(forme)
        canvas.drawCircle(ondeX, ondeY, rayonMax * age, pinceauOnde)
        canvas.restore()
        postInvalidateOnAnimation()
    }

    /**
     * Un pas de ressort.
     *
     * Intégration d'Euler semi-implicite : la vitesse est corrigée avant la
     * position, ce qui reste stable là où l'intégration explicite diverge dès
     * qu'une image saute. Le pas de temps est plafonné pour la même raison —
     * revenir d'un écran en veille donnerait sinon un dt d'une seconde et
     * projetterait l'indicateur hors de la barre.
     *
     * @return vrai s'il reste du mouvement à jouer
     */
    private fun avancerRessort(): Boolean {
        val maintenant = SystemClock.uptimeMillis()
        val dt = if (dernierInstant == 0L) 0f else {
            ((maintenant - dernierInstant) / 1000f).coerceAtMost(0.032f)
        }
        dernierInstant = maintenant

        val cible = actif.toFloat()
        if (dt <= 0f) return abs(cible - position) > REPOS_POSITION

        vitesse += ((cible - position) * RAIDEUR - vitesse * AMORTISSEMENT) * dt
        position += vitesse * dt

        if (abs(cible - position) < REPOS_POSITION && abs(vitesse) < REPOS_VITESSE) {
            position = cible
            vitesse = 0f
            dernierInstant = 0L
            return false
        }
        return true
    }

    // --- Doigt --------------------------------------------------------------

    override fun onTouchEvent(evenement: MotionEvent): Boolean {
        if (elements.isEmpty()) return false
        val index = (evenement.x / (width.toFloat() / elements.size)).toInt()
            .coerceIn(0, elements.size - 1)

        when (evenement.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                indexPresse = index
                ondeX = evenement.x
                ondeY = evenement.y
                ondeDebut = SystemClock.uptimeMillis()
                ondeVive = true
                postInvalidateOnAnimation()
                return true
            }

            MotionEvent.ACTION_UP -> {
                // Le doigt doit être parti de la même case : un glissement
                // d'un onglet à l'autre n'est pas un choix, c'est une hésitation.
                //
                // On ne se sélectionne pas soi-même : l'hôte rappellera
                // choisir() en basculant de section. Deux chemins pour un seul
                // état finissent toujours par diverger.
                if (index == indexPresse) {
                    performClick()
                    surChoix(index)
                }
                indexPresse = -1
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                indexPresse = -1
                return true
            }
        }
        return super.onTouchEvent(evenement)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * Ce que la barre annonce au lecteur d'écran.
     *
     * Une vue unique perd le découpage que quatre vues cliquables donnaient
     * gratuitement. On ne prétend pas le remplacer ici : on annonce au moins
     * où l'on se trouve, ce qu'une barre muette ne ferait pas.
     */
    private fun majDescription() {
        val titre = elements.getOrNull(actif)?.titre ?: return
        contentDescription = resources.getString(R.string.onglet_actif, titre)
    }
}
