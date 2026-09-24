package fr.ningbus.arbitre.moteur

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Le relevé du 24 septembre au complet : dix-neuf courses planifiées Bolt,
 * toutes entre 10:30 et 11:00.
 *
 * C'est le corpus qui a fait naître le comparateur, et il reste ici pour
 * qu'aucune évolution du barème ou du classement ne vienne le contredire en
 * silence. Les chiffres sont ceux des captures, au centime et au dixième de
 * kilomètre ; seules les adresses sont réduites à leur commune, comme le
 * comparateur le fait lui-même.
 *
 * Ce que ce relevé établit, et qu'aucun essai synthétique n'aurait montré :
 *
 *  - dix-neuf offres pour une seule demi-heure, donc **une seule sera faite** ;
 *  - de 0,80 à 3,06 €/km, soit un facteur 3,8 entre le meilleur et le pire
 *    choix du même écran ;
 *  - aucune ne supporte dix kilomètres d'approche, et trois ne tiennent pas
 *    même client devant la porte.
 */
class Releve24Test {

    private fun course(
        prix: Double,
        peage: Double,
        km: Double,
        categorie: String,
        creneau: String,
        depart: String,
        arrivee: String,
    ) = Planifiee(prix, peage, km, categorie, creneau, depart, arrivee)

    private val releve = listOf(
        course(19.00, 0.0, 6.2, "Bolt", "10:30", "Paris", "Paris"),
        course(19.00, 0.0, 20.2, "Bolt", "10:30", "Noisy-le-Grand", "Bagnolet"),
        course(53.43, 13.6, 40.1, "Green", "10:33", "Paray-Vieille-Poste", "Courbevoie"),
        course(22.80, 0.0, 12.3, "Comfort", "10:35", "Argenteuil", "Rueil-Malmaison"),
        course(24.78, 0.0, 31.0, "Bolt", "10:35", "Mormant", "Lieusaint"),
        course(28.96, 0.0, 30.4, "Comfort", "10:35", "Éragny", "Nanterre"),
        course(32.50, 0.0, 28.7, "Bolt", "10:35", "Paris", "Paray-Vieille-Poste"),
        course(18.92, 0.0, 20.2, "Green", "10:40", "Saint-Ouen", "Paris"),
        course(22.80, 0.0, 16.8, "Comfort", "10:40", "Le Vésinet", "Paris"),
        course(19.00, 0.0, 17.9, "Bolt", "10:40", "Vitry-sur-Seine", "Paris"),
        course(17.40, 0.0, 7.9, "Green", "10:40", "Bagnolet", "Paris"),
        course(23.97, 0.0, 26.7, "Green", "10:40", "Évry", "Paray-Vieille-Poste"),
        course(23.39, 0.0, 27.4, "Bolt", "10:40", "Asnières-sur-Seine", "Le Mesnil-Amelot"),
        course(21.47, 0.0, 25.2, "Bolt", "10:40", "Saint-Ouen", "Mauregard"),
        course(26.00, 0.0, 26.9, "Bolt", "10:40", "Paris", "Tremblay-en-France"),
        course(21.20, 0.0, 10.9, "Bolt", "10:45", "Bouray-sur-Juine", "Étréchy"),
        course(19.68, 0.0, 17.5, "Comfort", "10:45", "Fresnes", "Paris"),
        course(19.00, 0.0, 14.8, "Bolt", "10:55", "Saint-Denis", "Paris"),
        course(19.00, 0.0, 20.6, "Bolt", "10:55", "La Celle-Saint-Cloud", "Paris"),
    )

    private val comparatif = Planifiees.comparer(releve, Bareme())

    /**
     * La propriété structurelle du tarif planifié : il est calculé comme si la
     * voiture était déjà devant la porte.
     *
     * C'est la seule chose de tout ce relevé qui se généralise, et c'est elle
     * qui justifie de classer sur le budget d'approche plutôt que sur le prix.
     */
    @Test
    fun `aucune course ne supporte dix kilometres d'approche`() {
        assertEquals(19, comparatif.rangs.size)
        assertTrue(
            comparatif.budgetMaximal < 10.0,
            "la plus tolérante en supporte ${comparatif.budgetMaximal} km",
        )
    }

