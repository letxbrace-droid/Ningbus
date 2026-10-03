package fr.ningbus.arbitre

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import fr.ningbus.arbitre.moteur.Verdict
import fr.ningbus.arbitre.moteur.fmt0

/**
 * Le verdict quand la bulle ne peut pas s'afficher — permission de
 * superposition retirée, écran verrouillé, fenêtre refusée par le
 * constructeur. Une notification vaut mieux qu'un silence : le chauffeur
 * saura au moins que l'arbitrage a eu lieu.
 */
object Repli {

    private const val CANAL = "verdicts"
    private const val ID = 4201

    @SuppressLint("MissingPermission")
    fun notifier(contexte: Context, verdict: Verdict) {
        val gestionnaire =
            contexte.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        gestionnaire.createNotificationChannel(
            NotificationChannel(
                CANAL,
                contexte.getString(R.string.canal_verdicts),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )

        val titre = verdict.euroHeure
            ?.let { "${verdict.decision.libelle} · ${fmt0(it)} €/h" }
            ?: verdict.decision.libelle

        val notification = NotificationCompat.Builder(contexte, CANAL)
            .setSmallIcon(R.drawable.ic_bulle)
            .setContentTitle(titre)
            .setContentText(verdict.resume)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(contexte).notify(ID, notification)
        } catch (e: SecurityException) {
            // Permission de notification refusée elle aussi : plus rien à tenter.
        }
    }
}
