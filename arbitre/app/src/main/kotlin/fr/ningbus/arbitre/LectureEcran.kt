package fr.ningbus.arbitre

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import fr.ningbus.arbitre.moteur.Course
import fr.ningbus.arbitre.moteur.completer

/**
 * Lecture de la carte d'offre affichée par l'application chauffeur.
 *
 * C'est le chemin principal, et non un complément : quand l'application est
 * au premier plan — c'est-à-dire tout le temps où l'on travaille — Uber
 * dessine l'offre directement à l'écran **sans poster aucune notification**.
 *
 * Trois façons de déclencher la lecture :
 *
 *  - **automatiquement**, sur les changements d'écran ;
 *  - **à la demande**, par un appui sur la pastille flottante ;
 *  - **par capture**, par un appui long sur la pastille : le texte brut de
 *    l'écran part dans le journal, sans interprétation. C'est le seul moyen
 *    de savoir ce que le service a réellement vu quand une offre échappe à
 *    l'analyse — deviner à sa place coûte un aller-retour à chaque fois.
 */
class LectureEcran : AccessibilityService() {

    private val principal = Handler(Looper.getMainLooper())
    private val devoiler = Runnable { devoilerIncomplet() }

    private var derniereLecture = 0L

    /** Lecture partielle en cours de complétion. */
    private var attente: Attente? = null

    private class Attente(
        val paquet: String,
        val course: Course,
        val instant: Long,
        /** Un bouton d'acceptation était visible : c'était bien une offre. */
        val marquee: Boolean,
    )

    override fun onServiceConnected() {
        instance = this
        appliquerFiltre()
        BoutonFlottant.synchroniser(this)
        Log.i(TAG, "lecture d'écran active")
    }

    override fun onDestroy() {
        instance = null
        BoutonFlottant.cacher()
        super.onDestroy()
    }

