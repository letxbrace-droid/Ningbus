package fr.ningbus.arbitre

import android.content.Context
import android.os.SystemClock
import fr.ningbus.arbitre.moteur.Analyseur
import fr.ningbus.arbitre.moteur.Arbitre
import fr.ningbus.arbitre.moteur.Course
import fr.ningbus.arbitre.moteur.Plateformes

/** Par où l'offre est arrivée. */
enum class Source(val libelle: String) {
    NOTIFICATION("notification"),

    /** Arbre d'accessibilité : le texte tel que l'application l'a écrit. */
    ECRAN("écran"),

    /**
     * Reconnaissance de texte sur une capture.
     *
     * Distinguée de [ECRAN] à dessein, et le terrain a montré pourquoi en une
     * seule course : une approche de 1,6 km lue « 6,0 km », parce que la
     * reconnaissance avait rendu « 1.6 » en « l.6 » — le chiffre un devenu la
     * lettre L. L'arbre rend le texte exact, l'image rend une hypothèse —
     * deux choses qu'un journal ne doit pas confondre quand il s'agit de
     * comprendre un chiffre faux.
     */
    IMAGE("image"),

    ESSAI("essai"),
}

/**
 * Le chemin commun aux deux façons de voir une offre.
 *
 * Il en faut deux, parce qu'une seule ne suffit pas :
 *
 *  - **la notification** arrive quand l'application chauffeur est en arrière-plan
 *    ou l'écran verrouillé ;
 *  - **la lecture d'écran** prend le relais quand elle est au premier plan,
 *    cas où Uber dessine sa carte d'offre sans rien notifier du tout.
 *
 * Les deux peuvent se déclencher pour une même course : c'est pourquoi le
 * dédoublonnage vit ici, et non dans chaque service.
 */
object Arbitrage {

    private const val FENETRE_DOUBLON_MS = 45_000L

    /**
     * Combien de courses récentes on retient pour les reconnaître.
     *
     * Une seule ne suffisait pas, et le terrain l'a montré sans ambiguïté :
     * une même offre Bolt s'est inscrite **cinq fois** dans le journal, en
     * alternant « Bolt » et « Uber ». Le bouton flottant d'Uber était posé
     * par-dessus la carte, si bien que la fenêtre changeait de nom d'une
     * lecture à l'autre — et une mémoire d'une seule signature ne dédoublonne
     * jamais une alternance A, B, A, B.
     */
    private const val COURSES_RETENUES = 16

    private val recentes = LinkedHashMap<String, Long>()

    /** Extraction seule, sans effet de bord : sert aussi à décider si on affiche. */
    fun lire(paquet: String, texte: String): Course =
        Analyseur.analyser(texte, Plateformes.nom(paquet))

    /**
     * Calcule le verdict et le montre, sauf si la même course vient d'être
     * arbitrée.
     *
     * @return vrai si une bulle a bien été posée
     */
    @Synchronized
    fun rendre(
        contexte: Context,
        paquet: String,
        course: Course,
        source: Source,
        latenceMs: Long,
        force: Boolean = false,
        /**
         * Ce que le détecteur a pensé de l'écran, sur cent.
         *
         * Affiché tel quel, sans la mécanique qui l'a produit : le chauffeur
         * n'a pas à lire un laboratoire, mais un score bas sous un verdict
         * lui dit que l'écran était douteux avant même d'être calculé.
         */
        scoreOffre: Int? = null,
    ): Boolean {
        // Sans montant il n'y a pas d'offre : une carte de navigation ou un
        // écran d'attente ne doit pas déclencher de bulle.
        if (course.prix == null) return false

        // Une analyse demandée à la main n'est jamais un doublon : si le
        // chauffeur appuie deux fois, il attend deux réponses.
        val signature = signature(course)
        val maintenant = SystemClock.elapsedRealtime()
        if (!force) {
            recentes.entries.removeAll { maintenant - it.value >= FENETRE_DOUBLON_MS }
            if (recentes.containsKey(signature)) return false
        }
        recentes[signature] = maintenant
        while (recentes.size > COURSES_RETENUES) {
            recentes.remove(recentes.keys.first())
        }

        val reglages = Reglages(contexte)
        val verdict = Arbitre.arbitrer(course, reglages.baremePour(course.plateforme))

        Journal.ajouter(contexte, verdict, paquet, latenceMs, source, scoreOffre)
        if (reglages.vibration) Haptique.signaler(contexte, verdict.decision)
        Bulle.afficher(contexte, verdict, latenceMs, source, reglages, scoreOffre)
        return true
    }

    /**
     * Signature numérique d'une course.
     *
     * Une offre se réaffiche en continu tant que le compte à rebours tourne,
     * et la carte à l'écran change à chaque seconde qui s'écoule. Ce sont donc
     * les chiffres qui identifient la course, jamais le libellé.
     *
     * **Et surtout pas le nom du paquet.** Il y figurait, et c'est ce qui a
     * laissé passer cinq fois la même course de Brétigny : lue tantôt sous
     * « Bolt », tantôt sous « Uber » selon la fenêtre qui avait le dessus, elle
     * portait deux signatures pour un seul prix, une seule distance et une
     * seule durée. Deux applications qui annoncent exactement les mêmes
     * chiffres à la même minute, c'est la même carte vue deux fois — et si
     * jamais c'étaient deux offres, en montrer une suffit, puisqu'elles se
     * valent au centime près.
     */
    private fun signature(c: Course): String = listOf(
        c.prix, c.kmTrajet, c.minutesTrajet, c.kmApproche, c.minutesApproche,
    ).joinToString("|")

    /** Un montant et une distance ou une durée : c'est peut-être une course. */
    fun ressembleAUneCourse(texte: String): Boolean {
        val t = texte.lowercase()
        val montant = t.contains("€") || t.contains("eur")
        val trajet = t.contains("km") || t.contains("min")
        return montant && trajet
    }

    /**
     * Filet volontairement large, pour la trace de diagnostic seulement.
     *
     * Une notification de course dont les chiffres vivent dans une vue
     * personnalisée n'arrive souvent qu'en « Nouvelle course » : exiger un
     * montant la rendrait invisible, et avec elle la raison du silence.
     */
    fun pourraitEtreUneCourse(texte: String): Boolean {
        val t = texte.lowercase()
        return MOTS_DE_COURSE.any { t.contains(it) }
    }

    private val MOTS_DE_COURSE = listOf(
        "€", "eur", " km", "min", "course", "trajet", "trip", "ride",
    )
}
