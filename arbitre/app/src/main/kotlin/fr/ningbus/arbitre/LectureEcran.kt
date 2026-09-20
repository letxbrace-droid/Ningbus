package fr.ningbus.arbitre

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import fr.ningbus.arbitre.moteur.Course

/**
 * Lecture de la carte d'offre affichée dans l'application chauffeur.
 *
 * C'est le chemin principal, et non un complément : quand l'application est
 * au premier plan — c'est-à-dire tout le temps où l'on travaille — Uber
 * dessine l'offre directement à l'écran **sans poster aucune notification**.
 *
 * Deux façons de déclencher la lecture :
 *
 *  - **automatiquement**, sur les changements d'écran ;
 *  - **à la demande**, par la pastille flottante. Ce chemin-là ne dépend
 *    d'aucune heuristique : ni du nom du paquet, ni de la forme de l'écran,
 *    ni d'un mot dans un bouton. Quand la détection automatique se trompe,
 *    elle se trompe en silence — un appui, lui, répond toujours.
 */
class LectureEcran : AccessibilityService() {

    private var derniereLecture = 0L

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

        val racine = fenetreCourante() ?: return false
        val texte = texteDe(racine)
        if (!Arbitrage.ressembleAUneCourse(texte)) {
            if (force) signaler(R.string.rien_a_lire)
            return false
        }

        val course = Arbitrage.lire(paquet, texte)
        if (!force && reglages.filtrerEcrans && !estUneOffre(texte, course)) {
            // Écran portant un montant mais écarté : on le note pour pouvoir
            // élargir les marqueurs si des courses passent à travers.
            if (course.prix != null) Journal.signalerEcranIgnore(this, paquet, texte)
            return false
        }

        // eventTime est exprimé dans le temps d'activité du système, comme
        // uptimeMillis : l'écart mesure le délai réellement vécu.
        val latence = (SystemClock.uptimeMillis() - instantEvenement).coerceAtLeast(0L)
        val rendu = Arbitrage.rendre(this, paquet, course, Source.ECRAN, latence, force)
        if (force && !rendu) {
            Journal.signalerEcranIgnore(this, paquet, texte)
            signaler(R.string.montant_introuvable)
        }
        return rendu
    }

    /**
     * La fenêtre à lire.
     *
     * `rootInActiveWindow` suffit presque toujours. Mais la pastille est une
     * fenêtre de superposition : selon les constructeurs, un appui dessus peut
     * déplacer la fenêtre active. On retombe alors sur la fenêtre
     * d'application la plus en avant, qui est celle que le chauffeur regarde.
     */
    private fun fenetreCourante(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { if (it.packageName != packageName) return it }
        return try {
            windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedByDescending { it.layer }
                .firstNotNullOfOrNull { fenetre ->
                    fenetre.root?.takeIf { it.packageName != packageName }
                }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Distingue une offre d'un écran de navigation, qui affiche lui aussi un
     * prix et des kilomètres.
     *
     * Deux signes concordants, l'un textuel et l'autre structurel : le bouton
     * d'acceptation, ou bien deux distances distinctes — une offre annonce
     * toujours l'approche *et* la course, une navigation une seule.
     */
    private fun estUneOffre(texte: String, course: Course): Boolean {
        val minuscules = texte.lowercase()
        return MARQUEURS.any { minuscules.contains(it) } ||
            (course.kmApproche != null && course.kmTrajet != null)
    }

    /**
     * Ramasse les textes de l'arbre des vues, en ordre de lecture.
     *
     * L'ordre compte : quand aucun mot ne désigne l'approche, c'est la
     * position qui tranche, une offre annonçant toujours l'approche avant la
     * course. Le parcours est donc en profondeur d'abord, et borné pour ne
     * jamais peser sur l'application du dessous.
     */
    private fun texteDe(racine: AccessibilityNodeInfo): String {
        val morceaux = ArrayList<String>(48)
        val pile = ArrayDeque<AccessibilityNodeInfo>()
        pile.addLast(racine)
        var vus = 0

        while (pile.isNotEmpty() && vus < NOEUDS_MAX) {
            val noeud = pile.removeLast()
            vus++
            noeud.text?.toString()?.trim()?.let { if (it.isNotEmpty()) morceaux += it }
            noeud.contentDescription?.toString()?.trim()?.let { if (it.isNotEmpty()) morceaux += it }
            for (i in noeud.childCount - 1 downTo 0) {
                noeud.getChild(i)?.let { pile.addLast(it) }
            }
        }
        return morceaux.distinct().joinToString("\n")
    }

    private fun signaler(message: Int) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "Arbitre"

        /** Pas minimal entre deux lectures de l'arbre des vues. */
        private const val PAS_MINIMAL_MS = 400L

        /** Borne de parcours : une carte d'offre en compte quelques dizaines. */
        private const val NOEUDS_MAX = 600

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
            val service = instance
            if (service == null) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        contexte.applicationContext,
                        R.string.lecture_ecran_requise,
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return
            }
            try {
                val paquet = service.rootInActiveWindow?.packageName?.toString() ?: "écran"
                service.lire(paquet, SystemClock.uptimeMillis(), force = true)
            } catch (e: Exception) {
                Log.w(TAG, "analyse manuelle impossible : ${e.message}")
            }
        }
    }
}
