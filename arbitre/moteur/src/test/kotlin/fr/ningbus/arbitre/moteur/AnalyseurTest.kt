package fr.ningbus.arbitre.moteur

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyseurTest {

    private fun proche(attendu: Double, obtenu: Double?, tol: Double = 0.01) {
        assertNotNull(obtenu)
        assertTrue(
            kotlin.math.abs(attendu - obtenu) < tol,
            "attendu $attendu, obtenu $obtenu",
        )
    }

    @Test
    fun `format Uber complet`() {
        val c = Analyseur.analyser(
            "UberX · 15,32 €\n4 min (1,3 km) de vous\n22 min (9,8 km) de trajet",
            "Uber",
        )
        proche(15.32, c.prix)
        proche(4.0, c.minutesApproche)
        proche(1.3, c.kmApproche)
        proche(22.0, c.minutesTrajet)
        proche(9.8, c.kmTrajet)
        assertTrue(c.exploitable)
    }

    @Test
    fun `espace insecable avant l'euro`() {
        val c = Analyseur.analyser("Course à 12,50\u00A0€ · 15 min (6 km) de trajet")
        proche(12.50, c.prix)
    }

    @Test
    fun `sans mot-cle, l'ordre de lecture decide`() {
        val c = Analyseur.analyser("18,40 € · 6 min (2,4 km) · 19 min (7,1 km)")
        proche(6.0, c.minutesApproche)
        proche(2.4, c.kmApproche)
        proche(19.0, c.minutesTrajet)
        proche(7.1, c.kmTrajet)
    }

    @Test
    fun `approche en metres`() {
        val c = Analyseur.analyser("9,80 € — 2 min (650 m) de vous — 11 min (3,2 km) de trajet")
        proche(0.65, c.kmApproche)
        proche(3.2, c.kmTrajet)
    }

    @Test
    fun `duree en heures`() {
        val c = Analyseur.analyser("78,00 € · 5 min de vous · 1 h 15 de trajet (62 km)")
        proche(75.0, c.minutesTrajet)
        proche(62.0, c.kmTrajet)
    }

    @Test
    fun `une heure de la journee n'est pas une duree`() {
        val c = Analyseur.analyser("Réservation 18h30 — 24,00 € — 20 min (9 km) de trajet")
        proche(20.0, c.minutesTrajet)
        proche(24.0, c.prix)
    }

    @Test
    fun `vitesse absurde relue comme approche`() {
        // "3 min" est l'approche, "8 km" le trajet : 8 km en 3 min est impossible.
        val c = Analyseur.analyser("12,50 € · 3 min · 8 km")
        proche(3.0, c.minutesApproche)
        proche(8.0, c.kmTrajet)
        assertNull(c.minutesTrajet)
        assertTrue(c.remarques.any { it.contains("vitesse impossible") })
    }

    @Test
    fun `plusieurs montants, le plus eleve gagne`() {
        val c = Analyseur.analyser("14,00 € + 2,50 € de bonus · 10 min (4 km) de trajet")
        proche(14.00, c.prix)
        assertTrue(c.remarques.any { it.contains("plusieurs montants") })
    }

    @Test
    fun `notification sans prix n'est pas exploitable`() {
        val c = Analyseur.analyser("Nouvelle demande de course à proximité")
        assertNull(c.prix)
        assertTrue(!c.exploitable)
    }

    @Test
    fun `un seul couple sans indice devient le trajet et le signale`() {
        val c = Analyseur.analyser("22,00 € — 18 min (6,5 km)")
        proche(18.0, c.minutesTrajet)
        proche(6.5, c.kmTrajet)
        assertNull(c.minutesApproche)
        assertTrue(c.remarques.any { it.contains("approche") })
    }

    @Test
    fun `format anglais away`() {
        val c = Analyseur.analyser("New trip 21.40 € · 5 min (2.1 km) away · 17 min (8.4 km) trip")
        proche(21.40, c.prix)
        proche(5.0, c.minutesApproche)
        proche(17.0, c.minutesTrajet)
        proche(8.4, c.kmTrajet)
    }

    /**
     * La carte d'offre affichée dans l'application Uber, relevée à l'écran.
     * Aucune notification n'est postée quand l'app est au premier plan : ce
     * texte est celui que lit le service d'accessibilité, dans l'ordre où les
     * vues apparaissent.
     */
    private val CARTE_UBER = """
        UberX Priority
        17,08 €
        4,80
        Montant net de frais
        +2,34 € inclus pour la prise en charge
        16 min (à 10.9 km)
        125 Rue Lieutenant André Lemoal, 91640 Briis-sous-Forges, France
        Course de 12.1 km
        121 Chem. du Vieux Pavé de Bruyères le Châtel, 91310 Saint-Germain-lès-Arpajon, France
        Mise en relation
    """.trimIndent()

    @Test
    fun `carte d'offre Uber lue a l'ecran`() {
        val c = Analyseur.analyser(CARTE_UBER, "Uber")
        proche(17.08, c.prix)          // et non le bonus de 2,34 €
        proche(16.0, c.minutesApproche)
        proche(10.9, c.kmApproche)
        proche(12.1, c.kmTrajet)
        assertNull(c.minutesTrajet)    // Uber ne l'annonce pas sur cette carte
        assertTrue(c.exploitable)
    }

    @Test
    fun `la note du chauffeur n'est pas un montant`() {
        // « ★ 4,80 » n'a pas de symbole euro : il ne doit jamais être lu
        // comme un prix, sous peine de refuser toutes les courses.
        val c = Analyseur.analyser(CARTE_UBER, "Uber")
        assertTrue(c.prix!! > 5.0, "la note 4,80 a été prise pour le prix")
    }

    @Test
    fun `les codes postaux ne sont ni des metres ni des kilometres`() {
        val c = Analyseur.analyser(CARTE_UBER, "Uber")
        // 91640 et 91310 traînent dans les adresses ; seules 10.9 et 12.1
        // sont des distances.
        proche(10.9, c.kmApproche)
        proche(12.1, c.kmTrajet)
    }

    @Test
    fun `carte Uber sans ligne de bonus`() {
        // Sans « prise en charge », plus aucun mot ne désigne l'approche :
        // seule l'adjacence « 16 min (à 10.9 km) » permet de la retrouver,
        // une fois « Course de 12.1 km » identifié comme le trajet.
        val c = Analyseur.analyser(
            """
            UberX
            14,20 €
            Montant net de frais
            16 min (à 10.9 km)
            125 Rue Lieutenant André Lemoal, 91640 Briis-sous-Forges
            Course de 12.1 km
            121 Chem. du Vieux Pavé, 91310 Saint-Germain-lès-Arpajon
            """.trimIndent(),
            "Uber",
        )
        proche(14.20, c.prix)
        proche(16.0, c.minutesApproche)
        proche(10.9, c.kmApproche)
        proche(12.1, c.kmTrajet)
    }

    @Test
    fun `normalisation des espaces et de la casse`() {
        assertEquals("a · b", Analyseur.normaliser("A\n·\tB"))
    }
}
