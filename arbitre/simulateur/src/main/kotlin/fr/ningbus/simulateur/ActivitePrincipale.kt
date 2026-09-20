package fr.ningbus.simulateur

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * La fausse application chauffeur du banc d'essai.
 *
 * Elle rejoue des cartes d'offre réelles de deux façons, parce que c'est la
 * différence entre les deux qui a coûté quatre versions :
 *
 *  - **en plein écran**, cas facile où la fenêtre a le focus ;
 *  - **en fenêtre flottante** par-dessus l'écran d'accueil, cas réel où le
 *    focus reste à l'application du dessous. Une lecture limitée à
 *    `rootInActiveWindow` lit alors le lanceur et ne trouve rien — le défaut
 *    exact que ce simulateur existe pour interdire de revenir.
 *
 * Pilotable par intention, pour que les essais instrumentés l'utilisent sans
 * toucher l'écran :
 *
 *     am start -n fr.ningbus.simulateur/.ActivitePrincipale \
 *         -e offre ULIS -e mode FLOTTANTE
 */
class ActivitePrincipale : AppCompatActivity() {

    private val principal = Handler(Looper.getMainLooper())
    private var flottante: View? = null

    override fun onCreate(etat: Bundle?) {
        super.onCreate(etat)
        setContentView(construireEcran())
        jouerIntention()
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        jouerIntention()
    }

    override fun onDestroy() {
        retirerFlottante()
        super.onDestroy()
    }

    /** Applique le mode demandé par l'intention, s'il y en a un. */
    private fun jouerIntention() {
        val mode = intent?.getStringExtra("mode") ?: return
        val offre = Offres.parCle(intent?.getStringExtra("offre"))
        when (mode) {
            "FLOTTANTE" -> afficherFlottante(offre)
            "PLEIN_ECRAN" -> afficherPleinEcran(offre)
            "FERMER" -> retirerFlottante()
        }
    }

    // --- Écran de pilotage --------------------------------------------------

    private fun construireEcran(): View {
        val colonne = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        colonne.addView(TextView(this).apply {
            text = getString(R.string.titre)
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        colonne.addView(TextView(this).apply {
            text = getString(R.string.aide)
            textSize = 13f
            alpha = 0.75f
            setPadding(0, dp(6), 0, dp(12))
        })

        for (offre in Offres.TOUTES) {
            colonne.addView(TextView(this).apply {
                text = offre.cle
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(14), 0, dp(4))
            })
            colonne.addView(bouton(getString(R.string.en_flottant)) { afficherFlottante(offre) })
            colonne.addView(bouton(getString(R.string.en_plein_ecran)) { afficherPleinEcran(offre) })
        }

        colonne.addView(bouton(getString(R.string.fermer_offre)) { retirerFlottante() })

        return android.widget.ScrollView(this).apply { addView(colonne) }
    }

    private fun bouton(libelle: String, action: () -> Unit) = Button(this).apply {
        text = libelle
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        setOnClickListener { action() }
    }

    // --- Les deux façons d'afficher une offre -------------------------------

    private fun afficherPleinEcran(offre: Offre) {
        retirerFlottante()
        setContentView(
            android.widget.ScrollView(this).apply { addView(carte(offre)) }
        )
    }

    /**
     * L'offre par-dessus l'écran d'accueil, focus laissé au-dessous.
     *
     * `moveTaskToBack` est le geste qui compte : sans lui, cette activité
     * garde le focus et le cas dégénère en « plein écran », qui ne prouve
     * rien.
     */
    private fun afficherFlottante(offre: Offre) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.superposition_requise, Toast.LENGTH_LONG).show()
            return
        }
        retirerFlottante()

        val vue = carte(offre)
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.BOTTOM
        wm.addView(vue, lp)
        flottante = vue

        principal.postDelayed({ moveTaskToBack(true) }, 200)
    }

    private fun retirerFlottante() {
        val vue = flottante ?: return
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(vue)
        } catch (e: Exception) {
            // Déjà retirée.
        }
        flottante = null
    }

    /** La carte, une ligne de texte par ligne de la vraie carte. */
    private fun carte(offre: Offre): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.WHITE)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        for ((index, ligne) in offre.lignes.withIndex()) {
            addView(TextView(context).apply {
                text = ligne
                setTextColor(Color.BLACK)
                textSize = if (index == 1) 30f else 15f
                setPadding(0, dp(3), 0, dp(3))
            })
        }
    }

    private fun dp(valeur: Int): Int = (valeur * resources.displayMetrics.density).toInt()
}
