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
     * Ce que la journée du 21/09 a corrigé, et c'est l'inverse de ce qui était
     * écrit ici.
     *
     * L'essai précédent exigeait qu'une carte privée de son bouton reste
     * arbitrable : deux distances et un montant, cela paraissait suffire. Le
     * terrain a répondu par 55 verdicts sur 80 rendus hors d'une application
     * chauffeur — l'écran d'accueil, la barre de notifications, une boîte
     * mail, une conversation — tous à 60 points, tous par ce chemin-là.
     *
     * Le prix de la correction est connu et assumé : une carte d'offre lue
     * avant que son bouton ne soit dessiné n'est plus arbitrée toute seule.
     * Elle l'est à la lecture suivante, qui vient quelques centièmes plus
     * tard, ou d'un appui sur la pastille. Une offre retardée se rattrape ;
     * une bulle sur l'écran d'accueil use la confiance qu'on met dans l'outil.
     */
    @Test
    fun `des chiffres sans marque d'offre ne suffisent plus`() {
        val jugement = juger(ulis.substringBefore("Mise en relation"))
        assertFalse(
            jugement.arbitrable,
            "score ${jugement.score} — ${jugement.indices}",
        )
        assertEquals(Nature.AMBIGU, jugement.nature)
    }

    /** Un compte à rebours vaut le bouton : il n'existe que sur une offre. */
    @Test
    fun `un compte a rebours suffit a marquer une offre`() {
        val jugement = juger(
            ulis.substringBefore("Mise en relation") + "\n12 s restantes"
        )
        assertTrue(jugement.arbitrable, "score ${jugement.score}")
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

    /**
     * Relevé du 21/09, écran « Demandes de courses planifiées » de Bolt.
     *
     * Deux courses empilées, et l'analyseur en faisait une seule : le prix de
     * la seconde avec la distance de la première. Quatorze verdicts de la
     * journée sont sortis de cet écran, tous faux, tous annoncés à 83 % de
     * confiance.
     */
    @Test
    fun `une liste de courses planifiees n'est pas arbitree`() {
        val liste = """
            Demandes de courses planifiées
            Demandes
            Accepté
            mar., 22 septembre
            20,79 € • 22 sept., 02:20–02:25
            Bolt
            18.1km
            Près de Rue Michel-Ange, 16e Arrondissement, Paris 75016, France
            26,08 € • 22 sept., 03:55–04:00
            Comfort
            25.6km
            Près de Rue Pelleport, 20e Arrondissement, Paris 75020, France
            Voir toutes les demandes
        """.trimIndent()
        val jugement = juger(liste)
        assertEquals(Nature.PLUSIEURS_OFFRES, jugement.nature)
        assertFalse(jugement.arbitrable)
        assertTrue(
            jugement.indices.any { it.contains("empilées") },
            "le journal doit dire pourquoi : ${jugement.indices}",
        )
    }

    /** Un bonus de prise en charge n'est pas une seconde course. */
    @Test
    fun `un bonus inclus ne fait pas deux offres`() {
        assertEquals(1, Analyseur.compterOffres(ulis))
    }
}
