package fr.ningbus.arbitre

import android.content.Context
import fr.ningbus.arbitre.moteur.Verdict
import org.json.JSONArray
import org.json.JSONObject

/**
 * Une course vue, telle qu'on la relit après coup.
 *
 * Le texte brut est conservé : c'est la seule matière qui permette de
 * corriger l'analyseur quand une plateforme change son libellé.
 */
data class Ligne(
    val horodatage: Long,
    val paquet: String,
    val plateforme: String,
    val decision: String,
    val euroHeure: Double?,
    val resume: String,
    val texteBrut: String,
    val latenceMs: Long,
    /** Notification, écran ou essai — pour savoir quel chemin a fonctionné. */
    val source: String,
    /**
     * Ce que valait la lecture, de 0 à 100.
     *
     * Séparé du verdict à dessein : un LAISSE sur données complètes et un
     * LAISSE calculé sur une approche inventée se ressemblent dans un journal
     * et ne s'expliquent pas pareil.
     */
    val confiance: Int = 0,
    /** Le détail champ par champ : ce qui a été lu, estimé, ou manquait. */
    val lectures: String = "",
    /** Ce que le détecteur a pensé de l'écran, sur cent. */
    val scoreOffre: Int = 0,

    // --- De quoi repeindre la course sans la recalculer ---------------------
    //
    // Le cockpit et l'historique montrent l'un la dernière course, l'autre
    // toutes les précédentes, et les deux ont besoin des mêmes chiffres. Les
    // recalculer depuis le texte brut donnerait un résultat différent dès que
    // le barème change entre-temps — un historique qui se réécrit tout seul
    // ne serait plus un historique.
    val prix: Double? = null,
    /** Prix ÷ kilomètres réellement roulés, approche comprise. */
    val euroKm: Double? = null,
    val kmCourse: Double? = null,
    val kmApproche: Double? = null,
    /** Approche + course : ce qu'il a fallu rouler pour cette offre. */
    val kmRoules: Double? = null,
    val minutes: Double? = null,
    /**
     * La durée du trajet **annoncée** par la plateforme, telle quelle.
     *
     * Distincte de [minutes], qui est le temps mobilisé calculé par le moteur.
     * Conservée pour une seule raison : c'est la moitié annoncée du couple que
     * la calibration compare, l'autre moitié venant de l'écran de bilan une
     * fois la course faite. Sans elle, il n'y a rien à confronter.
     */
    val minutesTrajet: Double? = null,
    val cout: Double? = null,
    val revenuNet: Double? = null,
    /** La contrainte dominante, telle que le moteur l'a nommée. */
    val motif: String = "",
)

/**
 * Journal local des courses arbitrées, borné à [MAX] entrées.
 *
 * Stocké en JSON dans les préférences : quelques dizaines de kilo-octets,
 * rien qui justifie une base de données, et aucune donnée ne quitte le
 * téléphone.
 */
object Journal {

    private const val MAX = 80
    private const val FICHIER = "journal"
    private const val CLE = "lignes"
    private const val CLE_INCONNUS = "paquets_inconnus"

    fun ajouter(
        contexte: Context,
        verdict: Verdict,
        paquet: String,
        latenceMs: Long,
        source: Source,
        scoreOffre: Int? = null,
    ) {
        val o = JSONObject().apply {
            put("t", System.currentTimeMillis())
            put("paquet", paquet)
            put("plateforme", verdict.course.plateforme)
            put("decision", verdict.decision.name)
            verdict.euroHeure?.let { put("eh", it) }
            put("resume", verdict.resume)
            put("brut", verdict.course.texteBrut)
            put("lat", latenceMs)
            put("source", source.libelle)
            put("conf", verdict.confiance.pourcent)
            scoreOffre?.let { put("score", it) }
            put("lect", verdict.confiance.lectures.joinToString("\n") { it.toString() })

            verdict.course.prix?.let { put("prix", it) }
            verdict.euroKmRoule?.let { put("ekm", it) }
            verdict.kmCourse?.let { put("kmc", it) }
            verdict.kmApproche?.let { put("kma", it) }
            verdict.minutesTotal?.let { put("min", it) }
            verdict.course.minutesTrajet?.let { put("mint", it) }
            verdict.revenuNet?.let { put("net", it) }
            verdict.motif?.let { put("motif", it) }
            val cout = listOfNotNull(
                verdict.coutCarburant, verdict.coutUsure, verdict.coutFixes,
            )
            if (cout.size == 3) put("cout", cout.sum())
        }
        val prefs = contexte.applicationContext
            .getSharedPreferences(FICHIER, Context.MODE_PRIVATE)
        val tableau = JSONArray(prefs.getString(CLE, "[]"))
        val reduit = JSONArray()
        reduit.put(o)
        for (i in 0 until minOf(tableau.length(), MAX - 1)) reduit.put(tableau.get(i))
        prefs.edit().putString(CLE, reduit.toString()).apply()
    }

