package fr.ningbus.arbitre.moteur

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Antony → Orly Terminal 3, relevée en direct sur Bolt, avec le GPS ouvert.
 *
 * La carte annonce **10,40 €**, une approche de **3 min • 1,7 km** vers
 * l'avenue Jacques-Chirac, et un trajet de **12 min • 8,6 km** vers le
 * terminal 3. Le verdict rendu a été LIMITE à 1,89 €/km et 28 €/h, sur une
 * course de 1,7 km et une approche de 3,8 km — c'est-à-dire sur des chiffres
 * qui ne figurent pas sur la carte.
 *
 * La cause : le service lit **toutes les fenêtres visibles**, et le bandeau de
 * navigation posé par-dessus portait sa propre distance, celle du prochain
 * embranchement, qui décompte pendant qu'on roule. Trois distances pour deux
 * étapes. Les 8,6 km du trajet ont été chassés du calcul, et la course d'Orly
 * a été arbitrée comme un trajet de dix-sept cents mètres.
 *
 * Et comme à Grigny, comme sur le montant de 4 768 €, la bulle annonçait
 * **100 % de confiance** : tous les champs étaient remplis.
 */
class AntonyTest {

    /** La carte telle qu'elle se lit, bandeau du GPS compris. */
    private val ecran = """
        Bolt Comfort Carte
        10,40 € (net, TTC)
        Tarik 5.0
        3 min • 1.7 km
        4 Avenue Jacques Chirac, Antony 92160
        12 min • 8.6 km
        Terminal 3, Aéroport de Paris-Orly (ORY)
        Accepter
        A6B 3.8 km 13:29
    """.trimIndent()

    @Test
    fun `le trajet est celui de la carte, pas celui du bandeau`() {
        val c = Analyseur.analyser(ecran, "Bolt")
        assertEquals(10.40, assertNotNull(c.prix), 0.001)
        assertEquals(1.7, assertNotNull(c.kmApproche), 0.001)
        assertEquals(3.0, assertNotNull(c.minutesApproche), 0.001)
        assertEquals(8.6, assertNotNull(c.kmTrajet), 0.001, "les 8,6 km ne doivent pas disparaître")
        assertEquals(12.0, assertNotNull(c.minutesTrajet), 0.001)
    }

    /**
     * Le verdict que la carte méritait. L'erreur de lecture ne changeait pas
     * un détail : elle faisait passer une course de LAISSE à LIMITE, et son
     * euro/kilomètre de 1,01 à 1,89.
     */
    @Test
    fun `le verdict redevient juste`() {
        val v = Arbitre.arbitrer(Analyseur.analyser(ecran, "Bolt"))
        assertEquals(1.01, assertNotNull(v.euroKmRoule), 0.01)
        assertTrue(assertNotNull(v.euroHeure) < 23.0, "obtenu ${v.euroHeure} €/h")
        assertEquals(Decision.LAISSE, v.decision)
    }

    @Test
    fun `l'ecart est signale plutot que tu`() {
        val c = Analyseur.analyser(ecran, "Bolt")
        assertTrue(
            c.remarques.any { it.contains("hors carte") },
            "la distance écartée doit laisser une trace : ${c.remarques}",
        )
    }

    /**
     * Le garde-fou ne doit pas mordre sur les formats qui annoncent une
     * distance sans durée : ils ne forment aucun couple, et la règle exige
     * deux couples complets avant d'écarter quoi que ce soit.
     */
    @Test
    fun `une distance annoncee seule reste intacte`() {
        val c = Analyseur.analyser(
            "UberX 15,32 € · 4 min (1,3 km) de vous · Course de 9,8 km",
            "Uber",
        )
        assertEquals(9.8, assertNotNull(c.kmTrajet), 0.001)
        assertEquals(1.3, assertNotNull(c.kmApproche), 0.001)
    }

    /** Et sur une carte ordinaire à deux distances, rien ne bouge. */
    @Test
    fun `une carte a deux distances n'est pas touchee`() {
        val c = Analyseur.analyser(
            "Bolt 9,80 € 4 min • 1,4 km 20 Rue Voltaire 12 min • 8,2 km Accepter",
            "Bolt",
        )
        assertEquals(1.4, assertNotNull(c.kmApproche), 0.001)
        assertEquals(8.2, assertNotNull(c.kmTrajet), 0.001)
        assertTrue(c.remarques.none { it.contains("hors carte") }, "${c.remarques}")
    }
}
