package fr.ningbus.arbitre

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
 * Trois contraintes gouvernent sa forme :
 *
 *  - **elle ne doit rien bloquer.** La fenêtre est déclarée non focalisable
 *    et placée en haut de l'écran : le bouton « Accepter » de l'app chauffeur,
 *    toujours en bas, reste atteignable. Un widget qui coûte une course
 *    acceptée coûte plus cher qu'il ne rapporte ;
 *  - **elle se lit par étages.** Décision, argent, contraintes, explication,
 *    diagnostic — dans cet ordre, et le chauffeur doit pouvoir s'arrêter au
 *    premier. Un appui la réduit à ces deux premiers étages ;
 *  - **elle dit pourquoi.** « LAISSE — 14 €/h » laisse deviner ; « LAISSE —
 *    approche de 9,8 km avant la prise en charge » permet de s'étonner, ou
 *    non, en connaissance de cause.
 */
object Bulle {

    private val principal = Handler(Looper.getMainLooper())
    private val fermeture = Runnable { retirer() }

    private var vue: View? = null
    private var gestionnaire: WindowManager? = null

    /** Glissement minimal, en pixels, avant de considérer que l'on déplace. */
    private const val SEUIL_GLISSEMENT = 12

    /** Durée d'appui à partir de laquelle on ouvre le journal. */
    private const val APPUI_LONG_MS = 550L

