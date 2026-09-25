package fr.ningbus.arbitre.moteur

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Breuillet, 25/09, 16:00 — Uber X, 9,00 € pour 3,10 km en 7 min 42.
 *
 * Relevée sur l'écran « Détails de la course », donc vérifiée après coup et
 * non lue par le moteur. C'est la course que tout chauffeur accepte sans
 * réfléchir : 2,90 € du kilomètre, bouclée en huit minutes. Elle est
 * effectivement excellente — **et elle peut aussi être mauvaise**, sans qu'un
 * seul des chiffres affichés ne change.
 *
 * Ce qui les sépare n'est pas sur l'écran : c'est la distance à parcourir pour
 * aller chercher le client. Porte à porte, 39 €/h. À trois kilomètres, 20 €/h.
 * Le prix, la distance, la durée et l'euro/kilomètre sont identiques.
 *
 * C'est la raison d'être de cette application, et la démonstration que la
 * règle du métier — « plus d'un euro le kilomètre » — ne discrimine rien ici :
 * elle vaut oui dans les deux cas.
 */
class BreuilletTest {

    private val course = Course(
        plateforme = "Uber",
        prix = 9.00,
        kmTrajet = 3.10,
        minutesTrajet = 7.7,
    )

    @Test
    fun `porte a porte, la course est tres bonne`() {
        val v = Arbitre.arbitrer(course)
        val heure = assertNotNull(v.euroHeure)
        assertTrue(heure > 35.0, "attendu autour de 39 €/h, obtenu $heure")
    }

    /**
     * Et pourtant pas de feu vert : l'approche n'a pas été lue, donc le calcul
     * la suppose nulle. Un PRENDS ici serait un PRENDS sur une hypothèse.
     */
    @Test
    fun `sans approche lue, jamais de feu vert`() {
        assertEquals(Decision.LIMITE, Arbitre.arbitrer(course).decision)
    }

    @Test
    fun `le budget d'approche remplace l'avertissement par un chiffre`() {
        val v = Arbitre.arbitrer(course)
        val budget = assertNotNull(v.budgetApprocheKm)
        assertTrue(budget in 1.4..2.0, "attendu autour de 1,7 km, obtenu $budget")

        val motif = assertNotNull(v.motif)
        assertTrue(motif.contains("rentable jusqu'à"), motif)
    }

    /**
     * Le budget tient sa promesse des deux côtés : juste en dessous la course
     * atteint l'objectif, juste au-dessus elle ne l'atteint plus.
     */
    @Test
    fun `le budget est bien le point de bascule`() {
        val budget = assertNotNull(Arbitre.arbitrer(course).budgetApprocheKm)
        val bareme = Bareme()

        fun heurePour(km: Double): Double {
            val v = Arbitre.arbitrer(
                course.copy(kmApproche = km, minutesApproche = Planifiees.minutesApproche(km)),
                bareme,
            )
            return assertNotNull(v.euroHeure)
        }

        assertTrue(
            heurePour(budget - 0.3) >= bareme.objectifHeure,
            "juste avant le budget, la course doit encore tenir l'objectif",
        )
        assertTrue(
            heurePour(budget + 0.3) < bareme.objectifHeure,
            "juste après le budget, elle ne doit plus le tenir",
        )
    }

    /**
     * La règle du métier ne sépare pas ces deux courses, l'euro/heure si.
     *
     * À cinq kilomètres d'approche, la course affiche encore 1,11 €/km — au
     *-dessus du seuil que tout le monde utilise — et paie 17 €/h.
     */
    @Test
    fun `l'euro par kilometre ne discrimine pas, l'euro par heure oui`() {
        val loin = Arbitre.arbitrer(
            course.copy(kmApproche = 5.0, minutesApproche = Planifiees.minutesApproche(5.0)),
        )
        assertTrue(
            assertNotNull(loin.euroKmRoule) > 1.0,
            "la règle du métier dirait encore oui : ${loin.euroKmRoule} €/km",
        )
        assertTrue(
            assertNotNull(loin.euroHeure) < 20.0,
            "et pourtant ${loin.euroHeure} €/h",
        )
        assertEquals(Decision.LAISSE, loin.decision)
    }

    /** Quand l'approche est annoncée, le budget n'a plus lieu d'être calculé. */
    @Test
    fun `approche lue, pas de budget`() {
        val v = Arbitre.arbitrer(course.copy(kmApproche = 1.0, minutesApproche = 4.0))
        assertNull(v.budgetApprocheKm)
    }
}
