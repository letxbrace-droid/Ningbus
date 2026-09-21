package fr.ningbus.arbitre

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Lecture des offres arrivées sous forme de notification.
 *
 * C'est le chemin qui sert quand l'application chauffeur est en arrière-plan
 * ou l'écran verrouillé. Quand elle est au premier plan, Uber dessine sa
 * carte d'offre sans rien notifier : c'est alors [LectureEcran] qui voit
 * l'offre. Les deux services partagent [Arbitrage], donc une course vue par
 * les deux chemins ne produit qu'une bulle.
 *
 * Rien sur ce chemin n'attend : pas de réseau, pas de base de données, pas de
 * service à démarrer. Entre l'arrivée de la notification et la bulle il n'y a
 * qu'une lecture de chaîne, quelques expressions régulières et une trentaine
 * d'opérations flottantes.
 */
class EcouteNotifications : NotificationListenerService() {

    override fun onListenerConnected() {
        lie = true
        Log.i(TAG, "écoute des notifications active")
    }

    override fun onListenerDisconnected() {
        // Android délie parfois le service sans prévenir l'utilisateur, qui
        // voit alors une permission « accordée » et rien qui fonctionne.
        // L'écran d'accueil montre cet état-là séparément.
        lie = false
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
        if (paquet == packageName) return // nos propres notifications de repli

        // Tracé avant tout filtrage : c'est ce qui permet de distinguer
        // « la notification n'est jamais arrivée » de « elle est arrivée mais
        // n'a pas été retenue », et de lire le nom de paquet exact.
        // Le filet est large à dessein : une notification de course dont le
        // montant est dans une vue personnalisée n'arrive ici qu'en
        // « Nouvelle course ». La retenir quand même est ce qui permet de
        // voir, après coup, ce que la plateforme envoie vraiment.
        val interessante = Arbitrage.pourraitEtreUneCourse(texte)
        if (interessante) Journal.signalerVue(this, paquet, texte)

        if (!reglages.ecoute(paquet)) {
            if (reglages.modeDecouverte && interessante) Journal.signalerInconnu(this, paquet)
            return
        }
        if (!interessante) return

        // postTime est l'horodatage d'affichage par le système : la
        // différence mesure bien le délai vu par le chauffeur.
        val latence = (System.currentTimeMillis() - sbn.postTime).coerceAtLeast(0L)
        val rendu = Arbitrage.rendre(
            this, paquet, Arbitrage.lire(paquet, texte), Source.NOTIFICATION, latence,
        )

        // La notification n'a pas suffi : la carte correspondante est en train
        // d'apparaître à l'écran, et elle, elle porte tous les chiffres. La
        // notification sert alors de déclencheur, l'écran de source.
        if (!rendu) LectureEcran.analyserApres(DELAI_RELECTURE_MS)
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

    companion object {
        private const val TAG = "Arbitre"

        /** Temps laissé à la carte d'offre pour se dessiner après la notification. */
        private const val DELAI_RELECTURE_MS = 700L

        /**
         * Vrai quand le système a réellement lié le service. Une permission
         * accordée ne suffit pas : Android délie parfois l'écoute, et rien
         * dans les réglages système ne le montre.
         */
        @Volatile
        var lie: Boolean = false
            private set
    }
}
