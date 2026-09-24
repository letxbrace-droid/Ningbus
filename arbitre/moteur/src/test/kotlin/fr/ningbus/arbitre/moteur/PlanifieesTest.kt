package fr.ningbus.arbitre.moteur

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Le comparateur de courses planifiées, éprouvé sur le relevé du 24/09.
 *
 * Dix-neuf offres Bolt, toutes entre 10:30 et 11:00 — donc une seule serait
 * faite — et un écart de 0,80 à 3,06 €/km entre le pire et le meilleur choix.
 * C'est cet écran-là que l'application refusait d'arbitrer depuis la 2.0, à
 * juste titre : ses chiffres appartiennent à plusieurs courses. Le découper
 * est la seule façon d'en dire quelque chose de vrai.
 */
class PlanifieesTest {

    /** Trois offres consécutives, telles que l'arbre d'accessibilité les rend. */
    private val liste = """
        Demandes de courses planifiées
        Demandes
        Accepté
        19 € • Aujourd'hui, 10:30–10:35
        Bolt
        6.2km
        Carte Google
        Repère sur la carte
        Près de Place Du Président Mithouard, 7e Arrondissement, Paris 75007, France
        Près de Rue De Maubeuge, 10e Arrondissement, Paris 75010, France
        53,43 € • 13,6 € péage • Aujourd'hui, 10:33–10:38
        Green
        40.1km
        Près de Dépose Minute, Paray-Vieille-Poste, France
        Près de Place De l'Iris, Courbevoie 92400, France
        19 € • Aujourd'hui, 10:55–11:00
        Bolt
        20.6km
        Près de Avenue De La Jonchere, La Celle-Saint-Cloud 78170, France
        Près de Avenue Du Maine, 15e Arrondissement, Paris 75015, France
        Voir toutes les demandes
    """.trimIndent()

    private val bareme = Bareme()

    @Test
    fun `une liste se decoupe en offres distinctes`() {
        val offres = Planifiees.decouper(liste)
        assertEquals(3, offres.size)

        assertEquals(19.0, offres[0].prix)
        assertEquals(6.2, offres[0].km)
        assertEquals("Bolt", offres[0].categorie)
        assertEquals("Aujourd'hui, 10:30–10:35", offres[0].creneau)
        assertEquals("Paris", offres[0].depart)
        assertEquals("Paris", offres[0].arrivee)
    }

    /**
     * Le piège de la plus grosse annonce. 53,43 € est le premier chiffre que
     * la main attrape ; 13,6 € de péage sortent de la poche du chauffeur, et
     * la course retombe sous la médiane de sa propre liste.
     */
    @Test
    fun `le peage est retire du prix avant tout classement`() {
        val c = Planifiees.decouper(liste).first { it.peage > 0.0 }
        assertEquals(53.43, c.prix)
        assertEquals(13.6, c.peage)
        assertEquals(39.83, c.prixNet, 0.01)
        assertEquals(0.99, c.euroParKm, 0.01, "la plus grosse annonce est une course moyenne")
    }

    /**
     * Le plancher tarifaire, et le réflexe qu'il commande : devant deux
     * courses au même prix, prendre la plus courte.
     */
    @Test
    fun `deux courses au meme prix sont signalees comme un plancher`() {
        val comparatif = Planifiees.comparer(liste, bareme)
        val a19 = comparatif.rangs.filter { it.course.prix == 19.0 }
        assertEquals(2, a19.size)
        assertTrue(a19.all { it.auPlancher }, "19 € pour 6,2 km et pour 20,6 km")

        val courte = a19.first { it.course.km < 10.0 }
        val longue = a19.first { it.course.km > 10.0 }
        assertTrue(
            courte.budgetApprocheKm > longue.budgetApprocheKm,
            "même argent, trois fois le travail",
        )
    }

    /**
     * Le chiffre que la liste ne montre pas, et qui décide seul : une course
     * planifiée est tarifée comme si le chauffeur était déjà devant la porte.
     */
    @Test
    fun `le budget d'approche borne ce que la course supporte`() {
        val courte = Planifiees.decouper(liste).first { it.km < 10.0 }
        val budget = Planifiees.budgetApproche(courte, bareme)

        assertTrue(budget in 7.0..11.0, "budget calculé : $budget km")
        assertTrue(
            Planifiees.euroHeure(courte, bareme, budget + 3.0) < bareme.objectifHeure,
            "au-delà du budget, la course passe sous l'objectif",
        )
        assertTrue(Planifiees.euroHeure(courte, bareme, budget - 3.0) >= bareme.objectifHeure)
    }

