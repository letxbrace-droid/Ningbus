package fr.ningbus.arbitre

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
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
        val schema = p.getInt(SCHEMA, 0)
        if (schema >= SCHEMA_COURANT) return
        val edition = p.edit().putInt(SCHEMA, SCHEMA_COURANT)

        if (schema < 2) {
            // Écouter toutes les applications faisait surgir des bulles sur
            // l'écran d'accueil et les pages web. La liste d'origine s'étant
            // révélée exacte, ce mode redevient l'exception.
            edition.putBoolean("toutesApps", false)
        }

        if (schema < 3) {
            // Le coût de roulage se règle désormais en trois postes. Celui
            // qui avait choisi 0,18 €/km doit retrouver 0,18 €/km : on répartit
            // sa valeur dans les proportions d'origine — carburant 59 %, usure
            // 23 %, fixes 18 % — au lieu de lui imposer les trois valeurs par
            // défaut. Un réglage qu'on a pris la peine de changer ne se perd
            // pas dans une mise à jour.
            val ancien = p.getFloat("coutKm", 0.22f).toDouble()
            val modele = Bareme()
            val total = modele.coutKm
            edition
                .putFloat("coutCarburant", (ancien * modele.coutCarburant / total).toFloat())
                .putFloat("coutUsure", (ancien * modele.coutUsure / total).toFloat())
                .putFloat("coutFixes", (ancien * modele.coutFixes / total).toFloat())
        }

        edition.apply()
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

    /**
     * Reconnaissance de texte en secours, quand l'arbre d'accessibilité ne
     * livre rien d'exploitable.
     *
     * Activée par défaut, et il le faut : les applications chauffeur passent
     * à Compose et au dessin sur Canvas, où le texte n'existe pas en tant que
     * nœud. Sans ce second chemin, une carte d'offre dessinée est invisible,
     * et rien dans l'application ne permet de s'en apercevoir.
     *
     * Reste désactivable, parce qu'une capture d'écran suivie d'une
     * reconnaissance coûte mille fois un parcours de nœuds : sur un téléphone
     * fatigué, on peut vouloir s'en passer.
     */
    var ocrSecours: Boolean
        get() = p.getBoolean("ocrSecours", true)
        set(v) = p.edit().putBoolean("ocrSecours", v).apply()

    /**
     * Service de veille : une notification permanente, et rien d'autre.
     *
     * Son seul effet est d'élever l'importance du processus. Une surcouche
     * constructeur qui fait le ménage en arrière-plan emporte le service
     * d'accessibilité avec le processus, et Android ne le relie pas toujours
     * ensuite — la panne « autorisé mais non lié ».
     */
    var veille: Boolean
        get() = p.getBoolean("veille", true)
        set(v) = p.edit().putBoolean("veille", v).apply()

    /**
     * La bulle s'ouvre réduite : décision et euro/heure, rien d'autre.
     *
     * Retenu d'un appui à l'autre. Celui qui replie la bulle une fois la
     * replie pour de bon, sans avoir à y repenser à chaque offre.
     */
    var modeCompact: Boolean
        get() = p.getBoolean("modeCompact", false)
        set(v) = p.edit().putBoolean("modeCompact", v).apply()

    /**
     * Le coût de roulage, détaillé sous le verdict.
     *
     * Un total de 3,30 € se croit ou ne se croit pas ; « carburant 1,30, usure
     * 1,10, fixes 0,90 » se vérifie, et se corrige là où il est faux. Coûte
     * trois lignes de bulle, donc débrayable.
     */
    var detailsCouts: Boolean
        get() = p.getBoolean("detailsCouts", true)
        set(v) = p.edit().putBoolean("detailsCouts", v).apply()

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
        get() = p.getStringSet("paquets", null)?.toSet() ?: paquetsDOrigine()
        set(v) = p.edit().putStringSet("paquets", v.toSet()).apply()

    /**
     * La liste d'origine, augmentée du simulateur **sur émulateur seulement**.
     *
     * Le banc d'essai doit éprouver la configuration réellement livrée. Chaque
     * fois qu'un essai a commencé par régler l'application à sa convenance, il
     * a fini par valider un chemin que personne n'emprunte — et la panne
     * suivante est venue de l'écart entre les deux. La fausse application
     * chauffeur est donc écoutée comme une vraie, sans qu'aucun autre réglage
     * ne bouge.
     *
     * Sur un téléphone, [surEmulateur] est faux : la liste est exactement
     * celle d'avant, au paquet près.
     */
    private fun paquetsDOrigine(): Set<String> =
        if (surEmulateur) Plateformes.PAR_DEFAUT.keys + SIMULATEUR
        else Plateformes.PAR_DEFAUT.keys.toSet()

    /**
     * Cette application peut-elle porter une offre ?
     *
     * Le mode « toutes les applications » n'excuse pas tout. Le journal du
     * 21/09 a été rendu avec ce mode allumé : sur 80 verdicts, 55 portaient
     * sur l'écran d'accueil, la barre d'état, le clavier, une boîte mail ou
     * une conversation. Aucune de ces applications n'est, ni ne sera jamais,
     * une application chauffeur — et l'application se lisait elle-même en
     * prime, un verdict en engendrant un autre.
     *
     * L'exclusion est structurelle, pas une liste de noms à tenir à jour :
     * l'interface du système, le lanceur, le clavier, et nous-mêmes.
     */
    fun ecoute(paquet: String): Boolean {
        if (estSysteme(paquet)) return false
        return ecouteToutesApps || paquet in paquets
    }

    /**
     * Une application chauffeur nommément désignée.
     *
     * Distinct de [ecoute], qui dit seulement « on peut regarder ». Ce que
     * cela autorise en plus — capturer l'écran et le passer en reconnaissance
     * d'image — ne se fait pas sur une application quelconque : une capture de
     * l'écran d'accueil, même analysée sur le téléphone et jamais conservée,
     * n'a aucune raison d'être prise. Le journal en comptait neuf, toutes hors
     * d'une application chauffeur, et aucune à l'intérieur.
     */
    fun estApplicationChauffeur(paquet: String): Boolean =
        !estSysteme(paquet) && paquet in paquets

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
            coutCarburant = lire("coutCarburant", 0.13),
            coutUsure = lire("coutUsure", 0.05),
            coutFixes = lire("coutFixes", 0.04),
            plancherEuroKm = lire("plancherEuroKm", 0.0),
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
                .putFloat("coutCarburant", b.coutCarburant.toFloat())
                .putFloat("coutUsure", b.coutUsure.toFloat())
                .putFloat("coutFixes", b.coutFixes.toFloat())
                .putFloat("plancherEuroKm", b.plancherEuroKm.toFloat())
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
        private const val SCHEMA_COURANT = 3

        /** La fausse application chauffeur du banc d'essai. */
        private const val SIMULATEUR = "fr.ningbus.simulateur"

        /**
         * Ce qui n'est jamais une application chauffeur, quel que soit le
         * réglage.
         *
         * Volontairement court : un lanceur, une interface système, un
         * clavier, et l'application elle-même. Tout le reste passe par la
         * liste que l'utilisateur tient — la fonction de cette exclusion
         * n'est pas de filtrer le monde, mais d'empêcher les quatre cas où
         * un faux positif est certain.
         */
        private fun estSysteme(paquet: String): Boolean =
            paquet == "android" ||
                paquet == "fr.ningbus.arbitre" ||
                paquet.startsWith("com.android.systemui") ||
                paquet.startsWith("com.android.settings") ||
                paquet.contains(".launcher") ||
                paquet.contains("inputmethod") ||
                paquet.contains("honeyboard")

        /**
         * Vrai sur un émulateur Android, faux sur un téléphone.
         *
         * Les images d'émulateur se reconnaissent à leur matériel simulé —
         * goldfish pour l'ancien, ranchu pour l'actuel — et à leur empreinte
         * générique. Aucun appareil du commerce ne présente ces valeurs.
         */
        private val surEmulateur: Boolean by lazy {
            Build.HARDWARE.contains("goldfish") ||
                Build.HARDWARE.contains("ranchu") ||
                Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
                Build.PRODUCT.startsWith("sdk")
        }
    }
}
