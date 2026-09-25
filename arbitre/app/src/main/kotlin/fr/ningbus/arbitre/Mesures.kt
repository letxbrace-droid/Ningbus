package fr.ningbus.arbitre

import android.content.Context
import fr.ningbus.arbitre.moteur.Calibrage
import fr.ningbus.arbitre.moteur.Mesure
import fr.ningbus.arbitre.moteur.Realise
import org.json.JSONArray
import org.json.JSONObject

/**
 * Le carnet des durées : ce qui a été promis, et ce qui a été vécu.
 *
 * Deux écrans se rencontrent ici, à plusieurs heures d'intervalle. La carte
 * d'offre annonce « 12 min » avant que le chauffeur accepte ; l'écran
 * « Détails de la course » constate « 7 min 42 s » une fois qu'il a déposé.
 * Les rapprocher donne ce qu'aucun service de trafic ne vendra jamais : de
 * combien **cette** plateforme se trompe sur **ces** routes, à **ces** heures.
 *
 * L'appariement se fait sur le prix, et c'est volontaire. Il ne bouge pas
 * entre l'offre et le bilan — c'est même l'argument de vente du tarif annoncé
 * à l'avance — là où la distance est arrondie différemment d'un écran à
 * l'autre et où la durée est précisément ce qu'on cherche à comparer.
 */
object Mesures {

    private const val FICHIER = "mesures"
    private const val CLE = "couples"

    /** Au-delà, on oublie les plus anciennes : une plateforme change. */
    private const val MAX = 200

    /**
     * Fenêtre de rapprochement.
     *
     * Une course arbitrée puis faite se consulte dans l'heure ou deux qui
     * suivent. Au-delà de six heures, un prix identique désigne plus
     * probablement une autre course qu'un retour tardif sur celle-ci —
     * 9,00 € n'a rien de rare.
     */
    private const val FENETRE_MS = 6L * 60 * 60 * 1000

    /** Écart de prix toléré entre l'offre et le bilan, en euros. */
    private const val TOLERANCE_PRIX = 0.05

    /**
     * Range un bilan en le rapprochant de l'offre qui lui correspond.
     *
     * @return la mesure inscrite, ou null si aucune offre ne lui répond
     */
    fun inscrire(contexte: Context, realise: Realise): Mesure? {
        val maintenant = System.currentTimeMillis()
        val offre = Journal.lignes(contexte)
            .filter { maintenant - it.horodatage in 0..FENETRE_MS }
            .filter { it.minutesTrajet != null && it.minutesTrajet > 0.0 }
            .firstOrNull { l ->
                val prix = l.prix ?: return@firstOrNull false
                kotlin.math.abs(prix - realise.prix) <= TOLERANCE_PRIX
            } ?: return null

        val mesure = Mesure(
            plateforme = offre.plateforme.ifEmpty { offre.paquet },
            minutesAnnoncees = offre.minutesTrajet!!,
            minutesReelles = realise.minutes,
        )

        // Une même course consultée deux fois ne compte qu'une fois : l'écran
        // de bilan se rouvre, et un chauffeur qui vérifie ses gains fausserait
        // sa propre calibration en la regardant.
        if (deja(contexte, mesure)) return null

        val prefs = prefs(contexte)
        val tableau = JSONArray(prefs.getString(CLE, "[]"))
        val reduit = JSONArray()
        reduit.put(
            JSONObject().apply {
                put("t", maintenant)
                put("p", mesure.plateforme)
                put("a", mesure.minutesAnnoncees)
                put("r", mesure.minutesReelles)
            }
        )
        for (i in 0 until minOf(tableau.length(), MAX - 1)) reduit.put(tableau.get(i))
        prefs.edit().putString(CLE, reduit.toString()).apply()
        return mesure
    }

    fun toutes(contexte: Context): List<Mesure> {
        val tableau = try {
            JSONArray(prefs(contexte).getString(CLE, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        return (0 until tableau.length()).mapNotNull { i ->
            val o = tableau.optJSONObject(i) ?: return@mapNotNull null
            Mesure(
                plateforme = o.optString("p"),
                minutesAnnoncees = o.optDouble("a", 0.0),
                minutesReelles = o.optDouble("r", 0.0),
            )
        }
    }

    /** Le coefficient mesuré pour cette plateforme, ou null s'il est trop tôt. */
    fun facteur(contexte: Context, plateforme: String?): Double? =
        Calibrage.facteur(toutes(contexte), plateforme)

    /** Une phrase pour l'écran, ou null tant qu'il n'y a rien à dire. */
    fun resume(contexte: Context, plateforme: String? = null): String? =
        Calibrage.resume(toutes(contexte), plateforme)

    fun effacer(contexte: Context) {
        prefs(contexte).edit().remove(CLE).apply()
    }

    private fun deja(contexte: Context, mesure: Mesure): Boolean = toutes(contexte).any {
        it.plateforme == mesure.plateforme &&
            kotlin.math.abs(it.minutesAnnoncees - mesure.minutesAnnoncees) < 0.01 &&
            kotlin.math.abs(it.minutesReelles - mesure.minutesReelles) < 0.01
    }

    private fun prefs(contexte: Context) = contexte.applicationContext
        .getSharedPreferences(FICHIER, Context.MODE_PRIVATE)
}
