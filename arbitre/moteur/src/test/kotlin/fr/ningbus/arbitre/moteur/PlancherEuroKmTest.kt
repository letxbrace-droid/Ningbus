package fr.ningbus.arbitre.moteur

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Le plancher d'euro par kilomètre, et les trois postes de coût.
 *
 * Le plancher est arrivé par une maquette qui le posait à la place de
 * l'objectif horaire. Il s'y ajoute plutôt qu'il ne le remplace, et ces essais
 * verrouillent ce que « s'ajouter » veut dire : il refuse, il n'autorise
 * jamais, et il ne fait rien du tout tant qu'il n'est pas armé.
 */
class PlancherEuroKmTest {

    /** Une course confortable : 22 € pour 9 km, 1,5 km d'approche, 18 min. */
    private val bonne = Course(
        plateforme = "Bolt",
        prix = 22.0,
        minutesApproche = 4.0,
        kmApproche = 1.5,
        minutesTrajet = 18.0,
        kmTrajet = 9.0,
    )

    // --- Le plancher ---------------------------------------------------------

    /**
     * La garantie qui compte à la mise à jour : sans réglage, rien ne bouge.
     *
     * Un plancher livré armé aurait changé en silence les verdicts de
     * quelqu'un qui n'a rien demandé — exactement ce qu'on reproche à une
     * application qui décide à votre place.
     */
    @Test
    fun `desactive d'origine, il ne change aucun verdict`() {
        assertEquals(0.0, Bareme().plancherEuroKm)
        assertEquals(
            Arbitre.arbitrer(bonne, Bareme()).decision,
            Arbitre.arbitrer(bonne, Bareme(plancherEuroKm = 0.0)).decision,
        )
    }

    @Test
    fun `arme, il refuse une course qui passait`() {
        val sans = Arbitre.arbitrer(bonne, Bareme())
        assertEquals(Decision.PRENDS, sans.decision)

        val euroKm = sans.euroKmRoule
        assertNotNull(euroKm)
        assertEquals(22.0 / 10.5, euroKm, 0.001, "prix ÷ (approche + course)")

        val avec = Arbitre.arbitrer(bonne, Bareme(plancherEuroKm = euroKm + 0.5))
        assertEquals(Decision.LAISSE, avec.decision)
        assertTrue(avec.resume.contains("plancher"), avec.resume)
    }

    /**
     * Le sens unique, et c'est tout l'intérêt d'un veto : une course sous
     * l'objectif horaire le reste, même avec un euro/kilomètre superbe.
     *
     * 12 € pour 2 km de course après 9 km d'approche font 1,09 €/km — au-dessus
     * d'un plancher à 1,00 € — et pourtant vingt minutes de route pour une
     * course de deux kilomètres ne valent pas l'objectif.
     */
    @Test
    fun `un bon euro par kilometre n'autorise jamais rien`() {
        val exilee = Course(
            plateforme = "Bolt",
            prix = 12.0,
            minutesApproche = 20.0,
            kmApproche = 9.0,
            minutesTrajet = 6.0,
            kmTrajet = 2.0,
        )
        val sans = Arbitre.arbitrer(exilee, Bareme())
        val avec = Arbitre.arbitrer(exilee, Bareme(plancherEuroKm = 1.0))

        assertTrue(sans.decision != Decision.PRENDS, "refusée avant le plancher")
        assertEquals(sans.decision, avec.decision, "et refusée de la même façon après")
    }

    /**
     * Ce que le plancher coûte, mesuré sur une vraie course du relevé du
     * 24 septembre : Paris 18e → Orly, 32,50 € pour 28,7 km.
     *
     * Troisième du classement du comparateur, 43 €/h client devant la porte —
     * et un plancher à 1,70 €/km la jette, parce qu'une course longue étale
     * son temps mort sur plus de kilomètres payés. C'est la raison pour
     * laquelle l'euro/kilomètre ne peut pas décider seul.
     */
    @Test
    fun `un plancher trop haut jette une bonne course longue`() {
        val parisOrly = Course(
            plateforme = "Bolt",
            prix = 32.50,
            minutesApproche = 6.0,
            kmApproche = 3.0,
            minutesTrajet = 35.0,
            kmTrajet = 28.7,
        )
        assertTrue(Arbitre.arbitrer(parisOrly, Bareme()).decision != Decision.LAISSE)

        val avecPlancher = Arbitre.arbitrer(parisOrly, Bareme(plancherEuroKm = 1.70))
        assertEquals(Decision.LAISSE, avecPlancher.decision)
        assertTrue(avecPlancher.euroKmRoule!! < 1.70)
    }

    /** Les deux euros par kilomètre ne se confondent pas, et l'écart est grand. */
    @Test
    fun `l'euro par kilometre roule et l'euro par kilometre net sont distincts`() {
        val v = Arbitre.arbitrer(bonne, Bareme())
        assertNotNull(v.euroKmRoule)
        assertNotNull(v.euroKm)
        assertTrue(
            v.euroKmRoule!! > v.euroKm!!,
            "roulé ${v.euroKmRoule} · net ${v.euroKm}",
        )
    }

    // --- Les trois postes de coût --------------------------------------------

    @Test
    fun `les trois postes totalisent le cout de roulage`() {
        val b = Bareme()
        assertEquals(0.22, b.coutKm, 0.0001, "la valeur d'origine est conservée")
        assertEquals(b.coutCarburant + b.coutUsure + b.coutFixes, b.coutKm, 0.0001)
    }

    @Test
    fun `le detail des couts se recompose`() {
        val v = Arbitre.arbitrer(bonne, Bareme())
        val detail = v.coutCarburant!! + v.coutUsure!! + v.coutFixes!!
        assertEquals(v.kmTotal!! * Bareme().coutKm, detail, 0.001)
        assertEquals(v.revenuNet!!, 22.0 - detail, 0.001, "prix moins les trois postes")
    }

    /** Relever un seul poste suffit à durcir le verdict, sans toucher aux autres. */
    @Test
    fun `relever un poste durcit le verdict`() {
        val cher = Bareme(coutCarburant = 0.30)
        assertEquals(0.39, cher.coutKm, 0.0001)
        assertTrue(
            Arbitre.arbitrer(bonne, cher).revenuNet!! <
                Arbitre.arbitrer(bonne, Bareme()).revenuNet!!,
        )
    }
}
