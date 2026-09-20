package fr.ningbus.arbitre.moteur

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * La course de Grigny, relevée le 20/09 à 21:04 par-dessus TikTok.
 *
 * Première vraie carte lue sur la route par la 1.8, et première à faire
 * mentir l'application : elle annonçait « 5 min (à 1.6 km) » d'approche, et
 * la bulle a affiché **6,0 km**. Quatre fois trop, avec la cascade entière
 * derrière — 72 km/h d'approche, facteur de trafic au plafond, et une course
 * de 16,4 km estimée à 17 min.
 *
 * L'essai a d'abord servi à départager deux coupables possibles. Sur le texte
 * propre, l'analyseur rend 1,6 km : il était donc innocent, et c'est le texte
 * qui lui parvenait déjà abîmé. Une décimale coupée en deux, signature d'une
 * reconnaissance sur image — l'arbre d'accessibilité, lui, rend le texte
 * exact.
 *
 * Il reste ici comme garde-fou, avec les deux formes d'abîmage observées.
 */
class GrignyTest {

    private val carte = """
        UberX
        16,43 €
        Paiement en espèces
        4,70
        Montant net de frais
        5 min (à 1.6 km)
        14 Rue Saint-Exupéry, 91350 Grigny, France
        Course de 16.4 km
        139 Rte de Brie, 91800 Brunoy, France
        Mise en relation
    """.trimIndent()

    @Test
    fun `la carte telle qu'elle est ecrite`() {
        val c = Analyseur.analyser(carte, "Uber")

        // Le prix, et non la note du passager de 4,70 — qui n'a pas de symbole
        // euro — ni le montant d'une quelconque majoration.
        assertEquals(16.43, c.prix)
        assertEquals(1.6, c.kmApproche)
        assertEquals(5.0, c.minutesApproche)
        assertEquals(16.4, c.kmTrajet)
    }

    @Test
    fun `une decimale coupee par la reconnaissance est recousue`() {
        val abime = recoudreNombres(carte.replace("1.6 km", "1. 6 km"))
        assertEquals(1.6, Analyseur.analyser(abime, "Uber").kmApproche)
    }

    @Test
    fun `une decimale dont le point a disparu est recousue`() {
        val abime = recoudreNombres(carte.replace("1.6 km", "1 6 km"))
        assertEquals(1.6, Analyseur.analyser(abime, "Uber").kmApproche)
    }

    /**
     * Le garde-fou de la couture : un séparateur de milliers français groupe
     * par trois, jamais par un. « 2 400 km » doit rester 2400 km.
     */
    @Test
    fun `un separateur de milliers n'est pas une decimale coupee`() {
        assertEquals("Course de 2 400 km", recoudreNombres("Course de 2 400 km"))
    }

    /**
     * Et le cas qui compte le plus : un texte juste ne doit pas être touché.
     * Une réparation qui abîme ce qui marchait coûte plus cher que la panne
     * qu'elle corrige.
     */
    @Test
    fun `un texte intact traverse la couture sans changer`() {
        assertEquals(carte, recoudreNombres(carte))
    }
}
