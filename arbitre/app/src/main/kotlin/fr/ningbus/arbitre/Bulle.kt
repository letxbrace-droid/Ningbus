package fr.ningbus.arbitre

import android.content.Context
import android.content.res.ColorStateList
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
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import fr.ningbus.arbitre.moteur.Bareme
import fr.ningbus.arbitre.moteur.Decision
import fr.ningbus.arbitre.moteur.Trafic
import fr.ningbus.arbitre.moteur.Verdict
import fr.ningbus.arbitre.moteur.fmt0
import fr.ningbus.arbitre.moteur.fmt1
import fr.ningbus.arbitre.moteur.fmt2
import kotlin.math.abs

/**
 * La bulle posée par-dessus l'application chauffeur.
 *
 * Deux contraintes gouvernent sa forme :
 *
 *  - **elle ne doit rien bloquer.** La fenêtre est déclarée non focalisable
 *    et placée en haut de l'écran : le bouton « Accepter » de l'app chauffeur,
 *    toujours en bas, reste atteignable. Un widget qui coûte une course
 *    acceptée coûte plus cher qu'il ne rapporte ;
 *  - **elle se lit en un regard.** Un mot, un chiffre, une couleur. Le détail
 *    est là pour celui qui a le temps de le lire, jamais pour décider.
 */
object Bulle {

    private val principal = Handler(Looper.getMainLooper())
    private val fermeture = Runnable { retirer() }

    private var vue: View? = null
    private var gestionnaire: WindowManager? = null

    /** Glissement minimal, en pixels, avant de considérer que l'on déplace. */
    private const val SEUIL_GLISSEMENT = 12

    fun afficher(contexte: Context, verdict: Verdict, latenceMs: Long, reglages: Reglages) {
        val app = contexte.applicationContext
        principal.post {
            try {
                poser(app, verdict, latenceMs, reglages)
            } catch (e: Exception) {
                // Fenêtre refusée (permission retirée, écran verrouillé…) :
                // le verdict passe par une notification plutôt que d'être perdu.
                Repli.notifier(app, verdict)
            }
        }
    }

    fun masquer() {
        principal.post { retirer() }
    }

    // --- Pose ---------------------------------------------------------------

    private fun poser(contexte: Context, verdict: Verdict, latenceMs: Long, reglages: Reglages) {
        if (!Settings.canDrawOverlays(contexte)) {
            Repli.notifier(contexte, verdict)
            return
        }
        retirer()

        val habille = ContextThemeWrapper(contexte, R.style.Theme_Arbitre)
        val racine = LayoutInflater.from(habille).inflate(R.layout.bulle, null)
        remplir(racine, verdict, latenceMs, reglages.bareme)

        val wm = contexte.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE laisse le clavier et les touches à l'application
            // du dessous ; seuls les appuis sur la bulle nous reviennent.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = reglages.positionY

        installerGlissement(racine, wm, lp, reglages)

        wm.addView(racine, lp)
        vue = racine
        gestionnaire = wm

        principal.removeCallbacks(fermeture)
        principal.postDelayed(fermeture, reglages.secondesAffichage * 1000L)
    }

    private fun retirer() {
        principal.removeCallbacks(fermeture)
        val v = vue ?: return
        try {
            gestionnaire?.removeView(v)
        } catch (e: Exception) {
            // Déjà retirée par le système : rien à faire.
        }
        vue = null
        gestionnaire = null
    }

    // --- Contenu ------------------------------------------------------------

