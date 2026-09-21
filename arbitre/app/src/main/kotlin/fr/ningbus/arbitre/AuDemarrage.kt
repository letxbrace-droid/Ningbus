package fr.ningbus.arbitre

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Relance la veille après un redémarrage du téléphone.
 *
 * `BOOT_COMPLETED` est l'un des rares moments où une application a le droit
 * de démarrer un service au premier plan sans être elle-même au premier plan.
 * C'est aussi celui où l'on en a le plus besoin : un redémarrage remet à zéro
 * toutes les exceptions accordées en cours de session.
 */
class AuDemarrage : BroadcastReceiver() {

    override fun onReceive(contexte: Context, intention: Intent) {
        if (intention.action != Intent.ACTION_BOOT_COMPLETED) return
        ServiceVeille.synchroniser(contexte)
    }
}