    fun lignes(contexte: Context): List<Ligne> {
        val prefs = contexte.applicationContext
            .getSharedPreferences(FICHIER, Context.MODE_PRIVATE)
        val tableau = try {
            JSONArray(prefs.getString(CLE, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        return (0 until tableau.length()).mapNotNull { i ->
            val o = tableau.optJSONObject(i) ?: return@mapNotNull null
            Ligne(
                horodatage = o.optLong("t"),
                paquet = o.optString("paquet"),
                plateforme = o.optString("plateforme"),
                decision = o.optString("decision"),
                euroHeure = if (o.has("eh")) o.optDouble("eh") else null,
                resume = o.optString("resume"),
                texteBrut = o.optString("brut"),
                latenceMs = o.optLong("lat"),
                source = o.optString("source", "?"),
                confiance = o.optInt("conf"),
                lectures = o.optString("lect"),
                scoreOffre = o.optInt("score"),
                prix = o.reel("prix"),
                euroKm = o.reel("ekm"),
                kmCourse = o.reel("kmc"),
                kmApproche = o.reel("kma"),
                kmRoules = o.reel("kmc")?.let { c -> c + (o.reel("kma") ?: 0.0) },
                minutes = o.reel("min"),
                minutesTrajet = o.reel("mint"),
                cout = o.reel("cout"),
                revenuNet = o.reel("net"),
                motif = o.optString("motif"),
            )
        }
    }

    /** Un réel absent doit rester absent : `optDouble` rendrait 0,0. */
    private fun JSONObject.reel(cle: String): Double? =
        if (has(cle) && !isNull(cle)) optDouble(cle) else null

    fun vider(contexte: Context) {
        dejaNotes.clear()
        contexte.applicationContext.getSharedPreferences(FICHIER, Context.MODE_PRIVATE)
            .edit().remove(CLE).remove(CLE_ECARTES).remove(CLE_VUES).remove(CLE_CAPTURES).apply()
    }

    // --- Mode découverte ---------------------------------------------------

    /**
     * Retient qu'une application non écoutée a envoyé une notification qui
     * ressemble à une course. C'est ce qui permet de trouver le nom de paquet
     * d'une app absente de la liste d'origine, sans demander la permission de
     * lister toutes les applications installées.
     */
    fun signalerInconnu(contexte: Context, paquet: String) {
        val prefs = contexte.applicationContext
            .getSharedPreferences(FICHIER, Context.MODE_PRIVATE)
        val vus = prefs.getStringSet(CLE_INCONNUS, null)?.toMutableSet() ?: mutableSetOf()
        if (vus.add(paquet)) {
            prefs.edit().putStringSet(CLE_INCONNUS, vus).apply()
        }
    }

    fun inconnus(contexte: Context): Set<String> =
        contexte.applicationContext.getSharedPreferences(FICHIER, Context.MODE_PRIVATE)
            .getStringSet(CLE_INCONNUS, null)?.toSet() ?: emptySet()

    // --- Tout ce qui a été vu ----------------------------------------------

    private const val CLE_VUES = "notifications_vues"
    private const val MAX_VUES = 12

    /**
     * Retient toute notification qui ressemble à une course, **avant** tout
     * filtrage par application.
     *
     * C'est le diagnostic qui manquait : quand rien ne se passe, il permet de
     * distinguer « la notification n'est jamais arrivée » de « elle est
     * arrivée mais n'a pas été retenue », et donne le nom de paquet exact.
     */
    @Synchronized
    fun signalerVue(contexte: Context, paquet: String, texte: String) {
        val empreinte = (paquet + texte).hashCode()
        if (!dejaNotes.add(empreinte)) return
        if (dejaNotes.size > 64) dejaNotes.clear()
        empiler(contexte, CLE_VUES, MAX_VUES, paquet, texte)
    }

    fun notificationsVues(contexte: Context): List<Pair<String, String>> =
        depiler(contexte, CLE_VUES)

    // --- Écrans écartés ----------------------------------------------------

    private const val CLE_ECARTES = "ecrans_ecartes"
    private const val MAX_ECARTES = 5

    /** Empreintes déjà notées, pour ne pas réécrire à chaque rafraîchissement. */
    private val dejaNotes = mutableSetOf<Int>()

    /**
     * Retient un écran qui portait un montant mais n'a pas été reconnu comme
     * une offre. Si des courses passent à travers le filtre, c'est ici qu'on
     * voit pourquoi, et quel marqueur ajouter.
     */
    @Synchronized
    fun signalerEcranIgnore(contexte: Context, paquet: String, texte: String) {
        val empreinte = texte.hashCode()
        if (!dejaNotes.add(empreinte)) return
        if (dejaNotes.size > 64) dejaNotes.clear()

        empiler(contexte, CLE_ECARTES, MAX_ECARTES, paquet, texte)
    }

    /** Les écrans écartés, du plus récent au plus ancien. */
    fun ecransEcartes(contexte: Context): List<Pair<String, String>> =
        depiler(contexte, CLE_ECARTES)

    // --- Captures brutes ----------------------------------------------------

    private const val CLE_CAPTURES = "captures"
    private const val MAX_CAPTURES = 6

    /**
     * Le texte de l'écran, tel quel, sans interprétation.
     *
     * Quand une offre échappe à l'analyse, c'est la seule donnée qui permette
     * de comprendre plutôt que de supposer : elle dit exactement ce que le
     * service a vu, et donc où la lecture s'est arrêtée.
     *
     * @param force capture demandée par l'utilisateur : elle passe outre le
     *   dédoublonnage, car appuyer deux fois veut dire vouloir deux relevés.
     */
    @Synchronized
    fun signalerCapture(contexte: Context, paquet: String, texte: String, force: Boolean = false) {
        if (!force) {
            val empreinte = (paquet + texte).hashCode()
            if (!dejaNotes.add(empreinte)) return
            if (dejaNotes.size > 64) dejaNotes.clear()
        }
        empiler(contexte, CLE_CAPTURES, MAX_CAPTURES, paquet, texte)
    }

    fun captures(contexte: Context): List<Pair<String, String>> = depiler(contexte, CLE_CAPTURES)

    // --- Petite pile bornée, partagée par les traces -----------------------

    private fun empiler(
        contexte: Context,
        cle: String,
        maximum: Int,
        paquet: String,
        texte: String,
    ) {
        val prefs = contexte.applicationContext
            .getSharedPreferences(FICHIER, Context.MODE_PRIVATE)
        val tableau = try {
            JSONArray(prefs.getString(cle, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        val reduit = JSONArray()
        reduit.put(
            JSONObject().apply {
                put("t", System.currentTimeMillis())
                put("paquet", paquet)
                put("brut", texte.take(4000))
            }
        )
        for (i in 0 until minOf(tableau.length(), maximum - 1)) reduit.put(tableau.get(i))
        prefs.edit().putString(cle, reduit.toString()).apply()
    }

    private fun depiler(contexte: Context, cle: String): List<Pair<String, String>> {
        val prefs = contexte.applicationContext
            .getSharedPreferences(FICHIER, Context.MODE_PRIVATE)
        val tableau = try {
            JSONArray(prefs.getString(cle, "[]"))
        } catch (e: Exception) {
            return emptyList()
        }
        return (0 until tableau.length()).mapNotNull { i ->
            val o = tableau.optJSONObject(i) ?: return@mapNotNull null
            o.optString("paquet") to o.optString("brut")
        }
    }

    fun oublierInconnu(contexte: Context, paquet: String) {
        val prefs = contexte.applicationContext
            .getSharedPreferences(FICHIER, Context.MODE_PRIVATE)
        val vus = prefs.getStringSet(CLE_INCONNUS, null)?.toMutableSet() ?: return
        vus.remove(paquet)
        prefs.edit().putStringSet(CLE_INCONNUS, vus).apply()
    }
}
