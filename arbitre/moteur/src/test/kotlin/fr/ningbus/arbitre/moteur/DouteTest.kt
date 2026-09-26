package fr.ningbus.arbitre.moteur

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La confiance mesurait la complétude, jamais la justesse.
 *
 * Trois erreurs de terrain l'ont dit, et c'est la même à chaque fois. À
 * Grigny, un « 1 » lu « l ». Le 25/09, un montant de 4 768 € pour 47,7 km. À
 * Antony, la distance du bandeau GPS prise pour celle de la course. **Les
 * trois fois, la bulle annonçait 100 %** — parce que tous les champs étaient
 * remplis.
 *
 * Un champ vide se voit et fait baisser la confiance. Un champ rempli de
 * travers ne se voit pas et la faisait monter. Aucun réglage de poids ne
 * corrige ce renversement : il fallait que l'analyseur dise quand il a dû
 * trancher, deviner ou écarter.
 */
class DouteTest {

    /** Une carte nette : rien à trancher, la confiance reste entière. */
    @Test
    fun `une lecture sans hesitation reste a cent pour cent`() {
        val c = Analyseur.analyser(
            "Bolt 9,80 € 4 min • 1,4 km 20 Rue Voltaire 12 min • 8,2 km Accepter",
            "Bolt",
        )
        assertTrue(c.doutes.isEmpty(), "aucun doute attendu : ${c.doutes}")
        assertEquals(100, Confiance.de(c).pourcent)
    }

    /**
     * Le cœur de la correction : **un seul doute suffit à interdire le feu
     * vert.** Une carte dont tous les champs sont remplis mais dont l'un a
     * peut-être été mal choisi peut encore être refusée — c'est sans danger —
     * mais elle ne peut plus être recommandée.
     */
    @Test
    fun `un seul doute fait passer sous le seuil de fiabilite`() {
        val c = Course(
            plateforme = "Bolt",
            prix = 10.40, minutesApproche = 3.0, kmApproche = 1.7,
            minutesTrajet = 12.0, kmTrajet = 8.6,
            doutes = listOf(Doute.INTRUS),
        )
        val confiance = Confiance.de(c)
        assertEquals(70, confiance.pourcent)
        assertTrue(!confiance.fiable, "une lecture troublée ne doit pas être fiable")
    }

    /** La carte d'Antony, qui a motivé tout ceci, ne s'annonce plus certaine. */
    @Test
    fun `la carte d'Antony n'annonce plus cent pour cent`() {
        val ecran = """
            Bolt Comfort Carte
            10,40 € (net, TTC)
            3 min • 1.7 km
            4 Avenue Jacques Chirac, Antony 92160
            12 min • 8.6 km
            Terminal 3, Aéroport de Paris-Orly (ORY)
            Accepter
            A6B 3.8 km 13:29
        """.trimIndent()
        val v = Arbitre.arbitrer(Analyseur.analyser(ecran, "Bolt"))
        assertTrue(
            v.confiance.pourcent < 80,
            "annoncée à ${v.confiance.pourcent} % alors qu'un nombre a été écarté",
        )
        assertTrue(v.confiance.resume.contains("troublée"), v.confiance.resume)
    }

    /**
     * Et le feu vert devient impossible sur une lecture troublée, quel que
     * soit le résultat du calcul. C'est la règle qui manquait : le moteur
     * pouvait recommander une course sur des chiffres qu'il avait lui-même
     * choisis parmi plusieurs.
     */
    @Test
    fun `pas de feu vert sur une lecture troublee`() {
        val excellente = Course(
            plateforme = "Uber",
            prix = 40.0, minutesApproche = 2.0, kmApproche = 0.5,
            minutesTrajet = 25.0, kmTrajet = 20.0,
        )
        assertEquals(Decision.PRENDS, Arbitre.arbitrer(excellente).decision)
        assertEquals(
            Decision.LIMITE,
            Arbitre.arbitrer(excellente.copy(doutes = listOf(Doute.INTRUS))).decision,
            "la même course, lue de travers, ne peut plus être conseillée",
        )
    }

    /**
     * La répétition coûte moins cher : c'est une gêne courante sur des cartes
     * qui affichent deux fois la même distance, et le moteur la traite
     * correctement depuis le 21/09. Elle pèse sans condamner.
     */
    @Test
    fun `une repetition pese moins qu'un intrus`() {
        val base = Course(
            plateforme = "Bolt",
            prix = 10.40, minutesApproche = 3.0, kmApproche = 1.7,
            minutesTrajet = 12.0, kmTrajet = 8.6,
        )
        val repete = Confiance.de(base.copy(doutes = listOf(Doute.REPETITION))).pourcent
        val intrus = Confiance.de(base.copy(doutes = listOf(Doute.INTRUS))).pourcent
        assertTrue(repete > intrus, "$repete devrait dépasser $intrus")
        assertTrue(repete > 80, "obtenu $repete")
    }

    /** Deux hésitations se composent au lieu de s'additionner. */
    @Test
    fun `les doutes se composent`() {
        val c = Course(
            plateforme = "Uber",
            prix = 20.0, minutesApproche = 3.0, kmApproche = 1.0,
            minutesTrajet = 15.0, kmTrajet = 10.0,
            doutes = listOf(Doute.INTRUS, Doute.PLUSIEURS_MONTANTS),
        )
        assertEquals(49, Confiance.de(c).pourcent, "0,70 × 0,70 = 0,49")
    }

    /**
     * Le montant multiple est un doute, et il vaut d'être dit : c'est
     * exactement la situation du bonus de prise en charge, où le moteur
     * choisit lequel des deux chiffres est le prix.
     */
    @Test
    fun `plusieurs montants sont un doute`() {
        val c = Analyseur.analyser(
            "Uber 18,00 € · bonus de prise en charge · 4 min (1,2 km) de vous · 14 min (7 km)",
            "Uber",
        )
        // Le bonus est écarté comme parasite : aucun doute ici.
        assertTrue(Doute.PLUSIEURS_MONTANTS !in c.doutes, "${c.doutes}")

        val deux = Analyseur.analyser(
            "Uber 18,00 € puis 25,00 € · 4 min (1,2 km) de vous · 14 min (7 km)",
            "Uber",
        )
        assertTrue(Doute.PLUSIEURS_MONTANTS in deux.doutes, "${deux.doutes}")
    }

    /** Une lecture complétée par une autre hérite des hésitations des deux. */
    @Test
    fun `les doutes survivent a la completion`() {
        val partielle = Course(prix = 12.0, doutes = listOf(Doute.INTRUS))
        val suivante = Course(minutesTrajet = 10.0, kmTrajet = 5.0)
        assertTrue(Doute.INTRUS in suivante.completer(partielle).doutes)
    }
}
