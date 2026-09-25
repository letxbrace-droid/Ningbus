package fr.ningbus.arbitre.moteur

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L'écran « Détails de la course » d'Uber, relevé le 25/09 à 16:22.
 *
 * Il ne propose rien, donc il ne doit poser aucune bulle. Il porte en revanche
 * le seul chiffre que la carte d'offre ne peut pas donner : **la durée
 * réellement passée**, mesurée par la plateforme elle-même.
 *
 * Et il porte aussi, en creux, la raison pour laquelle l'approche doit être
 * capturée au moment de l'offre : elle n'est pas facturée, donc elle n'est pas
 * archivée. Sur cet écran, les 4 km parcourus pour aller chercher le client
 * n'existent plus nulle part.
 */
class DetailsTest {

    private val ecran = """
        Détails de la course
        Uber X • 25 sept. 2026 • 16:00
        9,00 €
        Tarification affichée à l'avance : 9,00 €
        Durée
        7 min 42 s
        Distance
        3.10 km
        91650 Breuillet, France
        91650 Breuillet, France
        1 point générés
        Vos revenus
        Prix total du trajet (net de  9,00 €
    """.trimIndent()

    @Test
    fun `l'ecran de bilan est reconnu`() {
        assertTrue(Details.estEcran(ecran))
    }

    /** Une carte d'offre n'est pas un bilan : elle ne doit pas être confondue. */
    @Test
    fun `une carte d'offre n'est pas un ecran de bilan`() {
        val offre = "Bolt Carte 9,80 € (net, TTC) 4 min • 1,4 km 20 Rue Voltaire, " +
            "Brétigny-sur-Orge 12 min • 8,2 km Accepter"
        assertTrue(!Details.estEcran(offre))
        assertNull(Details.lire(offre))
    }

    @Test
    fun `les trois chiffres sont lus, secondes comprises`() {
        val r = assertNotNull(Details.lire(ecran))
        assertEquals(9.00, r.prix, 0.001)
        assertEquals(3.10, r.km, 0.001)
        // 7 min 42 s = 7,7 min. Arrondir à 7 fausserait le rapport mesuré.
        assertEquals(7.7, r.minutes, 0.01)
    }

    @Test
    fun `sans duree, aucune mesure n'est rendue`() {
        assertNull(Details.lire("Détails de la course 9,00 € Distance 3.10 km"))
    }

    /**
     * Le garde-fou le plus important de ce fichier.
     *
     * Uber emploie « tarification affichée à l'avance » sur ses cartes
     * d'offre comme sur ses bilans : le prix garanti est un argument de
     * vente. Un écran qu'on peut accepter est une proposition, et le prendre
     * pour une archive reviendrait à ne plus jamais l'arbitrer — la panne la
     * plus grave possible ici, et la plus silencieuse.
     */
    @Test
    fun `un ecran qu'on peut accepter n'est jamais un bilan`() {
        val offre = """
            Uber X
            Tarification affichée à l'avance : 9,00 €
            Détails de la course
            Durée 12 min
            Distance 8.2 km
            Accepter
        """.trimIndent()
        assertTrue(!Details.estEcran(offre), "une carte acceptable reste une offre")
        assertNull(Details.lire(offre))
    }
}

/**
 * Le coefficient mesuré : ce que valent les estimations d'une plateforme,
 * calculé sur ses propres chiffres.
 */
class CalibrageTest {

    private fun mesures(vararg rapports: Double, n: String = "Uber") =
        rapports.map { Mesure(n, 20.0, 20.0 * it) }

    @Test
    fun `sous le plancher de mesures, on ne conclut rien`() {
        assertNull(Calibrage.facteur(mesures(1.2, 1.3, 1.1, 1.25)))
        assertNotNull(Calibrage.facteur(mesures(1.2, 1.3, 1.1, 1.25, 1.15)))
    }