    private fun remplir(racine: View, verdict: Verdict, latenceMs: Long, bareme: Bareme) {
        val ctx = racine.context
        val teinte = couleur(ctx, verdict.decision)

        racine.findViewById<View>(R.id.entete).backgroundTintList = ColorStateList.valueOf(teinte)
        racine.findViewById<TextView>(R.id.verdict).text = verdict.decision.libelle
        racine.findViewById<TextView>(R.id.latence).text = latenceLisible(latenceMs)

        val euroHeure = racine.findViewById<TextView>(R.id.euro_heure)
        euroHeure.text = verdict.euroHeure?.let { "${fmt0(it)} €/h" } ?: "—"
        euroHeure.setTextColor(teinte)

        racine.findViewById<TextView>(R.id.euro_km).text =
            verdict.euroKm?.let { "${fmt2(it)} €/km" } ?: ""

        racine.findViewById<TextView>(R.id.resume).text = verdict.resume

        // La jauge place l'objectif à mi-course : à moitié pleine, la course
        // rapporte exactement ce qui est visé.
        val jauge = racine.findViewById<ProgressBar>(R.id.jauge)
        jauge.progress = (((verdict.ratio ?: 0.0) * 50.0).toInt()).coerceIn(0, 100)
        jauge.progressTintList = ColorStateList.valueOf(teinte)
        racine.findViewById<TextView>(R.id.objectif).text =
            "objectif ${fmt0(bareme.objectifHeure)} €/h"

        racine.findViewById<TextView>(R.id.detail).text = buildString {
            append(fmt2(verdict.revenuNet)).append(" € net")
            append(" · ").append(fmt0(verdict.minutesTotal)).append(" min")
            append(" · ").append(fmt1(verdict.kmTotal)).append(" km")
        }

        val c = verdict.course
        racine.findViewById<TextView>(R.id.approche).text = when {
            c.minutesApproche != null && c.kmApproche != null ->
                "approche ${fmt0(c.minutesApproche)} min · ${fmt1(c.kmApproche)} km"
            c.minutesApproche != null -> "approche ${fmt0(c.minutesApproche)} min"
            c.kmApproche != null -> "approche ${fmt1(c.kmApproche)} km"
            else -> "approche non lue"
        }

        val trafic = racine.findViewById<TextView>(R.id.trafic)
        if (verdict.trafic == Trafic.INCONNU) {
            trafic.visibility = View.GONE
        } else {
            trafic.visibility = View.VISIBLE
            trafic.text = "circulation ${verdict.trafic.libelle} · " +
                "${fmt0(verdict.vitesseTrajet)} km/h de moyenne"
        }

        val alertes = racine.findViewById<TextView>(R.id.alertes)
        if (verdict.alertes.isEmpty()) {
            alertes.visibility = View.GONE
        } else {
            alertes.visibility = View.VISIBLE
            alertes.text = verdict.alertes.joinToString(" · ", prefix = "⚠ ")
        }
    }

    private fun latenceLisible(ms: Long): String =
        if (ms < 1000) "⚡ $ms ms" else "⚡ ${fmt1(ms / 1000.0)} s"

    private fun couleur(contexte: Context, decision: Decision): Int = ContextCompat.getColor(
        contexte,
        when (decision) {
            Decision.PRENDS -> R.color.vert
            Decision.LIMITE -> R.color.ambre
            Decision.LAISSE -> R.color.rouge
            Decision.INCOMPLET -> R.color.gris
        },
    )

    // --- Déplacement --------------------------------------------------------

    /** Appui = on ferme, glissement vertical = on déplace et on retient. */
    private fun installerGlissement(
        racine: View,
        wm: WindowManager,
        lp: WindowManager.LayoutParams,
        reglages: Reglages,
    ) {
        val carte = racine.findViewById<View>(R.id.carte)
        var yInitial = 0
        var toucheInitiale = 0f
        var deplace = false

        carte.setOnTouchListener { v, evenement ->
            when (evenement.action) {
                MotionEvent.ACTION_DOWN -> {
                    yInitial = lp.y
                    toucheInitiale = evenement.rawY
                    deplace = false
                    principal.removeCallbacks(fermeture) // pas d'escamotage sous le doigt
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val ecart = (evenement.rawY - toucheInitiale).toInt()
                    if (abs(ecart) > SEUIL_GLISSEMENT) {
                        deplace = true
                        lp.y = (yInitial + ecart).coerceAtLeast(0)
                        try {
                            wm.updateViewLayout(racine, lp)
                        } catch (e: Exception) {
                            // Vue déjà retirée pendant le glissement.
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (deplace) {
                        reglages.positionY = lp.y
                        principal.postDelayed(fermeture, reglages.secondesAffichage * 1000L)
                    } else {
                        v.performClick()
                        retirer()
                    }
                    true
                }

                else -> false
            }
        }
    }
}
