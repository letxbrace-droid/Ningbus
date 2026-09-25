package fr.ningbus.arbitre.moteur

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La journée du 25/09, relevée sur l'historique Uber : sept courses entre
 * 12:49 et 18:54 pour 121,33 €.
 *
 * **3 h 44 de vide pour 2 h 20 payées**, trou médian de vingt-neuf minutes.
 * Trois façons de lire le même argent : 51,9 €/h si l'on ne compte que le
 * temps facturé, 19,9 €/h brut sur l'amplitude réelle, environ 15 €/h net une
 * fois le roulage déduit. Facteur trois et demi entre la première et la
 * dernière.
 *
 * Ce fichier verrouille le constat qui en sort, et qui dément la conclusion
 * que tout le monde tire d'abord — « il faut enchaîner les petites courses
 * ultra rentables ». Elle n'est vraie que là où l'on peut réellement
 * enchaîner.
 */
class AttenteReelleTest {

    /** Le trou médian observé entre deux offres, ce jour-là. */
    private val attente = 29.0

    /** 9,00 € pour 3,10 km en 7 min 42, approche de 4 km. Breuillet, 16:00. */
    private val courte = Course(
        plateforme = "Uber",
        prix = 9.00,
        kmApproche = 4.0,
        minutesApproche = 11.0,
        kmTrajet = 3.10,
        minutesTrajet = 7.7,
    )

    /** 31,58 € pour 31,22 km en 42 min 6. Dourdan vers Orsay, 17:34. */
    private val longue = Course(
        plateforme = "Uber",
        prix = 31.58,
        kmApproche = 4.0,
        minutesApproche = 11.0,
        kmTrajet = 31.22,
        minutesTrajet = 42.1,
    )

    @Test
    fun `sans attente mesuree, rien ne change`() {
        assertNull(Arbitre.arbitrer(courte).euroHeureAmorti)
        assertEquals(
            Arbitre.arbitrer(courte).euroHeure!!,
            Arbitre.arbitrer(courte, Bareme(minutesEntreOffres = null)).euroHeure!!,
            0.001,
        )
    }

    /**
     * Le cœur du sujet, et le résultat le plus contre-intuitif de tout le
     * moteur.
     *
     * Sur son temps facturé, la course courte écrase la longue — 70 €/h contre
     * 45. Une fois la même attente comptée pour les deux, **le classement
     * s'inverse**. Le temps mort ne dépend pas de la longueur de la course :
     * une course de huit minutes l'amortit sur cinq fois moins de temps payé.
     */
    @Test
    fun `l'attente reelle inverse le classement`() {
        val bareme = Bareme(minutesEntreOffres = attente)

        val courteFacturee = courte.prix!! / (courte.minutesTrajet!! / 60.0)
        val longueFacturee = longue.prix!! / (longue.minutesTrajet!! / 60.0)
        assertTrue(
            courteFacturee > longueFacturee,
            "sur le temps facturé la courte doit gagner : $courteFacturee vs $longueFacturee",
        )

        val courteAmortie = assertNotNull(Arbitre.arbitrer(courte, bareme).euroHeureAmorti)
        val longueAmortie = assertNotNull(Arbitre.arbitrer(longue, bareme).euroHeureAmorti)
        assertTrue(
            longueAmortie > courteAmortie,
            "une fois l'attente comptée, la longue doit gagner : " +
                "$longueAmortie vs $courteAmortie",
        )
    }