    /** Trois offres ne tiennent pas, même sans un mètre à vide. */
    @Test
    fun `trois courses ne tiennent pas meme sur place`() {
        val perdues = comparatif.rangs.filterNot { it.tenable }
        assertEquals(3, perdues.size, perdues.map { it.course.depart }.toString())
        assertTrue(perdues.all { it.course.km > 20.0 }, "toutes longues et mal payées")
    }

    /**
     * Le piège de la plus grosse annonce.
     *
     * 53,43 € est le premier chiffre que la main attrape sur cet écran. Péage
     * déduit, la course tombe à 0,99 €/km — la médiane exacte de sa propre
     * liste — et se retrouve en cinquième position.
     */
    @Test
    fun `la plus grosse annonce n'est pas la meilleure course`() {
        val rangs = comparatif.rangs
        val grosse = rangs.first { it.course.peage > 0.0 }
        assertEquals(53.43, grosse.course.prix)
        assertEquals(39.83, grosse.course.prixNet, 0.01)
        assertTrue(
            rangs.indexOf(grosse) >= 4,
            "classée ${rangs.indexOf(grosse) + 1}e, et non première",
        )
        assertEquals(0.99, comparatif.medianeEuroKm, 0.01)
    }

    /**
     * Le plancher tarifaire, et le réflexe le plus rentable de tout l'écran.
     *
     * Cinq courses au prix exactement identique, de 6,2 à 20,6 km : même
     * argent, trois fois le travail. La plus courte est en tête du classement,
     * la plus longue au fond.
     */
    @Test
    fun `cinq courses a dix-neuf euros couvrent de six a vingt kilometres`() {
        val a19 = comparatif.rangs.filter { it.course.prix == 19.0 }
        assertEquals(5, a19.size)
        assertTrue(a19.all { it.auPlancher })

        val courte = a19.minByOrNull { it.course.km }!!
        val longue = a19.maxByOrNull { it.course.km }!!
        assertEquals(6.2, courte.course.km)
        assertEquals(20.6, longue.course.km)
        assertEquals(comparatif.rangs.first(), courte, "la plus courte mène le classement")
        assertTrue(longue.budgetApprocheKm == 0.0, "et la plus longue ne tient pas")
    }

    /**
     * Les courses vers un aéroport sont mal payées — mais pas toutes, et c'est
     * la nuance qui compte.
     *
     * Elles sont cinq, pas quatre, et l'essai l'a corrigé : Paris 18e → Orly
     * en fait partie, à 1,13 €/km et neuf kilomètres de budget d'approche,
     * troisième du classement. Généraliser « l'aéroport paie mal » aurait fait
     * refuser la meilleure grosse course de l'écran.
     *
     * Ce qui tient, en revanche : quatre des cinq sont sous la médiane de leur
     * propre liste. Bolt les tarife au plancher parce que les chauffeurs les
     * veulent pour la file au retour — et cette file n'est pas dans le prix.
     */
    @Test
    fun `les courses vers un aeroport sont majoritairement sous la mediane`() {
        val aeroports = listOf(
            "Paray-Vieille-Poste", "Le Mesnil-Amelot", "Mauregard", "Tremblay-en-France",
        )
        val versAeroport = comparatif.rangs.filter { it.course.arrivee in aeroports }
        assertEquals(5, versAeroport.size)

        val sousMediane = versAeroport.count { it.course.euroParKm < comparatif.medianeEuroKm }
        assertEquals(4, sousMediane, versAeroport.map { it.course.euroParKm }.toString())

        val bonne = versAeroport.maxByOrNull { it.course.euroParKm }!!
        assertTrue(
            comparatif.rangs.indexOf(bonne) < 5,
            "celle qui paie bien reste en tête : ${comparatif.rangs.indexOf(bonne) + 1}e",
        )
    }
}