    /**
     * La médiane, et non la moyenne. Une course interrompue, un client qui
     * fait attendre : une seule valeur folle déplace une moyenne et laisse
     * une médiane où elle est.
     */
    @Test
    fun `une mesure aberrante ne deplace pas le coefficient`() {
        val sain = assertNotNull(Calibrage.facteur(mesures(1.1, 1.15, 1.2, 1.25, 1.3)))
        val pollue = assertNotNull(Calibrage.facteur(mesures(1.1, 1.15, 1.2, 1.25, 1.3, 9.0)))
        assertTrue(
            kotlin.math.abs(sain - pollue) < 0.06,
            "la valeur folle a déplacé le coefficient de $sain à $pollue",
        )
    }

    @Test
    fun `le coefficient est borne`() {
        assertEquals(
            Calibrage.PLAFOND,
            assertNotNull(Calibrage.facteur(mesures(5.0, 6.0, 7.0, 8.0, 9.0))),
            0.001,
        )
    }

    @Test
    fun `chaque plateforme est mesuree separement`() {
        val toutes = mesures(1.3, 1.3, 1.3, 1.3, 1.3, n = "Uber") +
            mesures(1.0, 1.0, 1.0, 1.0, 1.0, n = "Bolt")
        assertEquals(1.3, assertNotNull(Calibrage.facteur(toutes, "Uber")), 0.01)
        assertEquals(1.0, assertNotNull(Calibrage.facteur(toutes, "Bolt")), 0.01)
    }

    @Test
    fun `le resume nomme le sens de l'ecart et le nombre de courses`() {
        val texte = assertNotNull(Calibrage.resume(mesures(1.2, 1.2, 1.2, 1.2, 1.2), "Uber"))
        assertTrue(texte.contains("sous-estime"), texte)
        assertTrue(texte.contains("20 %"), texte)
        assertTrue(texte.contains("5 courses"), texte)
    }
}

/**
 * Le coefficient mesuré, une fois branché sur le moteur.
 *
 * C'est la course du 25/09 qui a motivé tout ceci : 47,7 km annoncés en
 * 45 min, 52 min sur la carte routière. Une majoration de 15 % que la prudence
 * de trafic ne pouvait pas voir, puisqu'à 63 km/h son coefficient vaut
 * exactement 1,0.
 */
class FacteurDureeTest {

    private val longue = Course(
        plateforme = "Uber",
        prix = 47.68,
        kmApproche = 5.0,
        minutesApproche = 12.0,
        kmTrajet = 47.7,
        minutesTrajet = 45.0,
    )

    @Test
    fun `sans mesure, rien ne change`() {
        assertEquals(
            Arbitre.arbitrer(longue).euroHeure!!,
            Arbitre.arbitrer(longue, Bareme(facteurDureeMesure = null)).euroHeure!!,
            0.001,
        )
    }

    @Test
    fun `le coefficient mesure alourdit le temps et baisse l'euro par heure`() {
        val sans = Arbitre.arbitrer(longue).euroHeure!!
        val avec = Arbitre.arbitrer(longue, Bareme(facteurDureeMesure = 1.15)).euroHeure!!
        assertTrue(avec < sans, "$avec devrait être sous $sans")
        assertTrue(avec < 25.0, "à 15 % près, la course passe sous l'objectif : $avec")
    }

    /**
     * Il remplace la prudence de trafic, il ne s'y ajoute pas : les deux
     * répondent à la même question, et les empiler majorerait deux fois.
     */
    @Test
    fun `mesure et prudence ne se cumulent pas`() {
        val embouteille = longue.copy(minutesTrajet = 200.0) // 14 km/h : bouchons
        val prudence = Arbitre.arbitrer(embouteille, Bareme(prudenceTrafic = true))
        val mesure = Arbitre.arbitrer(
            embouteille,
            Bareme(prudenceTrafic = true, facteurDureeMesure = 1.0),
        )
        assertTrue(
            mesure.minutesTotal!! < prudence.minutesTotal!!,
            "un coefficient mesuré à 1,0 doit annuler la majoration supposée",
        )
    }
}