    /**
     * Restreint l'écoute aux applications cochées, ou l'ouvre à toutes.
     *
     * À rappeler quand l'utilisateur modifie la liste : le filtre étant
     * appliqué par le système, une application qui vient d'être cochée
     * n'enverrait sinon jamais le premier événement qui permettrait de s'en
     * apercevoir.
     */
    fun appliquerFiltre() {
        val info = serviceInfo ?: return
        val reglages = Reglages(this)
        info.packageNames = when {
            // null veut dire « toutes les applications ».
            reglages.ecouteToutesApps -> null
            // Un tableau vide voudrait dire « toutes » aussi : on préfère
            // n'écouter rien plutôt que tout par accident.
            reglages.paquets.isEmpty() -> arrayOf("")
            else -> reglages.paquets.toTypedArray()
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val paquet = e.packageName?.toString() ?: return
        if (paquet == packageName) return // nos propres fenêtres

        // Le contenu d'une carte d'offre change à chaque seconde du compte à
        // rebours. Sans ce pas minimal, on relirait tout l'arbre des vues
        // dizaines de fois par seconde pour un résultat identique.
        val maintenant = SystemClock.elapsedRealtime()
        if (maintenant - derniereLecture < PAS_MINIMAL_MS) return
        derniereLecture = maintenant

        try {
            lire(paquet, e.eventTime, force = false)
        } catch (ex: Exception) {
            // Un arbre de vues qui disparaît en cours de parcours ne doit pas
            // faire tomber le service : le système ne le relierait qu'au
            // prochain passage par les réglages d'accessibilité.
            Log.w(TAG, "écran illisible : ${ex.message}")
        }
    }

    override fun onInterrupt() = Unit

    // --- Lecture ------------------------------------------------------------

    /**
     * @param force analyse demandée par l'utilisateur : ni filtre d'écran, ni
     *   dédoublonnage — s'il appuie deux fois, il veut deux réponses.
     */
    private fun lire(paquet: String, instantEvenement: Long, force: Boolean): Boolean {
        val reglages = Reglages(this)
        if (!reglages.actif) return false
        if (!force && !reglages.ecoute(paquet)) return false

        // Une analyse demandée à la main ne sait pas de quelle application
        // vient l'écran : elle lit tout. Une analyse automatique, elle, sait,
        // et ne doit lire que les fenêtres de l'émettrice — sans quoi un
        // événement d'Uber fait lire la fenêtre d'une autre application et
        // arbitre son texte sous le nom d'Uber.
        val texte = texteEcran(if (force) "" else paquet)
        if (texte.isEmpty()) {
            if (force) signaler(R.string.rien_a_lire)
            return false
        }

        // Une lecture qui en complète une autre n'a pas à reporter de montant :
        // c'est précisément la partie qui manquait la fois d'avant.
        val enCours = attente?.takeIf { it.paquet == paquet }
        if (!Arbitrage.ressembleAUneCourse(texte) && enCours == null) {
            if (force) {
                Journal.signalerCapture(this, paquet, texte)
                signaler(R.string.rien_a_lire)
            }
            return false
        }

        val course = Arbitrage.lire(paquet, texte).completer(enCours?.course)

        if (!force && enCours == null && reglages.filtrerEcrans && !estUneOffre(texte, course)) {
            // Écran portant un montant mais écarté : on le note pour pouvoir
            // élargir les marqueurs si des courses passent à travers.
            if (course.prix != null) Journal.signalerEcranIgnore(this, paquet, texte)
            return false
        }

        // L'instant de référence est celui de la première lecture partielle :
        // la latence affichée reste le délai vécu depuis l'apparition de
        // l'offre, pas depuis la lecture qui a fini de la compléter.
        val debut = enCours?.instant ?: instantEvenement

        // Un écran ne se dessine pas d'un bloc. Une lecture incomplète est
        // bien plus souvent une carte à moitié construite qu'une carte
        // illisible : on laisse sa chance à la suivante plutôt que de rendre
        // un verdict creux sur ce qu'on a vu au millième de seconde près.
        if (!force && !course.exploitable) {
            if (course.prix != null || course.kmTrajet != null || course.minutesTrajet != null) {
                attente = Attente(paquet, course, debut, marqueur(texte))
                principal.removeCallbacks(devoiler)
                principal.postDelayed(devoiler, DELAI_INCOMPLET_MS)
            }
            return false
        }

        principal.removeCallbacks(devoiler)
        attente = null

        val latence = (SystemClock.uptimeMillis() - debut).coerceAtLeast(0L)
        val rendu = Arbitrage.rendre(this, paquet, course, Source.ECRAN, latence, force)
        if (force && !rendu) {
            Journal.signalerCapture(this, paquet, texte)
            signaler(R.string.montant_introuvable)
        }
        return rendu
    }

    /**
     * Le délai est écoulé et la lecture n'a jamais été complétée : la carte a
     * fini de se dessiner, ce qui manque manque vraiment. Mieux vaut le dire
     * que se taire — un « INCOMPLET » invite à décider à la main, un silence
     * laisse croire qu'il n'y avait rien à arbitrer. Le texte part au journal
     * en même temps, puisque c'est un cas où l'analyse a échoué.
     */
    private fun devoilerIncomplet() {
        val a = attente ?: return
        attente = null

        // Le texte est gardé dans tous les cas : c'est la matière du
        // diagnostic.
        Journal.signalerCapture(this, a.paquet, a.course.texteBrut)

        // Mais on ne dérange le chauffeur que si l'écran portait un bouton
        // d'acceptation. Un écran de réglages qui mentionne « €1.00/km » n'est
        // pas une offre illisible : ce n'est pas une offre. Faire surgir un
        // « INCOMPLET » dessus revient à crier au loup.
        if (!a.marquee) return

        val latence = (SystemClock.uptimeMillis() - a.instant).coerceAtLeast(0L)
        Arbitrage.rendre(this, a.paquet, a.course, Source.ECRAN, latence)
    }

    // --- Ce qui est réellement à l'écran ------------------------------------

    /**
     * Le texte de **toutes** les fenêtres affichées, pas seulement de la
     * fenêtre active.
     *
     * C'est la correction décisive. `rootInActiveWindow` ne rend que la
     * fenêtre qui a le focus, et une offre de course n'y est presque jamais :
     * les applications chauffeur l'affichent dans une fenêtre flottante
     * par-dessus l'accueil ou par-dessus une autre application, et le focus
     * reste à celle du dessous. On lisait donc consciencieusement le mauvais
     * écran — d'où un prix attrapé au hasard et aucune distance.
     *
     * Les fenêtres de l'application qui a émis l'événement sont préférées
     * quand il y en a ; sinon on prend tout ce qui n'est pas à nous, car une
     * offre vaut mieux lue avec du bruit autour que pas lue du tout.
     */
    private fun texteEcran(paquet: String): String {
        val racines = racines(paquet)
        if (racines.isEmpty()) return ""

        val morceaux = LinkedHashSet<String>(64)
        var budget = NOEUDS_MAX
        for (racine in racines) {
            budget = ramasser(racine, morceaux, budget)
            if (budget <= 0) break
        }
        return morceaux.joinToString("\n")
    }

    private fun racines(paquet: String): List<AccessibilityNodeInfo> {
        val toutes = ArrayList<AccessibilityNodeInfo>(4)
        val tout = paquet.isEmpty()

        // De la fenêtre la plus en avant vers la plus en arrière : une offre
        // posée par-dessus le reste se lit d'abord.
        try {
            for (fenetre in windows.sortedByDescending { it.layer }) {
                val racine = fenetre.root ?: continue
                if (racine.packageName == packageName) continue
                toutes += racine
            }
        } catch (e: Exception) {
            // Certaines surcouches refusent la liste des fenêtres.
        }

        rootInActiveWindow?.let { active ->
            if (active.packageName != packageName && toutes.none { it == active }) {
                toutes += active
            }
        }

        // Pas de repli sur « toutes les fenêtres » quand une application est
        // nommée : si la sienne n'est pas lisible, il n'y a rien à lire. Lire
        // celle d'à côté produirait un verdict sur le texte d'une autre
        // application, attribué à celle-ci.
        return if (tout) toutes else toutes.filter { it.packageName == paquet }
    }

    /**
     * Ramasse les textes d'un arbre de vues, en ordre de lecture.
     *
     * L'ordre compte : quand aucun mot ne désigne l'approche, c'est la
     * position qui tranche, une offre annonçant toujours l'approche avant la
     * course. Le parcours est donc en profondeur d'abord, et borné pour ne
     * jamais peser sur l'application du dessous.
     *
     * @return le budget de nœuds restant
     */
    private fun ramasser(
        racine: AccessibilityNodeInfo,
        morceaux: MutableSet<String>,
        budgetInitial: Int,
    ): Int {
        var budget = budgetInitial
        val pile = ArrayDeque<AccessibilityNodeInfo>()
        pile.addLast(racine)

        while (pile.isNotEmpty() && budget > 0) {
            val noeud = pile.removeLast()
            budget--
            noeud.text?.toString()?.trim()?.let { if (it.isNotEmpty()) morceaux += it }
            noeud.contentDescription?.toString()?.trim()?.let { if (it.isNotEmpty()) morceaux += it }
            for (i in noeud.childCount - 1 downTo 0) {
                noeud.getChild(i)?.let { pile.addLast(it) }
            }
        }
        return budget
    }

    /**
     * Distingue une offre d'un écran de navigation, qui affiche lui aussi un
     * prix et des kilomètres.
     *
     * Deux signes concordants, l'un textuel et l'autre structurel : le bouton
     * d'acceptation, ou bien deux distances distinctes — une offre annonce
     * toujours l'approche *et* la course, une navigation une seule.
     */
    private fun estUneOffre(texte: String, course: Course): Boolean =
        marqueur(texte) || (course.kmApproche != null && course.kmTrajet != null)

    /** Le bouton qui accepte la course est visible à l'écran. */
    private fun marqueur(texte: String): Boolean {
        val minuscules = texte.lowercase()
        return MARQUEURS.any { minuscules.contains(it) }
    }

    private fun signaler(message: Int) {
        principal.post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun signaler(message: String) {
        principal.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    companion object {
        private const val TAG = "Arbitre"

        /** Pas minimal entre deux lectures de l'arbre des vues. */
        private const val PAS_MINIMAL_MS = 400L

        /**
         * Borne de parcours, tous écrans confondus. Généreuse à dessein :
         * l'écran d'une application chauffeur porte une carte entière, dont
         * les milliers de nœuds précèdent la carte d'offre dans l'arbre des
         * vues. Une borne trop basse épuise le budget avant d'atteindre les
         * lignes du trajet, et l'offre paraît illisible alors qu'elle est
         * simplement plus loin.
         */
        private const val NOEUDS_MAX = 3000

        /** Attente maximale avant de rendre un verdict sur données partielles. */
        private const val DELAI_INCOMPLET_MS = 2500L

        /** Textes du bouton qui accepte la course, selon les plateformes. */
        private val MARQUEURS = listOf(
            "mise en relation", "accepter", "accept", "j'accepte",
            "correspondre", "prendre la course",
        )

        @Volatile
        private var instance: LectureEcran? = null

        /** Vrai quand le système a réellement lié le service, pas seulement autorisé. */
        val lie: Boolean
            get() = instance != null

        /** À appeler après une modification de la liste des applications. */
        fun rafraichirFiltre() {
            instance?.appliquerFiltre()
        }

        /**
         * Analyse l'écran courant à la demande. Le chemin de secours : il ne
         * dépend ni du nom du paquet, ni de la forme de l'écran.
         */
        fun analyserMaintenant(contexte: Context) {
            val service = instance ?: return refuser(contexte)
            try {
                service.lire(paquetDevant(service), SystemClock.uptimeMillis(), force = true)
            } catch (e: Exception) {
                Log.w(TAG, "analyse manuelle impossible : ${e.message}")
            }
        }

        /**
         * Relit l'écran après un délai.
         *
         * Sert quand une notification annonce une course : la carte
         * correspondante est en train d'apparaître, et le texte de la
         * notification est souvent plus pauvre que celui de l'écran.
         */
        fun analyserApres(delaiMs: Long) {
            val service = instance ?: return
            service.principal.postDelayed({
                try {
                    service.lire(paquetDevant(service), SystemClock.uptimeMillis(), force = false)
                } catch (e: Exception) {
                    Log.w(TAG, "relecture impossible : ${e.message}")
                }
            }, delaiMs)
        }

        /**
         * Envoie le texte brut de l'écran au journal, sans l'interpréter.
         *
         * Quand une offre échappe à l'analyse, c'est la seule donnée qui
         * permette de comprendre pourquoi plutôt que de supposer.
         */
        fun capturer(contexte: Context) {
            val service = instance ?: return refuser(contexte)
            try {
                val paquet = paquetDevant(service)
                val texte = service.texteEcran(paquet)
                Journal.signalerCapture(contexte, paquet, texte, force = true)
                service.signaler(
                    contexte.getString(R.string.ecran_capture, paquet, texte.length)
                )
            } catch (e: Exception) {
                Log.w(TAG, "capture impossible : ${e.message}")
            }
        }

        /** Le paquet de la fenêtre la plus en avant qui ne soit pas la nôtre. */
        private fun paquetDevant(service: LectureEcran): String {
            service.rootInActiveWindow?.packageName?.toString()?.let {
                if (it != service.packageName) return it
            }
            return service.racines("").firstOrNull()?.packageName?.toString() ?: "écran"
        }

        private fun refuser(contexte: Context) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    contexte.applicationContext,
                    R.string.lecture_ecran_requise,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
