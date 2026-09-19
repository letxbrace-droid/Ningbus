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

    fun ajouter(contexte: Context, verdict: Verdict, paquet: String, latenceMs: Long) {
        val o = JSONObject().apply {
            put("t", System.currentTimeMillis())
            put("paquet", paquet)
            put("plateforme", verdict.course.plateforme)
            put("decision", verdict.decision.name)
            verdict.euroHeure?.let { put("eh", it) }
            put("resume", verdict.resume)
            put("brut", verdict.course.texteBrut)
            put("lat", latenceMs)
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
            )
        }
    }

    fun vider(contexte: Context) {
        contexte.applicationContext.getSharedPreferences(FICHIER, Context.MODE_PRIVATE)
            .edit().remove(CLE).apply()
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

    fun oublierInconnu(contexte: Context, paquet: String) {
        val prefs = contexte.applicationContext
            .getSharedPreferences(FICHIER, Context.MODE_PRIVATE)
        val vus = prefs.getStringSet(CLE_INCONNUS, null)?.toMutableSet() ?: return
        vus.remove(paquet)
        prefs.edit().putStringSet(CLE_INCONNUS, vus).apply()
    }
}
