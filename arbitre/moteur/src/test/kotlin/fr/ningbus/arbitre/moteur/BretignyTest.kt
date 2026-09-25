package fr.ningbus.arbitre.moteur

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Brétigny-sur-Orge → Sainte-Geneviève-des-Bois, relevée en direct sur Bolt.
 *
 * La première carte live lue de bout en bout depuis les corrections du 21/09 :
 * quatre champs sur quatre, aucune approche inventée, 100 % de confiance. Elle
 * reste ici pour deux raisons.
 *
 * La première est de verrouiller une lecture juste. La seconde est plus
 * instructive : **c'est le contre-exemple qui interdit de laisser l'euro par
 * kilomètre décider seul.** Il affiche 1,02 €/km — au-dessus de la médiane
 * d'un relevé de dix-neuf courses planifiées — et la course paie 18,7 €/h
 * contre 25 visés. Le refus ne vient pas de la distance mais du temps :
 * 47 % de la durée mobilisée n'est pas payée.
 */
class BretignyTest {

    private val course = Course(
        plateforme = "Bolt",
        prix = 9.80,
        minutesApproche = 4.0,
        kmApproche = 1.4,
        minutesTrajet = 12.0,
        kmTrajet = 8.2,
    )

    private val verdict = Arbitre.arbitrer(course, Bareme())

    @Test
    fun `la carte est lue entierement`() {
        assertEquals(100, verdict.confiance.pourcent, verdict.confiance.lectures.toString())
        assertEquals(1.02, verdict.euroKmRoule!!, 0.005, "9,80 € ÷ (1,4 + 8,2) km")
    }

    /**
     * Un euro par kilomètre honorable, et un refus net. C'est tout l'argument
     * contre le plancher d'euro/kilomètre comme critère unique.
     */
    @Test
    fun `un bon euro par kilometre ne sauve pas une course trop courte`() {
        assertTrue(verdict.euroKmRoule!! > 1.0, "le chiffre affiché a l'air correct")
        assertEquals(Decision.LAISSE, verdict.decision)
        assertTrue(verdict.euroHeure!! < 20.0, "et pourtant : ${verdict.euroHeure} €/h")
        assertTrue(verdict.ratio!! < 0.85, "pas même dans la bande limite")
    }

    /**
     * Le vrai coupable, et il est temporel : douze minutes de course, et
     * presque autant à côté — approche majorée par les bouchons mesurés,
     * attente sur place, retour à vide supposé.
     */
    @Test
    fun `c'est le temps mort qui condamne la course`() {
        assertTrue(verdict.partMorte!! > 0.45, "part morte : ${verdict.partMorte}")
        assertEquals(22.7, verdict.minutesTotal!!, 0.1)
        assertEquals(10.7, verdict.minutesAVide!!, 0.1)
    }

    /**
     * Le défaut que cette capture a révélé : le verdict ne disait pas
     * pourquoi.
     *
     * Le moteur alerte dès 45 % de temps non payé, mais son motif attendait
     * 50 % — deux seuils pour la même idée, et le cas le plus fréquent de
     * tous, « le prix ne couvre pas le temps », tombait dans l'intervalle. Un
     * LAISSE muet à 1,02 €/km est exactement celui qu'on croit injuste.
     */
    @Test
    fun `le verdict nomme sa contrainte`() {
        assertNotNull(verdict.motif, "un LAISSE sans motif est un LAISSE incompris")
        assertTrue(
            verdict.motif!!.contains("temps") || verdict.motif!!.contains("payé"),
            verdict.motif!!,
        )
    }

    /** Ce qu'il aurait fallu, sur cette course exacte. */
    @Test
    fun `deux euros de plus en auraient fait une course limite`() {
        assertEquals(
            Decision.LIMITE,
            Arbitre.arbitrer(course.copy(prix = 12.0), Bareme()).decision,
        )
        assertEquals(
            Decision.PRENDS,
            Arbitre.arbitrer(course.copy(prix = 14.0), Bareme()).decision,
        )
    }
}
