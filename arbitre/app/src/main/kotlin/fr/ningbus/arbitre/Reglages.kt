package fr.ningbus.arbitre

import android.content.Context
import android.content.SharedPreferences
import fr.ningbus.arbitre.moteur.Bareme
import fr.ningbus.arbitre.moteur.Plateformes

/**
 * Tout ce que l'utilisateur règle, rangé dans les préférences partagées.
 *
 * L'objet est volontairement bon marché à construire : le service d'écoute
 * en crée un à chaque notification, et une lecture de SharedPreferences déjà
 * chargées est un accès mémoire.
 */
class Reglages(contexte: Context) {

    private val p: SharedPreferences =
        contexte.applicationContext.getSharedPreferences(FICHIER, Context.MODE_PRIVATE)

    // --- Fonctionnement ----------------------------------------------------

    /** Interrupteur général : couper l'arbitrage sans retirer les permissions. */
    var actif: Boolean
        get() = p.getBoolean("actif", true)
        set(v) = p.edit().putBoolean("actif", v).apply()

    /** Durée d'affichage de la bulle, en secondes. */
    var secondesAffichage: Int
        get() = p.getInt("secondes", 12)
        set(v) = p.edit().putInt("secondes", v.coerceIn(3, 60)).apply()

    /** Vibration codée selon le verdict, pour décider sans lire. */
    var vibration: Boolean
        get() = p.getBoolean("vibration", true)
        set(v) = p.edit().putBoolean("vibration", v).apply()

    /** Hauteur à laquelle la bulle a été déposée par glissement. */
    var positionY: Int
        get() = p.getInt("positionY", 0)
        set(v) = p.edit().putInt("positionY", v).apply()

    /**
     * Journalise les applications inconnues qui envoient une notification
     * contenant un montant : sert à retrouver le nom de paquet exact d'une
     * app chauffeur absente de la liste d'origine.
     */
    var modeDecouverte: Boolean
        get() = p.getBoolean("decouverte", true)
        set(v) = p.edit().putBoolean("decouverte", v).apply()

    // --- Applications écoutées ---------------------------------------------

    var paquets: Set<String>
        // Copie défensive : l'ensemble rendu par getStringSet ne doit jamais
        // être modifié en place, c'est celui que garde le cache interne.
        get() = p.getStringSet("paquets", null)?.toSet()
            ?: Plateformes.PAR_DEFAUT.keys.toSet()
        set(v) = p.edit().putStringSet("paquets", v.toSet()).apply()

    fun ecoute(paquet: String): Boolean = paquet in paquets

    fun ajouterPaquet(paquet: String) {
        if (paquet.isNotBlank()) paquets = paquets + paquet.trim()
    }

    fun retirerPaquet(paquet: String) {
        paquets = paquets - paquet
    }

    // --- Barème économique --------------------------------------------------

    var bareme: Bareme
        get() = Bareme(
            objectifHeure = lire("objectifHeure", 25.0),
            coutKm = lire("coutKm", 0.22),
            commission = lire("commission", 0.0),
            minutesAttente = lire("minutesAttente", 2.0),
            partRetour = lire("partRetour", 0.35),
            approcheMaxMinutes = lire("approcheMax", 12.0),
            prixPlancher = lire("prixPlancher", 6.0),
            marge = lire("marge", 0.15),
            prudenceTrafic = p.getBoolean("prudenceTrafic", true),
            vitesseParDefaut = lire("vitesseDefaut", 22.0),
        )
        set(b) {
            p.edit()
                .putFloat("objectifHeure", b.objectifHeure.toFloat())
                .putFloat("coutKm", b.coutKm.toFloat())
                .putFloat("commission", b.commission.toFloat())
                .putFloat("minutesAttente", b.minutesAttente.toFloat())
                .putFloat("partRetour", b.partRetour.toFloat())
                .putFloat("approcheMax", b.approcheMaxMinutes.toFloat())
                .putFloat("prixPlancher", b.prixPlancher.toFloat())
                .putFloat("marge", b.marge.toFloat())
                .putBoolean("prudenceTrafic", b.prudenceTrafic)
                .putFloat("vitesseDefaut", b.vitesseParDefaut.toFloat())
                .apply()
        }

    private fun lire(cle: String, defaut: Double): Double =
        p.getFloat(cle, defaut.toFloat()).toDouble()

    companion object {
        private const val FICHIER = "arbitre"
    }
}
