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
        val c = Analyseur.analyser("Course à 12,50 € · 15 min (6 km) de trajet")
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

    @Test
    fun `normalisation des espaces et de la casse`() {
        assertEquals("a · b", Analyseur.normaliser("A\n·\tB"))
    }
}
