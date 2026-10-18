package fr.ningbus.arbitre

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import fr.ningbus.arbitre.moteur.Decision

/**
 * Le verdict au toucher.
 *
 * Trois rythmes distincts, pour que la décision passe sans quitter la route
 * des yeux : deux impulsions brèves pour prendre, une longue pour laisser.
 */
object Haptique {

    private val RYTHMES = mapOf(
        Decision.PRENDS to longArrayOf(0, 60, 80, 60),
        Decision.LIMITE to longArrayOf(0, 150),
        Decision.LAISSE to longArrayOf(0, 400),
        Decision.INCOMPLET to longArrayOf(0, 40, 60, 40, 60, 40),
    )

    fun signaler(contexte: Context, decision: Decision) {
        val vibreur = vibreur(contexte) ?: return
        if (!vibreur.hasVibrator()) return
        val rythme = RYTHMES[decision] ?: return
        try {
            vibreur.vibrate(VibrationEffect.createWaveform(rythme, -1))
        } catch (e: Exception) {
            // Certains constructeurs refusent la vibration en mode silencieux :
            // ce n'est pas une raison pour perdre le verdict à l'écran.
        }
    }

    private fun vibreur(contexte: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (contexte.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            contexte.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
