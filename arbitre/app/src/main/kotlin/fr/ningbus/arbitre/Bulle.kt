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
import android.view.animation.DecelerateInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
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
    private val fermeture = Runnable { effacer() }

    private var vue: View? = null
    private var gestionnaire: WindowManager? = null

    /** Glissement minimal, en pixels, avant de considérer que l'on déplace. */
    private const val SEUIL_GLISSEMENT = 12

    /** Durée d'appui à partir de laquelle on ouvre le journal. */
    private const val APPUI_LONG_MS = 550L

    /**
     * Durée de l'entrée de la bulle, en millisecondes.
     *
     * Assez pour que l'œil suive le mouvement au lieu de chercher ce qui a
     * changé, trop peu pour retarder une décision qui n'a que douze secondes.
     */
    private const val DUREE_ENTREE_MS = 180L

    /** Durée de la sortie : plus courte que l'entrée, car on ne la regarde pas. */
    private const val DUREE_SORTIE_MS = 140L

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
        remplir(racine, verdict, latenceMs, source, reglages, scoreOffre)
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

        // La bulle descend en place plutôt que d'apparaître d'un coup.
        //
        // Elle se pose par-dessus une application que le chauffeur est en
        // train de regarder : surgir sans transition est perçu comme un
        // à-coup de l'application du dessous, et fait chercher des yeux ce qui
        // vient de bouger. Le mouvement dit d'où elle vient, et l'œil la suit
        // au lieu de la découvrir. Cent quatre-vingts millisecondes — assez
        // pour être vu, trop peu pour retarder une décision qui en a douze.
        val carte = racine.findViewById<View>(R.id.carte)
        carte.alpha = 0f
        carte.translationY = -24f * contexte.resources.displayMetrics.density
        carte.scaleX = 0.97f
        carte.scaleY = 0.97f
        carte.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(DUREE_ENTREE_MS)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .start()

        // La carte s'efface, la pilule de la pastille reste. C'est ce qui
        // permet de laisser la bulle courte sans rien perdre : douze secondes
        // suffisent à décider, une minute à se souvenir de ce qu'on a laissé.
        BoutonFlottant.montrerVerdict(contexte, verdict.euroKmRoule, verdict.decision)

        principal.removeCallbacks(fermeture)
        principal.postDelayed(fermeture, reglages.secondesAffichage * 1000L)
    }

    /**
     * Retire la bulle en la laissant s'effacer.
     *
     * Réservé à la fin normale du compte à rebours : une bulle remplacée par
     * la suivante, ou fermée d'un geste, doit partir à l'instant — sans quoi
     * deux cartes se chevaucheraient le temps de l'animation.
     */
    private fun effacer() {
        val v = vue
        if (v == null) {
            retirer()
            return
        }
        v.findViewById<View>(R.id.carte).animate()
            .alpha(0f)
            .translationY(-12f * v.resources.displayMetrics.density)
            .setDuration(DUREE_SORTIE_MS)
            .withEndAction { retirer() }
            .start()
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
        reglages: Reglages,
        scoreOffre: Int?,
    ) {
        val bareme = reglages.bareme
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
            text = verdict.euroKmRoule?.let { "${fmt2(it)} €/km" } ?: "—"
            setTextColor(teinte)
        }
        // L'euro/heure sous l'euro/kilomètre : sans lui, un LAISSE à
        // 1,02 €/km ne s'explique pas, et un verdict qu'on ne comprend pas
        // finit par ne plus être suivi.
        //
        // Et quand il n'y a pas d'euro/heure — lecture incomplète, montant
        // invraisemblable — c'est le motif qui prend la place plutôt que du
        // vide. Un « — INCOMPLET » sans un mot d'explication est le pire des
        // affichages : il coûte un regard et ne rend rien.
        //
        // Et quand l'approche n'a pas été lue, le budget passe devant
        // l'objectif. C'est lui qui décide : « 39 €/h pour 25 visés » sous un
        // LIMITE est incompréhensible tant qu'on ne sait pas que les 39 €/h
        // supposent le client devant la porte.
        val budget = verdict.budgetApprocheKm
        racine.findViewById<TextView>(R.id.pilule_heure).text = verdict.euroHeure?.let {
            if (budget != null && budget > 0.05) {
                "${fmt0(it)} €/h · ok si approche < ${fmt1(budget)} km"
            } else {
                "${fmt0(it)} €/h pour ${fmt0(bareme.objectifHeure)} visés"
            }
        } ?: verdict.motif.orEmpty()
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
        //
        // L'euro par kilomètre en grand, l'euro par heure à côté. Le premier
        // ne suppose aucune durée ; le second repose sur une durée que le
        // moteur estime lui-même quand la plateforme ne l'annonce pas. Devant
        // une offre qui laisse douze secondes, le chiffre qui ne suppose rien
        // mérite la grande taille.
        racine.findViewById<TextView>(R.id.euro_km).apply {
            text = verdict.euroKmRoule?.let { "${fmt2(it)} €/km" } ?: "—"
            setTextColor(teinte)
        }
        racine.findViewById<TextView>(R.id.euro_heure).text =
            verdict.euroHeure?.let { "${fmt0(it)} €/h" } ?: ""

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

        // --- Le coût de roulage, poste par poste ---------------------------
        //
        // Un total de 3,30 € se croit ou ne se croit pas. « Carburant 1,30 ·
        // usure 1,10 · fixes 0,90 » se vérifie, et se corrige là où il est
        // faux : c'est la seule façon de découvrir que l'entretien pèse
        // autant que le gazole.
        ligne(
            racine,
            R.id.couts,
            if (!reglages.detailsCouts) null else {
                val postes = listOfNotNull(
                    verdict.coutCarburant, verdict.coutUsure, verdict.coutFixes,
                )
                if (postes.size < 3) null else {
                    "carburant ${fmt2(postes[0])} € · usure ${fmt2(postes[1])} €" +
                        " · fixes ${fmt2(postes[2])} €"
                }
            },
            doux,
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

        // --- L'attente réelle : ce que coûte vraiment un secteur creux -------
        //
        // Une journée relevée le 25/09 a montré que ce chiffre inverse le
        // classement : la course de 9,00 € qui affichait 70 €/h sur son temps
        // facturé n'en rendait que 12 une fois ses trente-huit minutes
        // d'attente comptées — la plus mauvaise de la journée — pendant que
        // celle à 1,01 €/km, réputée médiocre, en rendait 26.
        //
        // Affiché dès qu'il existe, et jamais transformé en verdict : baisser
        // l'objectif quand les offres se raréfient ferait de l'outil une
        // machine à justifier les mauvaises courses.
        val amorti = verdict.euroHeureAmorti
        val attente = bareme.minutesEntreOffres
        ligne(
            racine,
            R.id.amorti,
            if (amorti == null || attente == null) null else {
                "⏳ attente réelle ${fmt0(attente)} min → ${fmt0(amorti)} €/h tout compris"
            },
            ContextCompat.getColor(
                ctx,
                if (amorti != null && amorti < bareme.objectifHeure * (1.0 - bareme.marge)) {
                    R.color.ambre
                } else {
                    R.color.bulle_texte
                },
            ),
        )

        // --- La zone morte : le seul chiffre que le moteur ne sait pas juger --
        //
        // Le barème suppose un repositionnement moyen — 35 % du trajet. C'est
        // juste sur une journée entière et faux sur une course en particulier :
        // une dépose au fond d'un secteur qui ne redemande rien coûte le retour
        // complet. Une course relevée le 25/09 le dit mieux qu'un raisonnement :
        // 26 €/h au barème moyen, 15 €/h s'il faut rentrer à vide sur 47 km.
        //
        // La ligne ne s'affiche que quand elle contredit le verdict. Autrement
        // elle n'apprend rien, et une bulle qui parle pour ne rien dire finit
        // par ne plus être lue du tout.
        val seuilBas = bareme.objectifHeure * (1.0 - bareme.marge)
        val heure = verdict.euroHeure
        val heurePlein = verdict.euroHeureRetourPlein
        val contredit = bareme.partRetour < 1.0 &&
            heure != null && heure >= seuilBas &&
            heurePlein != null && heurePlein < seuilBas
        ligne(
            racine,
            R.id.retour_plein,
            if (!contredit) null else "↩ si la zone ne te redonne rien : ${fmt0(heurePlein)} €/h",
            ContextCompat.getColor(ctx, R.color.ambre),
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
        // lue « 6,0 km » vient de « 1.6 » reconnu « l.6 » par l'image ;
        // l'arbre d'accessibilité, lui, ne confond aucune lettre. Savoir par
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
