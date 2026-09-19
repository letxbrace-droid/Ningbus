package fr.ningbus.arbitre

import android.app.Notification
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import fr.ningbus.arbitre.moteur.Analyseur
import fr.ningbus.arbitre.moteur.Arbitre
import fr.ningbus.arbitre.moteur.Course
import fr.ningbus.arbitre.moteur.Plateformes

/**
 * Le point d'entrée : le système livre ici toutes les notifications du
 * téléphone, dès leur affichage.
 *
 * Il n'y a ni sondage, ni réseau, ni base de données sur ce chemin. Entre
 * l'arrivée de la notification et la bulle à l'écran il n'y a qu'une lecture
 * de chaîne, quelques expressions régulières et une trentaine
 * d'opérations flottantes : l'ordre de grandeur est la milliseconde, très
 * loin des cinq secondes demandées. La latence réelle est mesurée à chaque
 * course et affichée sur la bulle, pour qu'elle soit vérifiable et non promise.
 */
class EcouteNotifications : NotificationListenerService() {

    private var derniereSignature: String? = null
    private var dernierInstant = 0L

    override fun onListenerConnected() {
        Log.i(TAG, "écoute des notifications active")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notif = sbn ?: return
        try {
            traiter(notif)
        } catch (e: Exception) {
            // Une notification mal formée ne doit jamais faire tomber le
            // service : le système ne le relierait qu'au prochain démarrage.
            Log.w(TAG, "notification ignorée : ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // L'offre a disparu (acceptée, refusée ou expirée) : la bulle n'a
        // plus rien à arbitrer.
        val paquet = sbn?.packageName ?: return
        if (Reglages(this).ecoute(paquet)) Bulle.masquer()
    }

    private fun traiter(sbn: StatusBarNotification) {
        val reglages = Reglages(this)
        if (!reglages.actif) return

        val texte = texteDe(sbn.notification)
        if (texte.isBlank()) return

        val paquet = sbn.packageName
        if (!reglages.ecoute(paquet)) {
            if (reglages.modeDecouverte && ressembleAUneCourse(texte)) {
                Journal.signalerInconnu(this, paquet)
            }
            return
        }

        val course = Analyseur.analyser(texte, Plateformes.nom(paquet))

        // Une offre de course se réaffiche chaque seconde tant que le compte
        // à rebours tourne : le libellé change, la course non. On dédoublonne
        // donc sur les chiffres extraits, jamais sur le texte.
        val signature = signature(paquet, course)
        val maintenant = SystemClock.elapsedRealtime()
        if (signature == derniereSignature && maintenant - dernierInstant < FENETRE_DOUBLON_MS) return
        derniereSignature = signature
        dernierInstant = maintenant

        val verdict = Arbitre.arbitrer(course, reglages.bareme)

        // postTime est l'horodatage d'affichage par le système : la
        // différence mesure bien le délai vu par le chauffeur.
        val latence = (System.currentTimeMillis() - sbn.postTime).coerceAtLeast(0L)

        Journal.ajouter(this, verdict, paquet, latence)
        if (reglages.vibration) Haptique.signaler(this, verdict.decision)
        Bulle.afficher(this, verdict, latence, reglages)
    }

    /**
     * Rassemble tout ce que la notification porte comme texte. Les
     * plateformes répartissent les chiffres entre le titre, le corps et le
     * texte déplié : il faut les trois pour arbitrer.
     */
    private fun texteDe(notification: Notification?): String {
        val extras = notification?.extras ?: return ""
        val morceaux = mutableListOf<CharSequence?>(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
            extras.getCharSequence(Notification.EXTRA_INFO_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT),
        )
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { morceaux.add(it) }

        return morceaux
            .mapNotNull { it?.toString()?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")
    }

    /** Signature numérique d'une course, insensible au compte à rebours. */
    private fun signature(paquet: String, c: Course): String = listOf(
        paquet, c.prix, c.kmTrajet, c.minutesTrajet, c.kmApproche, c.minutesApproche,
    ).joinToString("|")

    /** Un montant et une distance ou une durée : c'est probablement une course. */
    private fun ressembleAUneCourse(texte: String): Boolean {
        val t = texte.lowercase()
        val montant = t.contains("€") || t.contains("eur")
        val trajet = t.contains("km") || t.contains("min")
        return montant && trajet
    }

    private companion object {
        const val TAG = "Arbitre"
        const val FENETRE_DOUBLON_MS = 45_000L
    }
}
