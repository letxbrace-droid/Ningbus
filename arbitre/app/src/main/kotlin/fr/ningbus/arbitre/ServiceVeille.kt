package fr.ningbus.arbitre

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Le service qui ne fait rien, et c'est tout son intérêt.
 *
 * Un service d'accessibilité est lié par le système, pas démarré par
 * l'application : Android ne le met pas en veille et le Doze ne l'atteint
 * pas. La croyance répandue selon laquelle il faudrait « le réveiller » est
 * donc fausse sur un Android de référence.
 *
 * Elle ne l'est pas sur les surcouches. MIUI, EMUI, ColorOS, One UI et
 * quelques autres arrêtent des **processus** entiers selon leurs propres
 * règles, et le service d'accessibilité s'en va avec le sien. Le système le
 * relie parfois ensuite, parfois pas — c'est la panne « autorisé mais non
 * lié », où tout paraît en ordre et où rien ne se produit.
 *
 * Deux remèdes, tous deux réels :
 *
 *  - **un service au premier plan** élève l'importance du processus. Ce n'est
 *    pas une garantie, aucune n'existe, mais c'est le seul levier qu'une
 *    application possède ;
 *  - **une surveillance** qui vérifie la liaison et le dit. L'application ne
 *    peut pas se relier elle-même — seul le système le fait, et l'y forcer
 *    demanderait `WRITE_SECURE_SETTINGS`, que nul n'accorde à une application
 *    installée de côté. Elle peut en revanche cesser d'être muette, ce qui
 *    transforme une panne inexplicable en une notification qui mène droit au
 *    réglage à recocher.
 */
class ServiceVeille : Service() {

    private val principal = Handler(Looper.getMainLooper())
    private val battement = object : Runnable {
        override fun run() {
            verifier()
            principal.postDelayed(this, PERIODE_MS)
        }
    }

    /** Pour ne pas répéter l'alerte à chaque battement. */
    private var alerteDonnee = false

    override fun onCreate() {
        super.onCreate()
        creerCanaux(this)
        demarrerAuPremierPlan()
        principal.postDelayed(battement, PERIODE_MS)
    }

    override fun onStartCommand(intent: Intent?, drapeaux: Int, identifiant: Int): Int {
        demarrerAuPremierPlan()
        // Redémarré par le système s'il nous arrête : c'est exactement ce
        // qu'on lui demande.
        return START_STICKY
    }

    override fun onDestroy() {
        principal.removeCallbacks(battement)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun demarrerAuPremierPlan() {
        try {
            val notification = etatCourant()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    ID_VEILLE,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(ID_VEILLE, notification)
            }
        } catch (e: Exception) {
            // Depuis Android 12, un service au premier plan ne peut pas être
            // démarré depuis l'arrière-plan. Ce n'est pas fatal : la veille
            // est un renfort, pas le mécanisme principal.
            Log.w(TAG, "veille impossible à démarrer : ${e.message}")
            stopSelf()
        }
    }

    /**
     * Le battement : la lecture d'écran est-elle toujours liée ?
     *
     * Quand elle ne l'est plus alors que l'utilisateur l'a autorisée, il n'y
     * a rien à réparer depuis le code — mais tout à dire.
     */
    private fun verifier() {
        val reglages = Reglages(this)
        if (!reglages.actif) return

        val autorisee = lectureEcranAutorisee(this)
        val liee = LectureEcran.lie

        if (autorisee && !liee) {
            if (!alerteDonnee) {
                alerteDonnee = true
                alerterDetachement()
            }
        } else {
            alerteDonnee = false
        }
        majEtat()
    }

    private fun majEtat() {
        try {
            gestionnaire().notify(ID_VEILLE, etatCourant())
        } catch (e: Exception) {
            // Notifications refusées : la veille garde son utilité première,
            // qui est de maintenir le processus.
        }
    }

    private fun etatCourant(): Notification {
        val liee = LectureEcran.lie
        return NotificationCompat.Builder(this, CANAL_VEILLE)
            .setSmallIcon(R.drawable.ic_bulle)
            .setContentTitle(getString(R.string.veille_titre))
            .setContentText(
                getString(if (liee) R.string.veille_active else R.string.veille_detachee)
            )
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(versAccueil())
            .build()
    }

    private fun alerterDetachement() {
        Log.w(TAG, "lecture d'écran détachée alors qu'elle est autorisée")
        val alerte = NotificationCompat.Builder(this, CANAL_ALERTE)
            .setSmallIcon(R.drawable.ic_bulle)
            .setContentTitle(getString(R.string.detachement_titre))
            .setContentText(getString(R.string.detachement_texte))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(getString(R.string.detachement_texte))
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(versAccueil())
            .build()
        try {
            gestionnaire().notify(ID_ALERTE, alerte)
        } catch (e: Exception) {
            // Notifications refusées.
        }
    }

    private fun versAccueil(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, ActivitePrincipale::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun gestionnaire(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val TAG = "Arbitre"

        private const val CANAL_VEILLE = "veille"
        private const val CANAL_ALERTE = "detachement"
        private const val ID_VEILLE = 4201
        private const val ID_ALERTE = 4202

        /** Une minute : assez pour prévenir vite, trop peu pour peser. */
        private const val PERIODE_MS = 60_000L

        /**
         * Démarre ou arrête la veille selon le réglage.
         *
         * Appelée depuis l'écran d'accueil, au démarrage du téléphone et à la
         * liaison du service d'accessibilité — trois moments où l'application
         * a le droit de démarrer un service au premier plan.
         */
        fun synchroniser(contexte: Context) {
            val app = contexte.applicationContext
            val reglages = Reglages(app)
            val intention = Intent(app, ServiceVeille::class.java)
            try {
                if (reglages.actif && reglages.veille) {
                    ContextCompat.startForegroundService(app, intention)
                } else {
                    app.stopService(intention)
                }
            } catch (e: Exception) {
                Log.w(TAG, "veille non synchronisée : ${e.message}")
            }
        }

        /** Le service d'accessibilité figure-t-il parmi ceux qu'on a autorisés ? */
        fun lectureEcranAutorisee(contexte: Context): Boolean = try {
            val actifs = android.provider.Settings.Secure.getString(
                contexte.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            actifs.contains("${contexte.packageName}/${LectureEcran::class.java.name}")
        } catch (e: Exception) {
            false
        }

        fun creerCanaux(contexte: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val gestionnaire = contexte
                .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Importance minimale : la notification doit exister sans jamais
            // se faire remarquer. Elle est le prix du maintien en vie, pas un
            // message.
            gestionnaire.createNotificationChannel(
                NotificationChannel(
                    CANAL_VEILLE,
                    contexte.getString(R.string.canal_veille),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply { setShowBadge(false) }
            )
            gestionnaire.createNotificationChannel(
                NotificationChannel(
                    CANAL_ALERTE,
                    contexte.getString(R.string.canal_detachement),
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }
    }
}
