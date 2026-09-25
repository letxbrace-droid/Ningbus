package fr.ningbus.arbitre

import android.content.Context
import fr.ningbus.arbitre.moteur.Calibrage
import org.json.JSONArray

/**
 * À quelle fréquence les offres arrivent, mesuré plutôt que supposé.
 *
 * C'est le chiffre qui manquait à tout le reste, et une journée relevée l'a
 * imposé : sept courses entre 12:49 et 18:54, **3 h 44 de vide pour 2 h 20
 * payées**, trou médian de vingt-neuf minutes.
 *
 * Son effet est contre-intuitif. Le temps d'attente entre deux courses ne
 * dépend presque pas de la longueur de celle qu'on vient de faire — il faut
 * attendre la suivante, un point c'est tout. Une course de huit minutes traîne
 * donc la même attente qu'une course de quarante, et l'amortit sur cinq fois
 * moins de temps payé. Sur ce relevé, la course qui affichait le meilleur
 * euro/heure facturé de la journée s'est révélée la plus mauvaise une fois son
 * attente comptée, et la course au plus mauvais euro/kilomètre s'est révélée
 * la meilleure. Les deux classements étaient exactement inversés.
 *
 * On mesure donc, et on ne suppose rien :
 *
 *  - **l'écart entre deux offres**, et non entre deux courses acceptées.
 *    L'application ne sait pas ce qui a été pris, mais elle voit tout ce qui
 *    est proposé — et c'est bien le rythme des propositions qui dit ce que
 *    coûte un refus ;
 *  - **la médiane**, parce qu'une pause déjeuner ou un plein produisent un
 *    écart de deux heures qui n'apprend rien sur le secteur ;
 *  - **une fenêtre courte**, parce que la densité d'un vendredi 18 h n'a rien
 *    à voir avec celle d'un mardi 14 h. Ce qu'on veut savoir, c'est ce qui se
 *    passe *maintenant*.
 */
object Densite {

    private const val FICHIER = "densite"
    private const val CLE = "instants"

    /** On ne retient que les offres de ces dernières heures. */
    private const val FENETRE_MS = 3L * 60 * 60 * 1000

    /**
     * Au-delà, ce n'est plus une attente entre deux offres mais une pause.
     *
     * Un déjeuner, un plein, une sieste : l'écart existe et ne dit rien du
     * secteur. La médiane y résiste déjà, mais l'écarter d'emblée évite qu'une
     * courte série ne soit emportée par une seule coupure.
     */
    private const val ECART_MAX_MIN = 90.0

    /** En dessous, une médiane sur deux écarts ne décrit rien. */
    const val ECARTS_MINIMAUX = 4

    /** Range l'instant d'une offre vue. À appeler à chaque offre arbitrée. */
    fun noter(contexte: Context) {
        val maintenant = System.currentTimeMillis()
        val gardes = instants(contexte).filter { maintenant - it < FENETRE_MS } + maintenant
        val tableau = JSONArray()
        // Bornée : une journée très dense ne doit pas gonfler indéfiniment.
        gardes.takeLast(60).forEach { tableau.put(it) }
        prefs(contexte).edit().putString(CLE, tableau.toString()).apply()
    }

    /**
     * L'attente médiane entre deux offres, en minutes, ou null tant qu'on n'a
     * pas de quoi le dire.
     */
    fun minutesEntreOffres(contexte: Context): Double? {
        val maintenant = System.currentTimeMillis()
        val vus = instants(contexte).filter { maintenant - it < FENETRE_MS }.sorted()
        if (vus.size < ECARTS_MINIMAUX + 1) return null
        val ecarts = vus.zipWithNext { a, b -> (b - a) / 60000.0 }
            .filter { it in 0.5..ECART_MAX_MIN }
        if (ecarts.size < ECARTS_MINIMAUX) return null
        return Calibrage.mediane(ecarts)
    }

    /** Une phrase pour l'écran, ou null tant qu'il n'y a rien à dire. */
    fun resume(contexte: Context): String? {
        val minutes = minutesEntreOffres(contexte) ?: return null
        val n = instants(contexte).count { System.currentTimeMillis() - it < FENETRE_MS }
        return "une offre toutes les ${Math.round(minutes)} min · $n vues en 3 h"
    }

    fun effacer(contexte: Context) {
        prefs(contexte).edit().remove(CLE).apply()
    }

    private fun instants(contexte: Context): List<Long> {
        val tableau = try {
            JSONArray(prefs(contexte).getString(CLE, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        return (0 until tableau.length()).map { tableau.optLong(it) }.filter { it > 0 }
    }

    private fun prefs(contexte: Context) = contexte.applicationContext
        .getSharedPreferences(FICHIER, Context.MODE_PRIVATE)
}