    /**
     * Et le basculement dépend du temps fixe, pas de la course.
     *
     * Cet essai a d'abord échoué, et son échec corrige l'analyse qui l'avait
     * fait écrire. Supprimer l'attente ne suffit pas à faire gagner la course
     * courte : **l'approche est un coût fixe exactement comme l'attente**.
     * Onze minutes pour aller chercher le client pèsent sur sept minutes de
     * trajet payé comme elles pèseraient sur quarante — c'est-à-dire cinq fois
     * plus lourd. Avec une approche de 4 km, la longue reste devant même sans
     * la moindre attente.
     *
     * La condition n'est donc pas « pas d'attente » mais **« pas de temps
     * fixe du tout »** : client à deux minutes, et offre suivante
     * immédiatement. C'est ce que veut dire un secteur dense — une gare, une
     * sortie de boîte, un aéroport — et c'est là, et seulement là, que la
     * stratégie des petites courses écrase tout. Il y faut les deux
     * conditions, pas une.
     */
    @Test
    fun `sans temps fixe, la course courte reprend l'avantage`() {
        val serre = Bareme(minutesEntreOffres = 1.0)
        val devantLaPorte = { c: Course -> c.copy(kmApproche = 0.5, minutesApproche = 2.0) }

        val courteAmortie =
            assertNotNull(Arbitre.arbitrer(devantLaPorte(courte), serre).euroHeureAmorti)
        val longueAmortie =
            assertNotNull(Arbitre.arbitrer(devantLaPorte(longue), serre).euroHeureAmorti)
        assertTrue(
            courteAmortie > longueAmortie,
            "client devant la porte et offre immédiate : la courte doit reprendre " +
                "la tête — $courteAmortie vs $longueAmortie",
        )
    }

    /**
     * Et la même course courte, avec son approche de 4 km, reste derrière
     * même sans attente. C'est la moitié du constat qu'on oublie toujours.
     */
    @Test
    fun `l'approche seule suffit a condamner la course courte`() {
        val serre = Bareme(minutesEntreOffres = 1.0)
        val courteAmortie = assertNotNull(Arbitre.arbitrer(courte, serre).euroHeureAmorti)
        val longueAmortie = assertNotNull(Arbitre.arbitrer(longue, serre).euroHeureAmorti)
        assertTrue(
            longueAmortie > courteAmortie,
            "4 km d'approche condamnent la courte même sans attente : " +
                "$courteAmortie vs $longueAmortie",
        )
    }

    /**
     * Le chiffre ne décide rien. C'est une règle, pas un hasard : baisser
     * l'objectif quand les offres se raréfient ferait de l'outil une machine
     * à justifier les mauvaises courses, ce qu'il existe pour empêcher.
     */
    @Test
    fun `le verdict ne depend pas de l'attente mesuree`() {
        val sans = Arbitre.arbitrer(longue)
        val avec = Arbitre.arbitrer(longue, Bareme(minutesEntreOffres = 90.0))
        assertEquals(sans.decision, avec.decision)
        assertEquals(sans.euroHeure!!, avec.euroHeure!!, 0.001)
    }

    /** Plus l'attente est longue, moins la course rapporte. Sans exception. */
    @Test
    fun `l'euro par heure amorti decroit avec l'attente`() {
        val valeurs = listOf(0.0, 10.0, 29.0, 60.0).map {
            assertNotNull(Arbitre.arbitrer(longue, Bareme(minutesEntreOffres = it)).euroHeureAmorti)
        }
        assertEquals(valeurs, valeurs.sortedDescending(), "décroissance attendue : $valeurs")
    }
}

/**
 * La médiane des écarts, telle que la densité d'offres l'emploie.
 *
 * Même outil que la calibration des durées, et pour la même raison : une pause
 * déjeuner produit un écart de deux heures qui n'apprend rien sur le secteur,
 * et déplacerait une moyenne sans déplacer une médiane.
 */
class MedianeTest {

    @Test
    fun `une pause ne deplace pas la mediane`() {
        val normal = listOf(21.0, 24.0, 29.0, 32.0, 38.0)
        val avecPause = normal + 180.0
        assertEquals(29.0, Calibrage.mediane(normal), 0.001)
        assertTrue(
            kotlin.math.abs(Calibrage.mediane(avecPause) - 29.0) < 2.0,
            "obtenu ${Calibrage.mediane(avecPause)}",
        )
    }

    @Test
    fun `nombre pair, moyenne des deux valeurs centrales`() {
        assertEquals(25.0, Calibrage.mediane(listOf(20.0, 30.0, 10.0, 40.0)), 0.001)
    }
}
