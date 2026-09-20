package fr.ningbus.arbitre

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/**
 * La pastille permanente : un appui, et l'écran tel qu'il est à cet instant
 * est analysé.
 *
 * C'est le seul chemin qui ne repose sur aucune devinette. La détection
 * automatique dépend de ce qu'une plateforme veut bien afficher, du nom de son
 * paquet et de la forme de ses écrans — trois choses qui changent sans
 * prévenir, et dont l'échec est silencieux. Un bouton que l'on touche, lui,
 * marche tant que la lecture d'écran est accordée.
 *
 * Elle se glisse où l'on veut et retient sa place, pour ne jamais tomber sur
 * le bouton d'acceptation de l'application chauffeur.
 */
object BoutonFlottant {

    private val principal = Handler(Looper.getMainLooper())

    private var vue: View? = null
    private var gestionnaire: WindowManager? = null

    /** Glissement minimal, en pixels, avant de considérer que l'on déplace. */
    private const val SEUIL = 12

    /** Durée d'appui à partir de laquelle on capture l'écran brut. */
    private const val APPUI_LONG_MS = 550L

    fun montrer(contexte: Context) {
        val app = contexte.applicationContext
        principal.post {
            try {
                poser(app)
            } catch (e: Exception) {
                // Superposition refusée : la pastille n'apparaît pas, le reste
                // de l'application continue de fonctionner.
            }
        }
    }

    fun cacher() {
        principal.post { retirer() }
    }

    /** Remet la pastille dans l'état voulu par les réglages. */
    fun synchroniser(contexte: Context) {
        val reglages = Reglages(contexte)
        if (reglages.boutonFlottant && reglages.actif) montrer(contexte) else cacher()
    }

    val visible: Boolean
        get() = vue != null

    private fun poser(contexte: Context) {
        if (!Settings.canDrawOverlays(contexte)) return
        if (vue != null) return

        val reglages = Reglages(contexte)
        val habille = ContextThemeWrapper(contexte, R.style.Theme_Arbitre)
        val racine = LayoutInflater.from(habille).inflate(R.layout.bouton_flottant, null)

        val wm = contexte.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = reglages.boutonX
        lp.y = reglages.boutonY

        installerGestes(contexte, racine, wm, lp, reglages)

        wm.addView(racine, lp)
        vue = racine
        gestionnaire = wm
    }

    private fun retirer() {
        val v = vue ?: return
        try {
            gestionnaire?.removeView(v)
        } catch (e: Exception) {
            // Déjà retirée par le système.
        }
        vue = null
        gestionnaire = null
    }

    private fun installerGestes(
        contexte: Context,
        racine: View,
        wm: WindowManager,
        lp: WindowManager.LayoutParams,
        reglages: Reglages,
    ) {
        var xInitial = 0
        var yInitial = 0
        var toucheX = 0f
        var toucheY = 0f
        var deplace = false
        var capture = false

        // Appui long : le texte brut de l'écran part au journal, sans
        // interprétation. Quand une offre échappe à l'analyse, c'est la seule
        // façon de savoir ce que le service a vu au lieu de le supposer.
        val capturer = Runnable {
            capture = true
            LectureEcran.capturer(contexte)
        }

        racine.setOnTouchListener { v, evenement ->
            when (evenement.action) {
                MotionEvent.ACTION_DOWN -> {
                    xInitial = lp.x
                    yInitial = lp.y
                    toucheX = evenement.rawX
                    toucheY = evenement.rawY
                    deplace = false
                    capture = false
                    principal.postDelayed(capturer, APPUI_LONG_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (evenement.rawX - toucheX).toInt()
                    val dy = (evenement.rawY - toucheY).toInt()
                    if (abs(dx) > SEUIL || abs(dy) > SEUIL) {
                        deplace = true
                        principal.removeCallbacks(capturer)
                        lp.x = (xInitial + dx).coerceAtLeast(0)
                        lp.y = (yInitial + dy).coerceAtLeast(0)
                        try {
                            wm.updateViewLayout(racine, lp)
                        } catch (e: Exception) {
                            // Vue retirée pendant le glissement.
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    principal.removeCallbacks(capturer)
                    when {
                        deplace -> {
                            reglages.boutonX = lp.x
                            reglages.boutonY = lp.y
                        }
                        capture -> Unit // déjà traité par l'appui long
                        else -> {
                            v.performClick()
                            LectureEcran.analyserMaintenant(contexte)
                        }
                    }
                    true
                }

                else -> false
            }
        }
    }
}