    fun afficher(
        contexte: Context,
        verdict: Verdict,
        latenceMs: Long,
        source: Source,
        reglages: Reglages,
        scoreOffre: Int? = null,
    ) {
        val app = contexte.applicationContext
        principal.post {
            try {
                poser(app, verdict, latenceMs, source, reglages, scoreOffre)
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

    /**
     * Une bulle est-elle posée en ce moment ?
     *
     * Sert au diagnostic, et non plus à interdire une capture d'écran : ce
     * n'est pas la capture qu'il faut empêcher quand un verdict est affiché,
     * c'est notre propre verdict qu'il faut absenter de l'image.
     */
    val visible: Boolean
        get() = vue != null

    /**
     * Efface la bulle le temps d'une capture d'écran.
     *
     * Une image ne connaît pas les paquets : sans cela, la reconnaissance de
     * texte relirait notre verdict — montant, approche, distance — et le
     * prendrait pour une offre. Le verdict engendrerait un verdict.
     */
    fun eclipser() {
        vue?.alpha = 0f
    }

    fun reparaitre() {
        vue?.alpha = 1f
    }

    // --- Pose ---------------------------------------------------------------

    private fun poser(
        contexte: Context,
        verdict: Verdict,
        latenceMs: Long,
        source: Source,
        reglages: Reglages,
        scoreOffre: Int?,
    ) {
        if (!Settings.canDrawOverlays(contexte)) {
            Repli.notifier(contexte, verdict)
            return
        }
        retirer()

        val habille = ContextThemeWrapper(contexte, R.style.Theme_Arbitre)
        val racine = LayoutInflater.from(habille).inflate(R.layout.bulle, null)
        remplir(racine, verdict, latenceMs, source, reglages.bareme, scoreOffre)
        reduire(racine, reglages.modeCompact)

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

        installerGestes(contexte, racine, wm, lp, reglages)

        wm.addView(racine, lp)
        vue = racine
        gestionnaire = wm

        // La carte s'efface, la pilule de la pastille reste. C'est ce qui
        // permet de laisser la bulle courte sans rien perdre : douze secondes
        // suffisent à décider, une minute à se souvenir de ce qu'on a laissé.
        BoutonFlottant.montrerVerdict(contexte, verdict.euroHeure, verdict.decision)

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

    /** Bascule entre la pilule et la carte complète. */
    private fun reduire(racine: View, compact: Boolean) {
        racine.findViewById<View>(R.id.pilule).visibility =
            if (compact) View.VISIBLE else View.GONE
        racine.findViewById<View>(R.id.detaille).visibility =
            if (compact) View.GONE else View.VISIBLE
    }

    private fun estReduite(racine: View): Boolean =
        racine.findViewById<View>(R.id.pilule).visibility == View.VISIBLE

    // --- Contenu ------------------------------------------------------------

    private fun remplir(
        racine: View,
        verdict: Verdict,
        latenceMs: Long,
        source: Source,
        bareme: Bareme,
        scoreOffre: Int?,
    ) {
        val ctx = racine.context
        val teinte = couleur(ctx, verdict.decision)
        val doux = ContextCompat.getColor(ctx, R.color.bulle_texte_doux)

        // Le contour porte le verdict avant que le mot ne soit lu : de nuit,
        // au coin de l'œil, c'est la couleur qui arrive la première. Il est
        // dessiné plutôt que déclaré, parce qu'une teinte posée sur la
        // ressource entière colorerait aussi le fond — et un aplat fluo
        // par-dessus une carte de navigation est illisible.
        val densite = ctx.resources.displayMetrics.density
        racine.findViewById<View>(R.id.carte).background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f * densite
            setColor(ContextCompat.getColor(ctx, R.color.bulle_fond))
            setStroke((2 * densite).toInt(), teinte)
        }

        // --- Mode réduit : la décision et l'argent, rien d'autre ------------
        racine.findViewById<View>(R.id.pilule_point).backgroundTintList =
            ColorStateList.valueOf(teinte)
        racine.findViewById<TextView>(R.id.pilule_euro).apply {
            text = verdict.euroHeure?.let { "${fmt0(it)} €/h" } ?: "—"
            setTextColor(teinte)
        }
        racine.findViewById<TextView>(R.id.pilule_verdict).apply {
            text = verdict.decision.libelle
            setTextColor(teinte)
        }

        // --- Niveau 1 : la décision ----------------------------------------
        racine.findViewById<View>(R.id.entete).backgroundTintList = ColorStateList.valueOf(teinte)

        // Sur un néon clair, l'encre doit être noire. Le blanc y descend sous
        // 2:1 de contraste, ce qui se lit mal au soleil et pas du tout en
        // mouvement.
        val encre = ContextCompat.getColor(
            ctx,
            when (verdict.decision) {
                Decision.PRENDS, Decision.LIMITE -> R.color.nuit
                Decision.LAISSE, Decision.INCOMPLET -> R.color.bulle_texte
            },
        )
        racine.findViewById<TextView>(R.id.verdict).apply {
            text = verdict.decision.libelle
            setTextColor(encre)
        }
        racine.findViewById<TextView>(R.id.latence).apply {
            text = latenceLisible(latenceMs)
            setTextColor(encre)
        }

        // --- Niveau 2 : l'argent -------------------------------------------
        racine.findViewById<TextView>(R.id.euro_heure).apply {
            text = verdict.euroHeure?.let { "${fmt0(it)} €/h" } ?: "—"
            setTextColor(teinte)
        }
        racine.findViewById<TextView>(R.id.euro_km).text =
            verdict.euroKm?.let { "${fmt2(it)} €/km" } ?: ""

        // La jauge place l'objectif à mi-course : à moitié pleine, la course
        // rapporte exactement ce qui est visé.
        val jauge = racine.findViewById<ProgressBar>(R.id.jauge)
        jauge.progress = (((verdict.ratio ?: 0.0) * 50.0).toInt()).coerceIn(0, 100)
        jauge.progressTintList = ColorStateList.valueOf(teinte)
        racine.findViewById<TextView>(R.id.objectif).text =
            "objectif ${fmt0(bareme.objectifHeure)} €/h"

        // --- Le motif : pourquoi ce verdict et pas un autre -----------------
        ligne(racine, R.id.motif, verdict.motif?.let { "⚠ $it" }, teinte)

        // --- Niveau 3 : les contraintes ------------------------------------
        ligne(
            racine,
            R.id.contraintes,
            verdict.minutesTotal?.let {
                "${fmt1(verdict.kmTotal)} km · ${fmt0(it)} min mobilisées" +
                    (verdict.revenuNet?.let { net -> " · ${fmt2(net)} € net" } ?: "")
            },
            ContextCompat.getColor(ctx, R.color.bulle_texte),
        )

        // --- Niveau 4 : les trois kilométrages, jamais additionnés ----------
        //
        // « 28,1 km » ne dit rien. « 16,4 de course, 1,6 d'approche, 5,7 de
        // retour à vide » dit exactement ce qui fait le verdict — et permet
        // de repérer d'un coup d'œil un chiffre aberrant, comme une approche
        // lue quatre fois trop grande.
        val c = verdict.course
        ligne(racine, R.id.km_course, verdict.kmCourse?.let { km ->
            "course " + fmt1(km) + " km" +
                (c.minutesTrajet?.let { " · ${fmt0(it)} min" } ?: " · durée estimée")
        }, doux)

        ligne(racine, R.id.km_approche, when {
            c.kmApproche != null && c.minutesApproche != null ->
                "approche ${fmt1(c.kmApproche)} km · ${fmt0(c.minutesApproche)} min"
            c.minutesApproche != null -> "approche ${fmt0(c.minutesApproche)} min"
            c.kmApproche != null -> "approche ${fmt1(c.kmApproche)} km"
            else -> "approche non lue"
        }, doux)

        ligne(
            racine,
            R.id.km_retour,
            verdict.kmRetour?.takeIf { it > 0 }?.let {
                "↩ retour à vide ${fmt1(it)} km (supposé)"
            },
            doux,
        )

        ligne(
            racine,
            R.id.trafic,
            verdict.trafic.takeIf { it != Trafic.INCONNU }?.let {
                "circulation ${it.libelle} · ${fmt0(verdict.vitesseTrajet)} km/h"
            },
            doux,
        )

        // --- Niveau 5 : d'où vient ce qu'on vient de lire -------------------
        //
        // La source n'est pas un détail d'ingénieur. Une approche de 1,6 km
        // lue « 6,0 km » vient d'une décimale coupée par la reconnaissance
        // d'image ; l'arbre d'accessibilité, lui, ne coupe rien. Savoir par
        // quel chemin un chiffre est arrivé, c'est savoir s'il faut s'en
        // méfier.
        val confiance = verdict.confiance
        racine.findViewById<TextView>(R.id.diagnostic).apply {
            text = buildString {
                append("lecture : ").append(source.libelle)
                scoreOffre?.let { append(" · offre ").append(it).append("/100") }
                append(" · ").append(confiance.resume)
                confiance.manquants.takeIf { it.isNotEmpty() }?.let { absents ->
                    append(" — manque ").append(absents.joinToString(", ") { it.champ })
                }
            }
            setTextColor(
                if (confiance.fiable) doux else ContextCompat.getColor(ctx, R.color.ambre)
            )
        }

        ligne(
            racine,
            R.id.alertes,
            verdict.alertes.takeIf { it.isNotEmpty() }?.joinToString(" · "),
            ContextCompat.getColor(ctx, R.color.ambre),
        )
    }

    /** Pose un texte, ou escamote la ligne quand il n'y a rien à dire. */
    private fun ligne(racine: View, id: Int, texte: String?, couleur: Int) {
        racine.findViewById<TextView>(id).apply {
            if (texte.isNullOrEmpty()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = texte
                setTextColor(couleur)
            }
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

    // --- Gestes -------------------------------------------------------------

    /**
     * Trois gestes, et pas un de plus.
     *
     * Appui : développer ou réduire. Glissement vertical : déplacer, et
     * retenir la place. Appui long : ouvrir le journal, pour comprendre le
     * verdict qu'on vient de lire. Rien qui demande de viser au volant.
     */
    private fun installerGestes(
        contexte: Context,
        racine: View,
        wm: WindowManager,
        lp: WindowManager.LayoutParams,
        reglages: Reglages,
    ) {
        val carte = racine.findViewById<View>(R.id.carte)
        var yInitial = 0
        var toucheInitiale = 0f
        var deplace = false
        var journalOuvert = false

        val ouvrirJournal = Runnable {
            journalOuvert = true
            ouvrirLeJournal(contexte)
        }

        carte.setOnTouchListener { v, evenement ->
            when (evenement.action) {
                MotionEvent.ACTION_DOWN -> {
                    yInitial = lp.y
                    toucheInitiale = evenement.rawY
                    deplace = false
                    journalOuvert = false
                    principal.removeCallbacks(fermeture) // pas d'escamotage sous le doigt
                    principal.postDelayed(ouvrirJournal, APPUI_LONG_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val ecart = (evenement.rawY - toucheInitiale).toInt()
                    if (abs(ecart) > SEUIL_GLISSEMENT) {
                        deplace = true
                        principal.removeCallbacks(ouvrirJournal)
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
                    principal.removeCallbacks(ouvrirJournal)
                    when {
                        deplace -> reglages.positionY = lp.y
                        journalOuvert -> Unit // déjà traité par l'appui long
                        else -> {
                            v.performClick()
                            // Le mode choisi est retenu : celui qui replie la
                            // bulle une fois la replie pour de bon.
                            val compact = !estReduite(racine)
                            reglages.modeCompact = compact
                            reduire(racine, compact)
                        }
                    }
                    principal.postDelayed(fermeture, reglages.secondesAffichage * 1000L)
                    true
                }

                else -> false
            }
        }
    }

    /**
     * Ouvre le journal depuis la bulle.
     *
     * Une application ordinaire n'aurait pas le droit de démarrer une
     * activité depuis l'arrière-plan. Celle-ci l'a, et précisément parce
     * qu'elle détient la superposition d'écran — sans quoi cette bulle
     * n'existerait pas.
     */
    private fun ouvrirLeJournal(contexte: Context) {
        try {
            contexte.startActivity(
                Intent(contexte, ActiviteJournal::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            masquer()
        } catch (e: Exception) {
            // Démarrage refusé : la bulle reste, le journal s'ouvre à la main.
        }
    }
}
