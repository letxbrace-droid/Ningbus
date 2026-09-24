package fr.ningbus.arbitre

import android.content.Context
import fr.ningbus.arbitre.moteur.Planifiee
import fr.ningbus.arbitre.moteur.Planifiees
import org.json.JSONArray
import org.json.JSONObject

/**
 * Les courses planifiées relevées au fil du défilement.
 *
 * Il en faut un, et le terrain l'impose : l'arbre d'accessibilité ne rend que
 * ce qui est **dessiné**. Sur la liste de Bolt, cela fait deux offres à la
 * fois, alors que le relevé du 24/09 en comptait dix-neuf dans la même
 * demi-heure. Comparer ce qu'un seul écran montre ne servirait donc à rien —
 * ce n'est pas le choix qu'a le chauffeur.
 *
 * D'où l'accumulation : chaque écran de liste traversé dépose ses offres ici,
 * en silence et sans bulle. Le chauffeur fait défiler sa liste comme
 * d'habitude, puis appuie **une fois** sur la pastille, et le classement porte
 * sur tout ce qu'il a vu. Une offre relevée deux fois ne compte qu'une, sans
 * quoi la médiane se déplacerait au gré du pouce.
 *
 * Conservé dans les préférences plutôt qu'en mémoire seule : la comparaison se
 * relit après avoir fermé l'écran, et survit à une application chauffeur qui
 * fait le ménage en arrière-plan.
 */
object Comparateur {

    private const val FICHIER = "comparateur"
    private const val CLE = "planifiees"
    private const val CLE_INSTANT = "instant"

    /**
     * Au-delà, ce n'est plus une liste qu'on compare mais un historique.
     *
     * Les courses planifiées se renouvellent : une comparaison qui traînerait
     * cinquante offres de trois sessions différentes ferait décider sur des
     * créneaux périmés.
     */
    private const val MAX = 40

    /** Passé ce délai sans rien relever, la comparaison précédente est oubliée. */
    private const val PEREMPTION_MS = 30 * 60 * 1000L

    /**
     * La liste en mémoire, et l'écran d'où elle vient.
     *
     * Le relevé est appelé à **chaque événement d'accessibilité** tant que la
     * liste est à l'écran, c'est-à-dire plusieurs fois par seconde pendant que
     * le pouce défile. Relire et réécrire les préférences à ce rythme
     * coûterait plus que tout le reste de la lecture d'écran réunie. Deux
     * gardes, donc : l'empreinte du texte écarte l'écran identique avant même
     * de l'analyser, et le cache évite de relire ce qu'on vient d'écrire.
     */
    private var cache: List<Planifiee>? = null
    private var derniereEmpreinte = 0

    /**
     * Dépose les offres d'un écran de liste.
     *
     * @return le nombre d'offres nouvelles, pour la trace de diagnostic.
     */
    @Synchronized
    fun relever(contexte: Context, texte: String): Int {
        val empreinte = texte.hashCode()
        if (empreinte == derniereEmpreinte) return 0
        derniereEmpreinte = empreinte

        val vues = Planifiees.decouper(texte)
        if (vues.isEmpty()) return 0

        // [lire] rend une liste vide quand la comparaison précédente est
        // périmée : les offres d'une session d'il y a deux heures portent sur
        // des créneaux passés, et fausseraient la médiane comme le classement.
        val anciennes = lire(contexte)
        val connues = anciennes.map { it.empreinte }.toSet()
        val nouvelles = vues.filterNot { it.empreinte in connues }

        val prefs = prefs(contexte)
        if (nouvelles.isEmpty()) {
            // Rien de neuf, mais l'écran est bien vivant : on repousse la
            // péremption plutôt que de laisser expirer une liste qu'on relit.
            prefs.edit().putLong(CLE_INSTANT, System.currentTimeMillis()).apply()
            return 0
        }

        val tout = (anciennes + nouvelles).takeLast(MAX)
        val tableau = JSONArray()
        for (c in tout) {
            tableau.put(
                JSONObject().apply {
                    put("prix", c.prix)
                    put("peage", c.peage)
                    put("km", c.km)
                    put("cat", c.categorie)
                    put("creneau", c.creneau)
                    put("dep", c.depart)
                    put("arr", c.arrivee)
                }
            )
        }
        cache = tout
        prefs.edit()
            .putString(CLE, tableau.toString())
            .putLong(CLE_INSTANT, System.currentTimeMillis())
            .apply()
        return nouvelles.size
    }

    /** Tout ce qui a été relevé depuis la dernière remise à zéro. */
    @Synchronized
    fun lire(contexte: Context): List<Planifiee> {
        val prefs = prefs(contexte)
        val instant = prefs.getLong(CLE_INSTANT, 0L)
        if (instant > 0L && System.currentTimeMillis() - instant > PEREMPTION_MS) {
            cache = emptyList()
            return emptyList()
        }
        cache?.let { return it }

        val tableau = try {
            JSONArray(prefs.getString(CLE, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        val liste = (0 until tableau.length()).mapNotNull { i ->
            val o = tableau.optJSONObject(i) ?: return@mapNotNull null
            Planifiee(
                prix = o.optDouble("prix"),
                peage = o.optDouble("peage"),
                km = o.optDouble("km"),
                categorie = o.optString("cat"),
                creneau = o.optString("creneau"),
                depart = o.optString("dep"),
                arrivee = o.optString("arr"),
            )
        }
        cache = liste
        return liste
    }

    @Synchronized
    fun vider(contexte: Context) {
        cache = emptyList()
        derniereEmpreinte = 0
        prefs(contexte).edit().remove(CLE).remove(CLE_INSTANT).apply()
    }

    private fun prefs(contexte: Context) =
        contexte.applicationContext.getSharedPreferences(FICHIER, Context.MODE_PRIVATE)
}
