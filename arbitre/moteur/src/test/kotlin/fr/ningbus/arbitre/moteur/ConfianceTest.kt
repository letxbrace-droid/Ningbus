package fr.ningbus.arbitre.moteur

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * La confiance dit ce que vaut le verdict, pas ce qu'il conseille.
 *
 * Ce qui est éprouvé ici, c'est surtout qu'elle ne peut **jamais** rendre le
 * moteur plus permissif. Une mesure de fiabilité qui autoriserait un feu vert
 * refusé jusque-là ferait exactement le contraire de ce qu'on attend d'elle.
 */
class ConfianceTest {

    private val complete = Course(
        plateforme = "Uber",
        prix = 17.08,
        minutesApproche = 16.0,
        kmApproche = 10.9,
        minutesTrajet = 20.0,
        kmTrajet = 12.1,
    )

    @Test
    fun `une offre entierement lue vaut cent pour cent`() {
        assertEquals(100, Confiance.de(complete).pourcent)
        assertTrue(Confiance.de(complete).fiable)
    }

    @Test
    fun `une duree estimee coute la moitie de son poids`() {
        val sansDuree = complete.copy(minutesTrajet = null)
        val confiance = Confiance.de(sansDuree, minutesTrajetEstime = true)

        // 15 % pour la durée du trajet, estimée donc comptée moitié.
        assertEquals(93, confiance.pourcent)
        assertEquals(
            EtatLecture.ESTIME,
            confiance.lectures.first { it.champ == "durée de la course" }.etat,
        )
    }

    @Test
    fun `une approche absente coute un quart de la confiance`() {
        val sansApproche = complete.copy(minutesApproche = null, kmApproche = null)
        val confiance = Confiance.de(sansApproche)

        assertEquals(75, confiance.pourcent)
        assertEquals(2, confiance.manquants.size)
    }

    @Test
    fun `sans prix ni trajet la confiance est au plancher`() {
        val vide = Course(plateforme = "Uber")
        val confiance = Confiance.de(vide)

        assertEquals(0, confiance.pourcent)
        assertFalse(confiance.fiable)
    }

    /**
     * Le garde-fou qui compte. La confiance s'ajoute aux règles d'origine ;
     * elle n'en remplace aucune. Une course dont la durée est estimée reste
     * plafonnée à LIMITE même si sa confiance atteint 93 %.
     */
    @Test
    fun `une duree estimee reste plafonnee a limite malgre une confiance haute`() {
        // Approche ramenée sous la limite du barème : sans cela le veto
        // d'approche trancherait avant même qu'on arrive à la règle éprouvée
        // ici, et l'essai passerait au vert pour la mauvaise raison.
        val genereuse = Bareme(objectifHeure = 5.0)
        val verdict = Arbitre.arbitrer(
            complete.copy(minutesApproche = 8.0, minutesTrajet = null),
            genereuse,
        )

        assertTrue(verdict.confiance.pourcent >= 90, "la confiance devrait rester haute")
        assertEquals(Decision.LIMITE, verdict.decision)
    }

    @Test
    fun `le verdict porte les kilometres a vide`() {
        val verdict = Arbitre.arbitrer(complete, Bareme(partRetour = 0.5))

        // 10,9 km d'approche + la moitié des 12,1 km du trajet.
        assertEquals(16.95, verdict.kmAVide!!, 0.01)
    }
}