    /**
     * Mormant → Lieusaint, relevée le 24/09 : 24,78 € pour 31 km, soit
     * 0,80 €/km, le fond du panier. Client devant la porte, elle tient encore
     * — 26 €/h — et c'est précisément ce qui la rend trompeuse : un seul
     * kilomètre à vide la fait tomber.
     */
    @Test
    fun `une course au fond du panier ne supporte pas un kilometre a vide`() {
        val perdante = Planifiee(
            prix = 24.78, peage = 0.0, km = 31.0, categorie = "Bolt",
            creneau = "Aujourd'hui, 10:35–10:40",
            depart = "Mormant", arrivee = "Lieusaint",
        )
        val rang = Planifiees.comparer(listOf(perdante), bareme).rangs.single()
        assertTrue(rang.euroHeureSurPlace >= bareme.objectifHeure, "sur place elle passe encore")
        assertTrue(
            rang.budgetApprocheKm < 1.0,
            "budget calculé : ${rang.budgetApprocheKm} km",
        )
    }

    /**
     * Le défaut que cet essai a trouvé, et qui justifie une courbe d'approche
     * distincte des paliers du moteur : aux ruptures, trois kilomètres de plus
     * prenaient moins de temps que trois de moins, et le budget d'approche
     * n'avait plus de sens.
     */
    @Test
    fun `le temps d'approche croit avec la distance, sans rupture`() {
        var precedent = 0.0
        var km = 0.5
        while (km <= 60.0) {
            val minutes = Planifiees.minutesApproche(km)
            assertTrue(minutes > precedent, "$km km : $minutes min après $precedent")
            precedent = minutes
            km += 0.5
        }
    }

    /** Le classement met en tête ce qui supporte le plus de route à vide. */
    @Test
    fun `le classement suit le budget d'approche`() {
        val rangs = Planifiees.comparer(liste, bareme).rangs
        assertEquals(6.2, rangs.first().course.km, "la courte à 19 € passe devant")
        assertTrue(
            rangs.zipWithNext().all { (a, b) -> a.budgetApprocheKm >= b.budgetApprocheKm },
            "classement décroissant",
        )
    }

    /** Une liste qui défile repasse les mêmes offres : elles ne comptent qu'une fois. */
    @Test
    fun `les offres vues deux fois ne sont comptees qu'une fois`() {
        assertEquals(3, Planifiees.decouper(liste + "\n" + liste).size)
    }

    /** Une carte d'offre unique n'est pas une liste, et ne doit pas le devenir. */
    @Test
    fun `une carte seule n'est pas une liste`() {
        val carte = """
            Demande
            19 € • Aujourd'hui, 08:30
            Bolt
            13.6km
            Près de Rue De La Briqueterie, Saclay 91400, France
            Accepter
        """.trimIndent()
        assertFalse(Planifiees.estUneListe(carte))
        assertEquals(1, Planifiees.decouper(carte).size)
    }

    /** Une adresse se lit au volant, ou elle ne se lit pas. */
    @Test
    fun `une adresse est reduite a sa commune`() {
        assertEquals("Paris", Planifiees.abreger("Rue De Maubeuge, 10e Arrondissement, Paris 75010, France"))
        assertEquals("Saint-Denis", Planifiees.abreger("Rue Du Landy, Saint-Denis 93210, France"))
        assertEquals("Paray-Vieille-Poste", Planifiees.abreger("Dépose Minute, Paray-Vieille-Poste, France"))
    }

    /** La médiane situe une course dans sa propre liste, sans seuil inventé. */
    @Test
    fun `la mediane et le peage masque sont rendus`() {
        val comparatif = Planifiees.comparer(liste, bareme)
        assertEquals(0.99, comparatif.medianeEuroKm, 0.02)
        assertEquals(13.6, comparatif.peageMasque, 0.01)
    }
}
