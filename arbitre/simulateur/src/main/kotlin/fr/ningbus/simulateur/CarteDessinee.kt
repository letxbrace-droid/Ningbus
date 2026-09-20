package fr.ningbus.simulateur

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * La carte d'offre **dessinée**, et non écrite.
 *
 * C'est l'angle mort de l'arbre d'accessibilité, et il s'élargit : une
 * application qui peint son texte sur un `Canvas` — ou qui compose une vue
 * sans lui donner de sémantique — n'expose aucun nœud de texte. Le service
 * lit alors une fenêtre parfaitement vide pendant qu'un chauffeur, lui, voit
 * une offre à dix-sept euros.
 *
 * Cette vue reproduit ce cas exactement : elle n'a ni `text`, ni
 * `contentDescription`, ni enfant. Tout ce qu'elle affiche n'existe que sous
 * forme de pixels. Un banc d'essai qui ne saurait pas produire cette
 * situation ne pourrait pas prouver que la reconnaissance de texte sert à
 * quelque chose — et l'on reviendrait à supposer.
 */
class CarteDessinee(contexte: Context, private val lignes: List<String>) : View(contexte) {

    private val densite = resources.displayMetrics.density

    private val encre = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 15f * densite
    }

    private val encreTitre = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 30f * densite
        isFakeBoldText = true
    }

    private val fond = Paint().apply { color = Color.WHITE }

    private val marge = 16f * densite
    private val interligne = 26f * densite

    override fun onMeasure(largeurMesuree: Int, hauteurMesuree: Int) {
        val largeur = MeasureSpec.getSize(largeurMesuree)
        val hauteur = (marge * 2 + interligne * (lignes.size + 1)).toInt()
        setMeasuredDimension(largeur, hauteur)
    }

    override fun onDraw(toile: Canvas) {
        toile.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fond)
        var y = marge + interligne
        for ((index, ligne) in lignes.withIndex()) {
            // La deuxième ligne est le montant : elle est grande sur la vraie
            // carte, et la reconnaissance de texte doit s'en accommoder.
            val pinceau = if (index == 1) encreTitre else encre
            toile.drawText(ligne, marge, y, pinceau)
            y += if (index == 1) interligne * 1.6f else interligne
        }
    }
}
