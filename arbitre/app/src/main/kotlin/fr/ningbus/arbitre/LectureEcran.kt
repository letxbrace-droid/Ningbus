package fr.ningbus.arbitre

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import fr.ningbus.arbitre.moteur.Course

/**
 * Lecture de la carte d'offre affichée dans l'application chauffeur.
 *
 * C'est le chemin principal, et non un complément : quand l'application est
 * au premier plan — c'est-à-dire tout le temps où l'on travaille — Uber
 * dessine l'offre directement à l'écran **sans poster aucune notification**.
 * Un service d'écoute des notifications ne voit alors strictement rien.
 *
 * Le service ne reçoit d'événements que des applications cochées : le filtre
 * est posé dans [AccessibilityService.setServiceInfo], donc appliqué par le
 * système lui-même. Rien du reste du téléphone ne transite par ce code.
 */
class LectureEcran : AccessibilityService() {

    private var derniereLecture = 0L

    override fun onServiceConnected() {
        instance = this
        appliquerFiltre()
        Log.i(TAG, "lecture d'écran active")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * Restreint l'écoute aux applications chauffeur cochées.
     *
     * À rappeler quand l'utilisateur modifie la liste : le filtre étant
     * appliqué par le système, une application qui vient d'être cochée
     * n'enverrait sinon jamais le premier événement qui permettrait de s'en
     * apercevoir.
     */
    fun appliquerFiltre() {
        val info = serviceInfo ?: return
        val paquets = Reglages(this).paquets
        // Un tableau vide voudrait dire « aucune application » ; null veut
        // dire « toutes ». On préfère ne rien écouter qu'écouter tout.
        info.packageNames = if (paquets.isEmpty()) arrayOf("") else paquets.toTypedArray()
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val paquet = e.packageName?.toString() ?: return

        // Le contenu d'une carte d'offre change à chaque seconde du compte à
        // rebours. Sans ce pas minimal, on relirait tout l'arbre des vues
        // dizaines de fois par seconde pour un résultat identique.
        val maintenant = SystemClock.elapsedRealtime()
        if (maintenant - derniereLecture < PAS_MINIMAL_MS) return
        derniereLecture = maintenant

        try {
            lire(paquet, e.eventTime)
        } catch (ex: Exception) {
            // Un arbre de vues qui disparaît en cours de parcours ne doit pas
            // faire tomber le service : le système ne le relierait qu'au
            // prochain passage par les réglages d'accessibilité.
            Log.w(TAG, "écran illisible : ${ex.message}")
        }
    }

    override fun onInterrupt() = Unit

    private fun lire(paquet: String, instantEvenement: Long) {
        val reglages = Reglages(this)
        if (!reglages.actif || !reglages.ecoute(paquet)) return

        val racine = rootInActiveWindow ?: return
        val texte = texteDe(racine)
        if (!Arbitrage.ressembleAUneCourse(texte)) return

        val course = Arbitrage.lire(paquet, texte)
        if (reglages.filtrerEcrans && !estUneOffre(texte, course)) {
            // Écran portant un montant mais écarté : on le note pour pouvoir
            // élargir les marqueurs si des courses passent à travers.
            if (course.prix != null) Journal.signalerEcranIgnore(this, paquet, texte)
            return
        }

        // eventTime est exprimé dans le temps d'activité du système, comme
        // uptimeMillis : l'écart mesure le délai réellement vécu.
        val latence = (SystemClock.uptimeMillis() - instantEvenement).coerceAtLeast(0L)
        Arbitrage.rendre(this, paquet, course, Source.ECRAN, latence)
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

    companion object {
        private const val TAG = "Arbitre"

        /** Pas minimal entre deux lectures de l'arbre des vues. */
        private const val PAS_MINIMAL_MS = 250L

        /** Borne de parcours : une carte d'offre en compte quelques dizaines. */
        private const val NOEUDS_MAX = 600

        /** Textes du bouton qui accepte la course, selon les plateformes. */
        private val MARQUEURS = listOf(
            "mise en relation", "accepter", "accept", "j'accepte",
            "correspondre", "prendre la course",
        )

        @Volatile
        private var instance: LectureEcran? = null

        /** À appeler après une modification de la liste des applications. */
        fun rafraichirFiltre() {
            instance?.appliquerFiltre()
        }
    }
}
