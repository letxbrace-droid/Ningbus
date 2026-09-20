package fr.ningbus.arbitre.moteur

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Le tri entre une offre et le reste de l'écran d'un chauffeur.
 *
 * Les textes sont ceux de vraies captures : c'est la seule façon de vérifier
 * que le score ne fait pas que fonctionner sur les exemples qui l'ont inspiré.
 */
class DetecteurTest {

    private val ulis = """
        UberX Priority
        12,51 €
        Paiement en espèces
        4,55
        Montant net de frais
        +2,43 € inclus pour la prise en charge
        9 min (à 2.5 km)
        Rue d'Argonne & Avenue de Champagne, 91940 Les Ulis, France
        Course de 12.6 km
        Rue du Saut-du-Loup, 91470 Limours, France
        Mise en relation
    """.trimIndent()

    private val navigation = """
        En route vers la dépose
        12,51 €
        8.2 km restants
        Arrivée estimée 12:14
        Terminer la course
    """.trimIndent()

    private val preferences = """
        Préférences de course
        Tarif minimum 1,00 €/km
        Distance maximale 30 km
    """.trimIndent()

    private fun juger(texte: String) = Detecteur.juger(texte, Analyseur.analyser(texte, "Uber"))

    @Test
    fun `une vraie carte d'offre est certaine`() {
        val jugement = juger(ulis)
        assertEquals(Nature.OFFRE_CERTAINE, jugement.nature)
        assertTrue(jugement.arbitrable)
    }

    @Test
    fun `un ecran de navigation est ecarte`() {
        val jugement = juger(navigation)
        assertFalse(jugement.arbitrable, "une bulle surgirait en pleine conduite")
        assertTrue(
            jugement.indices.any { it.contains("navigation") },
            "le motif du refus doit être lisible",
        )
    }

    @Test
    fun `un ecran de preferences n'est pas une offre`() {
        val jugement = juger(preferences)
        assertEquals(Nature.AUTRE, jugement.nature)
    }

    /**
     * Le cas limite qui justifie le score plutôt qu'une règle binaire : une
     * offre dont le bouton n'a pas encore été dessiné reste reconnaissable à
     * ses deux distances et à son montant.
     */
    @Test
    fun `une offre sans bouton d'acceptation reste probable`() {
        val jugement = juger(ulis.substringBefore("Mise en relation"))
        assertTrue(
            jugement.arbitrable,
            "score ${jugement.score} — ${jugement.indices}",
        )
    }

    /**
     * Et le cas symétrique : un montant seul ne suffit pas. Une page web, une
     * conversation, un écran d'accueil en portent tous.
     */
    @Test
    fun `un montant seul ne fait pas une offre`() {
        val jugement = juger("Votre commande de 24,90 € a été expédiée")
        assertFalse(jugement.arbitrable)
    }
}
