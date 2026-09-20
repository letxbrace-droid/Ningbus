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

    init {
        migrer()
    }

    /**
     * Corrige les réglages d'une version précédente.
     *
     * Changer une valeur par défaut n'atteint que les nouvelles installations :
     * un réglage déjà écrit sur le téléphone reste tel quel, et l'utilisateur
     * qui a subi le défaut continue de le subir après la mise à jour. Une
     * valeur par défaut qu'on regrette doit donc être réécrite une fois, ce
     * que ce compteur permet sans effacer ce que l'utilisateur a choisi
     * lui-même par ailleurs.
     */
    private fun migrer() {
        if (p.getInt(SCHEMA, 0) >= SCHEMA_COURANT) return
        p.edit()
            .putInt(SCHEMA, SCHEMA_COURANT)
            // Écouter toutes les applications faisait surgir des bulles sur
            // l'écran d'accueil et les pages web. La liste d'origine s'étant
            // révélée exacte, ce mode redevient l'exception.
            .putBoolean("toutesApps", false)
            .apply()
    }

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

    /**
     * N'arbitre, en lecture d'écran, que ce qui ressemble à une carte d'offre :
     * un bouton d'acceptation, ou une approche et une course distinctes. Sans
     * ce filtre, l'écran de navigation — qui affiche lui aussi un prix et des
     * kilomètres — ferait surgir des bulles en pleine conduite.
     */
    var filtrerEcrans: Boolean
        get() = p.getBoolean("filtrerEcrans", true)
        set(v) = p.edit().putBoolean("filtrerEcrans", v).apply()

    // --- Applications écoutées ---------------------------------------------

    /**
     * Écoute toutes les applications et décide sur le seul contenu.
     *
     * **Désactivé par défaut, après l'avoir été.** Le journal d'un vrai
     * téléphone a tranché deux choses : les noms de paquets de la liste
     * d'origine étaient exacts — com.ubercab.driver, com.heetch.driver,
     * ee.mtakso.driver s'y trouvaient tous — et tout écouter transforme
     * l'application en machine à faux positifs. N'importe quel texte portant
     * un prix et deux distances devient une offre : une page web, une
     * conversation, l'écran d'accueil.
     *
     * Reste disponible pour une application chauffeur absente de la liste,
     * le temps que le mode découverte en donne le nom exact.
     */
    var ecouteToutesApps: Boolean
        get() = p.getBoolean("toutesApps", false)
        set(v) = p.edit().putBoolean("toutesApps", v).apply()

    /** Pastille permanente : un appui analyse l'écran tel qu'il est. */
    var boutonFlottant: Boolean
        get() = p.getBoolean("boutonFlottant", true)
        set(v) = p.edit().putBoolean("boutonFlottant", v).apply()

    var boutonX: Int
        get() = p.getInt("boutonX", 0)
        set(v) = p.edit().putInt("boutonX", v).apply()

    var boutonY: Int
        get() = p.getInt("boutonY", 300)
        set(v) = p.edit().putInt("boutonY", v).apply()

    var paquets: Set<String>
        // Copie défensive : l'ensemble rendu par getStringSet ne doit jamais
        // être modifié en place, c'est celui que garde le cache interne.
        get() = p.getStringSet("paquets", null)?.toSet()
            ?: Plateformes.PAR_DEFAUT.keys.toSet()
        set(v) = p.edit().putStringSet("paquets", v.toSet()).apply()

    /** En mode automatique, aucune application n'est écartée d'avance. */
    fun ecoute(paquet: String): Boolean = ecouteToutesApps || paquet in paquets

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
        private const val SCHEMA = "schema"
        private const val SCHEMA_COURANT = 2
    }
}
