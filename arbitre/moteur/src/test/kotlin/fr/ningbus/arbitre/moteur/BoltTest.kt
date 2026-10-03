package fr.ningbus.arbitre.moteur

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Les cartes Bolt relevées le 21/09, et les deux façons dont elles mentaient.
 *
 * Elles sont le cas le plus instructif du journal, parce que c'est celui qui
 * paraissait sain. Les verdicts sortis de ces écrans s'annonçaient à **100 %
 * de confiance** — la note la plus haute de toute la journée — et ils étaient
 * faux de bout en bout.
 *
 * La leçon dépasse Bolt : la confiance mesurait la **complétude** de la
 * lecture, pas sa **justesse**. Un champ rempli par erreur la faisait monter,
 * là où un champ laissé vide l'aurait fait descendre. C'est exactement
 * l'inverse de ce qu'on veut : une donnée manquante se voit, une donnée
 * inventée non.
 */
class BoltTest {

    /**
     * Carte d'une course planifiée, telle que le journal l'a conservée.
     *
     * Deux pièges y cohabitent :
     *
     *  - `13.6km` apparaît **deux fois** — seul, puis suivi de la durée. La
     *    première occurrence devenait une approche de 13,6 km, alors que la
     *    carte n'annonce aucune approche. Treize kilomètres à vide inventés ;
     *  - `5 min` ne sont pas une durée d'approche mais les minutes d'attente
     *    offertes par le tarif. Lues comme approche, elles complétaient le
     *    dernier champ manquant et portaient la confiance à 100 %.
     */
    private val planifiee = """
        Demande
        19 € • Aujourd'hui, 08:30
        Bolt
        13.6km
        Carte Google
        Repère sur la carte
        Près de Rue De La Briqueterie, Saclay 91400, France
        Près de Rue De La Mutualité, Antony 92160, France
        Les lieux précis seront affichés avant de commencer la course planifiée
        13.6km • 21 min
        Temps d'attente supplémentaire
        5 min gratuites incluses dans le tarif, 0,78 €/min après 5 min
        Accepter
    """.trimIndent()

    @Test
    fun `l'approche n'est plus inventee a partir d'une distance repetee`() {
        val c = Analyseur.analyser(planifiee, "Bolt")

        assertEquals(19.0, c.prix)
        assertEquals(13.6, c.kmTrajet)
        assertEquals(21.0, c.minutesTrajet)
        assertNull(c.kmApproche, "la carte n'annonce aucune approche")
    }

    @Test
    fun `les minutes d'attente offertes ne sont pas une approche`() {
        val c = Analyseur.analyser(planifiee, "Bolt")
        assertNull(c.minutesApproche, "« 5 min gratuites incluses dans le tarif »")
    }

    /**
     * Et la conséquence qui compte : une approche absente ne se présente plus
     * avec la même assurance qu'une lecture complète.
     */
    @Test
    fun `une approche absente fait tomber la confiance`() {
        val c = Analyseur.analyser(planifiee, "Bolt")
        val pleine = c.copy(kmApproche = 2.0, minutesApproche = 6.0)
        assertTrue(
            Confiance.de(c).pourcent < Confiance.de(pleine).pourcent,
            "la confiance doit mesurer ce qu'on sait, pas ce qu'on a rempli",
        )
    }

    /** Le péage est annoncé en euros, et n'est pas le prix d'une autre course. */
    @Test
    fun `un peage ne compte ni comme prix ni comme seconde offre`() {
        val avecPeage = planifiee.replace(
            "19 € • Aujourd'hui, 08:30",
            "46,7 € • 11,7 € péage • Aujourd'hui, 08:29",
        )
        assertEquals(46.7, Analyseur.analyser(avecPeage, "Bolt").prix)
        assertEquals(1, Analyseur.compterOffres(avecPeage))
    }

    /** Le bouton « Accepter » reste la marque qui autorise l'arbitrage. */
    @Test
    fun `la carte d'une course planifiee reste une offre`() {
        val jugement = Detecteur.juger(planifiee, Analyseur.analyser(planifiee, "Bolt"))
        assertTrue(jugement.arbitrable, "score ${jugement.score} — ${jugement.indices}")
    }
}
