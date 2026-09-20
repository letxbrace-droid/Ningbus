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
     * seule course : une approche de 1,6 km lue « 6,0 km », parce qu'une
     * décimale avait été coupée en deux. L'arbre rend le texte exact, l'image
     * rend une hypothèse — deux choses qu'un journal ne doit pas confondre
     * quand il s'agit de comprendre un chiffre faux.
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

    private var derniereSignature: String? = null
    private var dernierInstant = 0L

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
    ): Boolean {
        // Sans montant il n'y a pas d'offre : une carte de navigation ou un
        // écran d'attente ne doit pas déclencher de bulle.
        if (course.prix == null) return false

        // Une analyse demandée à la main n'est jamais un doublon : si le
        // chauffeur appuie deux fois, il attend deux réponses.
        val signature = signature(paquet, course)
        val maintenant = SystemClock.elapsedRealtime()
        if (!force &&
            signature == derniereSignature &&
            maintenant - dernierInstant < FENETRE_DOUBLON_MS
        ) {
            return false
        }
        derniereSignature = signature
        dernierInstant = maintenant

        val reglages = Reglages(contexte)
        val verdict = Arbitre.arbitrer(course, reglages.bareme)

        Journal.ajouter(contexte, verdict, paquet, latenceMs, source)
        if (reglages.vibration) Haptique.signaler(contexte, verdict.decision)
        Bulle.afficher(contexte, verdict, latenceMs, source, reglages)
        return true
    }

    /**
     * Signature numérique d'une course.
     *
     * Une offre se réaffiche en continu tant que le compte à rebours tourne,
     * et la carte à l'écran change à chaque seconde qui s'écoule. Ce sont donc
     * les chiffres qui identifient la course, jamais le libellé.
     */
    private fun signature(paquet: String, c: Course): String = listOf(
        paquet, c.prix, c.kmTrajet, c.minutesTrajet, c.kmApproche, c.minutesApproche,
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
