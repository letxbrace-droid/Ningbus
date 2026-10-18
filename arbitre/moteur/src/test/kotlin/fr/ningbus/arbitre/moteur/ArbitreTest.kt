package fr.ningbus.arbitre.moteur

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArbitreTest {

    /** Barème neutre : pas de retour à vide, pas de prudence, calcul nu. */
    private val nu = Bareme(partRetour = 0.0, prudenceTrafic = false, minutesAttente = 0.0)

    @Test
    fun `calcul de base verifiable a la main`() {
        // 20 € · approche 5 min / 2 km · trajet 25 min / 10 km
        // km total 12 → coût 12 × 0,22 = 2,64 € → net 17,36 €
        // temps total 30 min → 34,72 €/h
        val v = Arbitre.arbitrer(
            Course(prix = 20.0, minutesApproche = 5.0, kmApproche = 2.0,
                minutesTrajet = 25.0, kmTrajet = 10.0),
            nu,
        )
        assertEquals(12.0, v.kmTotal!!, 0.001)
        assertEquals(17.36, v.revenuNet!!, 0.001)
        assertEquals(30.0, v.minutesTotal!!, 0.001)
        assertEquals(34.72, v.euroHeure!!, 0.01)
        assertEquals(Decision.PRENDS, v.decision)
    }

    @Test
    fun `le retour a vide fait basculer le verdict`() {
        // Même course, mais 100 % du trajet à refaire à vide pour rentrer.
        val course = Course(prix = 20.0, minutesApproche = 5.0, kmApproche = 2.0,
            minutesTrajet = 25.0, kmTrajet = 10.0)
        val avec = Arbitre.arbitrer(course, nu.copy(partRetour = 1.0))
        val sans = Arbitre.arbitrer(course, nu)
        assertTrue(avec.euroHeure!! < sans.euroHeure!!)
        assertEquals(22.0, avec.kmTotal!!, 0.001)   // 2 + 10 + 10
        assertEquals(55.0, avec.minutesTotal!!, 0.001) // 30 + 25 min de retour
    }

    @Test
    fun `une longue approche pour une courte course est refusee`() {
        val v = Arbitre.arbitrer(
            Course(prix = 9.0, minutesApproche = 10.0, kmApproche = 4.0,
                minutesTrajet = 8.0, kmTrajet = 3.0),
            Bareme(),
        )
        assertEquals(Decision.LAISSE, v.decision)
        assertTrue(v.alertes.any { it.contains("vide") })
    }

    @Test
    fun `veto sur le prix plancher`() {
        val v = Arbitre.arbitrer(
            Course(prix = 5.0, minutesApproche = 1.0, kmApproche = 0.3,
                minutesTrajet = 4.0, kmTrajet = 1.5),
            Bareme(prixPlancher = 6.0),
        )
        assertEquals(Decision.LAISSE, v.decision)
        assertTrue(v.resume.contains("plancher"))
    }

    @Test
    fun `veto sur l'approche trop longue meme si la course paie bien`() {
        val v = Arbitre.arbitrer(
            Course(prix = 60.0, minutesApproche = 20.0, kmApproche = 12.0,
                minutesTrajet = 40.0, kmTrajet = 35.0),
            Bareme(approcheMaxMinutes = 12.0),
        )
        assertEquals(Decision.LAISSE, v.decision)
        assertTrue(v.resume.contains("approche", ignoreCase = true))
    }

    @Test
    fun `bouchons detectes par la vitesse implicite`() {
        // 4 km en 30 min = 8 km/h
        val v = Arbitre.arbitrer(
            Course(prix = 22.0, minutesApproche = 3.0, kmApproche = 1.0,
                minutesTrajet = 30.0, kmTrajet = 4.0),
            Bareme(partRetour = 0.0),
        )
        assertEquals(Trafic.BOUCHONS, v.trafic)
        assertTrue(v.alertes.any { it.contains("bouchons") })
        // La durée majorée doit allonger le temps total au-delà du brut.
        assertTrue(v.minutesTotal!! > 33.0)
    }

    @Test
    fun `voie rapide reconnue`() {
        val v = Arbitre.arbitrer(
            Course(prix = 55.0, minutesApproche = 4.0, kmApproche = 2.0,
                minutesTrajet = 30.0, kmTrajet = 40.0),
            Bareme(),
        )
        assertEquals(Trafic.VOIE_RAPIDE, v.trafic)
    }

    @Test
    fun `approche inconnue ne donne jamais de feu vert`() {
        val v = Arbitre.arbitrer(
            Course(prix = 40.0, minutesTrajet = 20.0, kmTrajet = 8.0),
            nu,
        )
        assertEquals(Decision.LIMITE, v.decision)
        assertTrue(v.alertes.any { it.contains("approche inconnue") })
    }

    @Test
    fun `sans montant, aucun arbitrage`() {
        val v = Arbitre.arbitrer(Course(minutesTrajet = 20.0, kmTrajet = 8.0), nu)
        assertEquals(Decision.INCOMPLET, v.decision)
    }

    @Test
    fun `duree manquante estimee sur la longueur du trajet, corrigee du trafic`() {
        // Approche : 4 km en 12 min, soit 20 km/h là où l'on attendrait 26 —
        // le trafic vaut donc 0,77. Le trajet de 10 km, qui se parcourt
        // normalement à 38 km/h, est estimé à 29 km/h, soit 20,5 min.
        val v = Arbitre.arbitrer(
            Course(prix = 18.0, minutesApproche = 12.0, kmApproche = 4.0, kmTrajet = 10.0),
            nu.copy(approcheMaxMinutes = 20.0),
        )
        assertEquals(12.0 + 20.5, v.minutesTotal!!, 0.2)
        assertTrue(v.alertes.any { it.contains("trafic mesuré sur l'approche") })
        assertEquals(Decision.LIMITE, v.decision) // jamais vert sur une estimation
    }

    @Test
    fun `sans approche mesurable, le trafic est supposé normal`() {
        // 11 km se parcourent normalement à 38 km/h, soit 17 min.
        val v = Arbitre.arbitrer(Course(prix = 18.0, kmTrajet = 11.0), nu)
        assertEquals(11.0 / 38.0 * 60.0, v.minutesTotal!!, 0.2)
        assertTrue(v.alertes.any { it.contains("trafic supposé normal") })
    }

    @Test
    fun `une approche en ville ne condamne pas un trajet sur route`() {
        // Le cas des Ulis : 2,5 km de rues en 9 min. Extrapoler cette vitesse
        // donnerait 45 min pour 12,6 km de départementale — le double du vrai.
        val v = Arbitre.arbitrer(
            Course(prix = 12.51, minutesApproche = 9.0, kmApproche = 2.5, kmTrajet = 12.6),
            nu.copy(approcheMaxMinutes = 20.0),
        )
        val trajet = v.minutesTotal!! - 9.0
        assertTrue(trajet in 18.0..26.0, "durée de trajet invraisemblable : $trajet min")
    }

    @Test
    fun `une course longue se parcourt plus vite qu'une course courte`() {
        assertTrue(vitesseTypique(2.0) < vitesseTypique(15.0))
        assertTrue(vitesseTypique(15.0) < vitesseTypique(40.0))
    }

    @Test
    fun `le facteur de trafic reste borné`() {
        // Approche de 200 m dans un parking : 1,2 km/h. Sans borne, elle
        // condamnerait toutes les courses.
        val etouffe = facteurTrafic(0.2, 10.0)!!
        assertTrue(etouffe >= 0.6, "facteur non borné : $etouffe")
        // Approche sur voie rapide : 90 km/h là où l'on attend 26.
        val degage = facteurTrafic(5.0, 3.3)!!
        assertTrue(degage <= 1.5, "facteur non borné : $degage")
    }

    @Test
    fun `la course Uber de Briis-sous-Forges est refusee`() {
        // 17,08 € pour 16 min et 10,9 km d'approche, puis 12,1 km de course :
        // l'approche seule dépasse la limite, et l'euro/heure est très en
        // dessous de l'objectif. C'est exactement le genre de course qu'un
        // chauffeur accepte au vu du seul montant affiché.
        val v = Arbitre.arbitrer(
            Course(prix = 17.08, minutesApproche = 16.0, kmApproche = 10.9, kmTrajet = 12.1),
            Bareme(),
        )
        assertEquals(Decision.LAISSE, v.decision)
        assertTrue(v.resume.contains("approche", ignoreCase = true))
        assertTrue(v.euroHeure!! < 20.0, "euro/heure inattendu : ${v.euroHeure}")
    }

    @Test
    fun `la commission ampute la recette`() {
        val course = Course(prix = 30.0, minutesApproche = 4.0, kmApproche = 2.0,
            minutesTrajet = 20.0, kmTrajet = 9.0)
        val brut = Arbitre.arbitrer(course, nu)
        val net = Arbitre.arbitrer(course, nu.copy(commission = 0.25))
        assertEquals(7.5, brut.revenuNet!! - net.revenuNet!!, 0.001)
    }

    @Test
    fun `part de temps non payee signalee`() {
        val v = Arbitre.arbitrer(
            Course(prix = 14.0, minutesApproche = 11.0, kmApproche = 4.0,
                minutesTrajet = 9.0, kmTrajet = 3.5),
            Bareme(approcheMaxMinutes = 15.0, prudenceTrafic = false),
        )
        assertNotNull(v.partMorte)
        assertTrue(v.partMorte!! > 0.45)
        assertTrue(v.alertes.any { it.contains("n'est pas payé") })
    }

    @Test
    fun `chaine complete depuis le texte de notification`() {
        val v = Arbitre.arbitrer(
            "UberX · 15,32 €\n4 min (1,3 km) de vous\n22 min (9,8 km) de trajet",
            "Uber",
            Bareme(objectifHeure = 25.0),
        )
        assertNotNull(v.euroHeure)
        assertTrue(v.euroHeure!! in 10.0..35.0, "euro/heure improbable : ${v.euroHeure}")
        assertTrue(v.resume.isNotEmpty())
    }

    @Test
    fun `mise en forme francaise`() {
        assertEquals("12,50", fmt2(12.5))
        assertEquals("3,2", fmt1(3.24))
        assertEquals("25", fmt0(24.6))
        assertEquals("—", fmt1(null))
    }
}

