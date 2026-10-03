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
 * qui lui parvenait déjà abîmé.
 *
 * **La cause exacte n'est venue qu'avec le journal du 21/09**, qui conserve la
 * capture brute. La reconnaissance avait rendu `5 min (à l.6 km)` : le chiffre
 * **1** était devenu la lettre **l**, et le motif de distance, qui ne cherche
 * que des chiffres, n'avait vu que le `6`. Ce n'était donc pas une décimale
 * coupée, et la couture posée pour elle ne pouvait rien y faire — l'essai
 * ci-dessous, sur le texte réellement relevé, l'établit.
 *
 * Tout reste ici comme garde-fou : les deux coupures possibles, la confusion
 * de lettres, et le texte intact qui ne doit pas bouger.
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
     * Le texte exactement tel que le journal du 21/09 l'a conservé — le seul
     * qui décrive vraiment la panne.
     */
    @Test
    fun `un chiffre pris pour une lettre est redresse`() {
        val abime = recoudreNombres(carte.replace("1.6 km", "l.6 km"))
        val c = Analyseur.analyser(abime, "Uber")
        assertEquals(1.6, c.kmApproche, "c'est le 6,0 km affiché à Grigny")
        assertEquals(16.4, c.kmTrajet)
    }

    /** Les autres sosies relevés : le O du zéro, la barre du un. */
    @Test
    fun `les autres sosies de chiffres sont redresses`() {
        assertEquals("Course de 10.5 km", recoudreNombres("Course de 1O.5 km"))
        assertEquals("12 min", recoudreNombres("l2 min"))
    }

    /**
     * Le garde-fou du redressement : une lettre isolée n'est pas un chiffre.
     * Sans vrai chiffre dans le jeton, on ne fabrique rien.
     */
    @Test
    fun `une lettre seule ne devient pas un chiffre`() {
        assertEquals("allée O km 12", recoudreNombres("allée O km 12"))
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