class CompletionTest {

    @Test
    fun `une lecture partielle est completee par la precedente`() {
        // L'écran a d'abord été lu alors que seul le prix était attaché à
        // l'arbre des vues ; les lignes du trajet sont arrivées après.
        val prixSeul = Course(prix = 12.51, texteBrut = "UberX 12,51 €")
        val trajetSeul = Course(
            minutesApproche = 9.0, kmApproche = 2.5, kmTrajet = 12.6,
            texteBrut = "9 min (à 2.5 km) … Course de 12.6 km",
        )
        val c = trajetSeul.completer(prixSeul)
        assertEquals(12.51, c.prix)
        assertEquals(2.5, c.kmApproche)
        assertEquals(12.6, c.kmTrajet)
        assertTrue(c.exploitable)
        assertTrue(c.texteBrut.contains("Course de"))
    }

    @Test
    fun `deux prix differents ne se melangent pas`() {
        val offreA = Course(prix = 12.51, kmTrajet = 12.6)
        val offreB = Course(prix = 30.00)
        assertEquals(30.00, offreB.completer(offreA).prix)
        assertNull(offreB.completer(offreA).kmTrajet)
    }

    @Test
    fun `sans precedente, rien ne change`() {
        val c = Course(prix = 9.0)
        assertEquals(c, c.completer(null))
    }

    @Test
    fun `le meme prix autorise la fusion`() {
        val a = Course(prix = 12.51, kmApproche = 2.5)
        val b = Course(prix = 12.51, kmTrajet = 12.6)
        val f = b.completer(a)
        assertEquals(2.5, f.kmApproche)
        assertEquals(12.6, f.kmTrajet)
    }
}
