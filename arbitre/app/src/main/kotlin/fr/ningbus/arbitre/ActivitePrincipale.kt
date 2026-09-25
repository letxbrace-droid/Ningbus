package fr.ningbus.arbitre

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import fr.ningbus.arbitre.moteur.Arbitre
import fr.ningbus.arbitre.moteur.Bareme
import fr.ningbus.arbitre.moteur.Course
import fr.ningbus.arbitre.moteur.Decision
import fr.ningbus.arbitre.moteur.Plateformes
import fr.ningbus.arbitre.moteur.Verdict
import fr.ningbus.arbitre.moteur.fmt0
import fr.ningbus.arbitre.moteur.fmt1
import fr.ningbus.arbitre.moteur.fmt2
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * L'application, en quatre destinations.
 *
 * Elle n'en avait qu'une : un écran unique qui défilait sur trois mètres, où
 * il fallait passer devant le barème pour voir l'état des services et devant
 * le simulateur pour régler le barème. Quatre sujets qui ne se ressemblent pas
 * méritent quatre adresses — et le cockpit, en particulier, méritait de ne
 * porter que ce qu'on regarde entre deux courses.
 *
 * Les champs numériques restent engendrés à partir d'une liste de
 * descripteurs plutôt que décrits un à un en XML : ajouter un paramètre au
 * barème ne demande alors qu'une ligne ici.
 */
class ActivitePrincipale : AppCompatActivity() {

    private lateinit var reglages: Reglages
    private val champs = mutableListOf<Pair<Champ, EditText>>()
    private val heure = SimpleDateFormat("HH:mm", Locale.FRANCE)

    override fun onCreate(etat: Bundle?) {
        super.onCreate(etat)
        setContentView(R.layout.principale)
        reglages = Reglages(this)

        construireOnglets()

        findViewById<Button>(R.id.bouton_notif).setOnClickListener {
            ouvrir(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.bouton_superposition).setOnClickListener {
            ouvrir(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        }
        findViewById<Button>(R.id.bouton_ecran).setOnClickListener {
            ouvrir(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.bouton_test).setOnClickListener { essai() }
        findViewById<Button>(R.id.bouton_voir_bulle).setOnClickListener { essai() }
        findViewById<Button>(R.id.bouton_planifiees).setOnClickListener {
            startActivity(Intent(this, ActivitePlanifiees::class.java))
        }
        findViewById<Button>(R.id.bouton_journal).setOnClickListener {
            startActivity(Intent(this, ActiviteJournal::class.java))
        }
        findViewById<Button>(R.id.bouton_defaut).setOnClickListener {
            reglages.bareme = Bareme()
            peuplerChamps()
            rafraichirSimulateur()
            Toast.makeText(this, R.string.bareme_reinitialise, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.bouton_ajouter_paquet).setOnClickListener {
            val champ = findViewById<EditText>(R.id.champ_paquet)
            val paquet = champ.text.toString().trim()
            if (paquet.isEmpty()) return@setOnClickListener
            reglages.ajouterPaquet(paquet)
            champ.setText("")
            peuplerApplications()
        }

        interrupteur(R.id.actif, reglages.actif) {
            reglages.actif = it
            BoutonFlottant.synchroniser(this)
            peuplerCockpit()
        }
        interrupteur(R.id.toutes_apps, reglages.ecouteToutesApps) {
            reglages.ecouteToutesApps = it
            LectureEcran.rafraichirFiltre()
            peuplerApplications()
        }
        interrupteur(R.id.bouton_flottant, reglages.boutonFlottant) {
            reglages.boutonFlottant = it
            BoutonFlottant.synchroniser(this)
            if (it && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.superposition_requise, Toast.LENGTH_LONG).show()
            }
        }
        interrupteur(R.id.vibration, reglages.vibration) { reglages.vibration = it }
        interrupteur(R.id.decouverte, reglages.modeDecouverte) { reglages.modeDecouverte = it }
        interrupteur(R.id.filtre_ecrans, reglages.filtrerEcrans) { reglages.filtrerEcrans = it }
        interrupteur(R.id.details_couts, reglages.detailsCouts) { reglages.detailsCouts = it }
        interrupteur(R.id.prudence, reglages.bareme.prudenceTrafic) {
            reglages.bareme = reglages.bareme.copy(prudenceTrafic = it)
            rafraichirSimulateur()
        }
        interrupteur(R.id.ocr, reglages.ocrSecours) { reglages.ocrSecours = it }
        interrupteur(R.id.compact, reglages.modeCompact) { reglages.modeCompact = it }
        interrupteur(R.id.veille, reglages.veille) {
            reglages.veille = it
            ServiceVeille.synchroniser(this)
        }

        construireChamps()
        construireCurseurs()
        demanderNotifications()
        ServiceVeille.creerCanaux(this)
        ServiceVeille.synchroniser(this)

        findViewById<TextView>(R.id.version_app).text = versionLisible()
    }

    override fun onResume() {
        super.onResume()
        etatPermissions()
        peuplerChamps()
        peuplerApplications()
        peuplerCockpit()
        peuplerHistorique()
        rafraichirSimulateur()
    }

    override fun onPause() {
        super.onPause()
        enregistrerChamps()
    }

    // --- Les quatre onglets --------------------------------------------------

    private enum class Onglet(val titre: Int, val icone: Int, val section: Int) {
        ACCUEIL(R.string.onglet_accueil, R.drawable.ic_accueil, R.id.section_accueil),
        COURSES(R.string.onglet_courses, R.drawable.ic_courses, R.id.section_courses),
        SIMULATEUR(R.string.onglet_simulateur, R.drawable.ic_simulateur, R.id.section_simulateur),
        REGLAGES(R.string.onglet_reglages, R.drawable.ic_reglages, R.id.section_reglages),
    }

    private val ongletsPeints = mutableListOf<Pair<Onglet, View>>()
    private var ongletCourant = Onglet.ACCUEIL

    private fun construireOnglets() {
        val barre = findViewById<LinearLayout>(R.id.barre_onglets)
        for (onglet in Onglet.entries) {
            val vue = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f,
                )
                setOnClickListener { afficher(onglet) }
            }
            vue.addView(
                ImageView(this).apply {
                    setImageResource(onglet.icone)
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                }
            )
            vue.addView(
                TextView(this).apply {
                    text = getString(onglet.titre)
                    textSize = 11f
                    setPadding(0, dp(3), 0, 0)
                }
            )
            barre.addView(vue)
            ongletsPeints += onglet to vue
        }
        afficher(Onglet.ACCUEIL)
    }

    /**
     * Bascule d'onglet.
     *
     * Les sections sont toutes présentes et seule leur visibilité change :
     * une bascule doit être instantanée, et reconstruire une section à chaque
     * appui ferait perdre la position de défilement — le genre de détail
     * qu'on ne remarque que lorsqu'il manque.
     */
    private fun afficher(onglet: Onglet) {
        ongletCourant = onglet
        for (autre in Onglet.entries) {
            findViewById<View>(autre.section).visibility =
                if (autre == onglet) View.VISIBLE else View.GONE
        }
        val actif = ContextCompat.getColor(this, R.color.primaire)
        val dormant = ContextCompat.getColor(this, R.color.gris)
        for ((cible, vue) in ongletsPeints) {
            val teinte = if (cible == onglet) actif else dormant
            (vue as LinearLayout).let { colonne ->
                (colonne.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(teinte)
                (colonne.getChildAt(1) as TextView).setTextColor(teinte)
            }
        }
    }

    // --- Accueil : le cockpit ------------------------------------------------

    /**
     * Ce qu'on regarde entre deux courses, et rien d'autre.
     *
     * Deux questions : est-ce que ça marche, et qu'a-t-il dit de la dernière
     * offre. Tout ce qui se règle vit dans l'onglet Réglages — un écran
     * d'accueil sur lequel on règle quelque chose n'est plus un accueil, c'est
     * le début des réglages.
     */
    private fun peuplerCockpit() {
        // « Autorisé mais non lié » compte comme une panne, et c'est le cœur
        // du cockpit : Android affiche le service comme coché alors qu'il ne
        // reçoit plus rien, et aucune offre n'arrive sans que rien ne le dise.
        val enPanne = !lectureEcranActive() || !LectureEcran.lie
        val teinte = when {
            !reglages.actif -> R.color.gris
            enPanne -> R.color.rouge
            else -> R.color.vert
        }
        val couleur = ContextCompat.getColor(this, teinte)

        findViewById<View>(R.id.voyant_etat).apply {
            background = ContextCompat.getDrawable(context, R.drawable.fond_puce)
            backgroundTintList = ColorStateList.valueOf(couleur)
        }
        findViewById<TextView>(R.id.titre_etat).apply {
            text = getString(
                when {
                    !reglages.actif -> R.string.arbitre_eteint
                    enPanne -> R.string.arbitre_en_panne
                    else -> R.string.arbitre_actif
                }
            )
            setTextColor(couleur)
        }
        findViewById<TextView>(R.id.detail_etat).text = when {
            !reglages.actif -> getString(R.string.arbitre_eteint_detail)
            enPanne -> getString(R.string.service_non_lie)
            else -> getString(R.string.arbitre_actif_detail)
        }

        peuplerChips()
        peuplerDerniereCourse()
    }

    /** Les trois voyants qui décident si une offre sera vue, ou non. */
    private fun peuplerChips() {
        val conteneur = findViewById<LinearLayout>(R.id.conteneur_chips)
        conteneur.removeAllViews()

        val chips = listOf(
            Triple(
                getString(R.string.chip_accessibilite),
                lectureEcranActive() && LectureEcran.lie,
                { ouvrir(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            ),
            Triple(
                getString(R.string.chip_image),
                Ocr.disponible && reglages.ocrSecours,
                { afficher(Onglet.REGLAGES) },
            ),
            Triple(
                getString(R.string.chip_detection),
                reglages.actif && reglages.filtrerEcrans,
                { afficher(Onglet.REGLAGES) },
            ),
        )
        for ((index, chip) in chips.withIndex()) {
            val (nom, bon, action) = chip
            conteneur.addView(
                chip(nom, if (bon) R.color.vert else R.color.ambre, action).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                    ).apply { if (index > 0) marginStart = dp(8) }
                }
            )
        }
    }

    private fun chip(texte: String, couleur: Int, action: (() -> Unit)? = null): TextView =
        TextView(this).apply {
            text = texte
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@ActivitePrincipale, couleur))
            setBackgroundResource(R.drawable.fond_chip)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            action?.let {
                isClickable = true
                setOnClickListener { _ -> it() }
            }
        }

    /**
     * La dernière course, telle qu'elle a été jugée — et non recalculée.
     *
     * Recalculer depuis le texte brut donnerait un chiffre différent dès que
     * le barème a changé entre-temps, et un historique qui se réécrit tout
     * seul n'est plus un historique.
     */
    private fun peuplerDerniereCourse() {
        val carte = findViewById<LinearLayout>(R.id.carte_derniere)
        val vide = findViewById<TextView>(R.id.aucune_course)
        carte.removeAllViews()

        val ligne = Journal.lignes(this).firstOrNull()
        if (ligne == null) {
            carte.visibility = View.GONE
            vide.visibility = View.VISIBLE
            findViewById<TextView>(R.id.age_derniere).text = ""
            return
        }
        carte.visibility = View.VISIBLE
        vide.visibility = View.GONE
        findViewById<TextView>(R.id.age_derniere).text = age(ligne.horodatage)

        val couleur = ContextCompat.getColor(this, teinteDe(ligne.decision))

        // Étage 1 : l'euro par kilomètre, puis l'euro/heure. Dans cet ordre
        // parce que le premier ne suppose aucune durée, et que c'est celui
        // qu'un chauffeur compare d'une plateforme à l'autre.
        carte.addView(
            TextView(this).apply {
                text = "${fmt2(ligne.euroKm)} €/km"
                textSize = 34f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(couleur)
            }
        )
        carte.addView(
            TextView(this).apply {
                text = "${fmt0(ligne.euroHeure)} €/h  ·  ${ligne.decision}"
                textSize = 15f
                setTextColor(couleur)
                alpha = 0.9f
            }
        )

        // Étage 2 : les chiffres de l'offre, tels qu'elle les annonçait.
        carte.addView(
            troisColonnes(
                "${fmt2(ligne.prix)} €" to "prix",
                "${fmt1(ligne.kmRoules)} km" to "roulés",
                "${fmt0(ligne.minutes)} min" to "mobilisées",
            )
        )

        // Étage 3 : les kilomètres séparés — c'est là que se voit l'exil.
        carte.addView(
            troisColonnes(
                "${fmt1(ligne.kmApproche)} km" to "approche",
                "${fmt1(ligne.kmCourse)} km" to "client à bord",
                "${ligne.confiance} %" to "confiance",
            )
        )

        if (ligne.motif.isNotEmpty()) {
            carte.addView(
                TextView(this).apply {
                    text = ligne.motif
                    textSize = 13f
                    setTextColor(couleur)
                    setPadding(0, dp(8), 0, 0)
                }
            )
        }

        if (reglages.detailsCouts && ligne.cout != null) {
            carte.addView(
                troisColonnes(
                    "${fmt2(ligne.cout)} €" to "coût estimé",
                    "${fmt2(ligne.revenuNet)} €" to "gain net",
                    "${ligne.scoreOffre}/100" to "score d'offre",
                )
            )
        }
    }

    /** Trois valeurs et leurs légendes, la brique de base du cockpit. */
    private fun troisColonnes(vararg colonnes: Pair<String, String>): View {
        val ligne = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        for ((valeur, legende) in colonnes) {
            val colonne = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                )
            }
            colonne.addView(
                TextView(this).apply {
                    text = valeur
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                }
            )
            colonne.addView(
                TextView(this).apply {
                    text = legende
                    textSize = 11f
                    alpha = 0.65f
                }
            )
            ligne.addView(colonne)
        }
        return ligne
    }

    private fun teinteDe(decision: String): Int = when (decision) {
        Decision.PRENDS.name -> R.color.vert
        Decision.LIMITE.name -> R.color.ambre
        Decision.LAISSE.name -> R.color.rouge
        else -> R.color.gris
    }

    /** « il y a 5 s », « il y a 12 min », « 08:47 » au-delà de l'heure. */
    private fun age(horodatage: Long): String {
        val secondes = (System.currentTimeMillis() - horodatage) / 1000
        return when {
            secondes < 60 -> "il y a ${secondes} s"
            secondes < 3600 -> "il y a ${secondes / 60} min"
            else -> heure.format(Date(horodatage))
        }
    }

    // --- Courses : ce qui a été vu -------------------------------------------

    private enum class Filtre { TOUTES, PRISES, REFUSEES }

    private var filtre = Filtre.TOUTES

    private fun peuplerHistorique() {
        val lignes = Journal.lignes(this)
        peuplerStats(lignes)
        peuplerFiltres(lignes)

        val conteneur = findViewById<LinearLayout>(R.id.conteneur_historique)
        conteneur.removeAllViews()

        val retenues = lignes.filter {
            when (filtre) {
                Filtre.TOUTES -> true
                Filtre.PRISES -> it.decision == Decision.PRENDS.name
                Filtre.REFUSEES -> it.decision == Decision.LAISSE.name
            }
        }
        if (retenues.isEmpty()) {
            conteneur.addView(
                TextView(this).apply {
                    text = getString(R.string.journal_vide)
                    textSize = 13f
                    alpha = 0.7f
                    setPadding(0, dp(16), 0, 0)
                }
            )
            return
        }
        for (ligne in retenues) conteneur.addView(ligneHistorique(ligne))
    }

    /**
     * Les trois chiffres de la session.
     *
     * Le taux de prise est le plus instructif des trois, et c'est celui qu'on
     * ne calcule jamais de tête : un chauffeur qui refuse neuf offres sur dix
     * ne le sait pas, il sait seulement qu'il attend.
     */
    private fun peuplerStats(lignes: List<Ligne>) {
        val conteneur = findViewById<LinearLayout>(R.id.conteneur_stats)
        conteneur.removeAllViews()

        val prises = lignes.count { it.decision == Decision.PRENDS.name }
        val part = if (lignes.isEmpty()) 0 else prises * 100 / lignes.size
        val euroKm = lignes.mapNotNull { it.euroKm }
        val moyen = if (euroKm.isEmpty()) null else euroKm.average()

        val cases = listOf(
            "${lignes.size}" to getString(R.string.stat_analysees),
            "$prises" to "${getString(R.string.stat_prises)} ($part %)",
            fmt2(moyen) to getString(R.string.stat_euro_km),
        )
        for ((index, c) in cases.withIndex()) {
            val bloc = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.fond_carte)
                setPadding(dp(8), dp(12), dp(8), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                ).apply { if (index > 0) marginStart = dp(8) }
            }
            bloc.addView(
                TextView(this).apply {
                    text = c.first
                    textSize = 22f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(
                        ContextCompat.getColor(
                            this@ActivitePrincipale,
                            if (index == 1) R.color.vert else R.color.primaire,
                        )
                    )
                }
            )
            bloc.addView(
                TextView(this).apply {
                    text = c.second
                    textSize = 11f
                    gravity = Gravity.CENTER
                    alpha = 0.7f
                }
            )
            conteneur.addView(bloc)
        }
    }

    private fun peuplerFiltres(lignes: List<Ligne>) {
        val conteneur = findViewById<LinearLayout>(R.id.conteneur_filtres)
        conteneur.removeAllViews()

        val comptes = mapOf(
            Filtre.TOUTES to lignes.size,
            Filtre.PRISES to lignes.count { it.decision == Decision.PRENDS.name },
            Filtre.REFUSEES to lignes.count { it.decision == Decision.LAISSE.name },
        )
        val noms = mapOf(
            Filtre.TOUTES to R.string.filtre_toutes,
            Filtre.PRISES to R.string.filtre_prises,
            Filtre.REFUSEES to R.string.filtre_refusees,
        )
        for ((index, f) in Filtre.entries.withIndex()) {
            val vue = chip(
                "${getString(noms.getValue(f))} (${comptes[f]})",
                if (f == filtre) R.color.primaire else R.color.gris,
            ) {
                filtre = f
                peuplerHistorique()
            }
            vue.layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            ).apply { if (index > 0) marginStart = dp(8) }
            conteneur.addView(vue)
        }
    }

    private fun ligneHistorique(ligne: Ligne): View {
        val couleur = ContextCompat.getColor(this, teinteDe(ligne.decision))
        val bloc = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.fond_carte)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        bloc.addView(
            TextView(this).apply {
                text = heure.format(Date(ligne.horodatage))
                textSize = 12f
                alpha = 0.6f
                setPadding(0, 0, dp(12), 0)
            }
        )
        val milieu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            )
        }
        milieu.addView(
            TextView(this).apply {
                text = "${fmt2(ligne.prix)} €"
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
            }
        )
        milieu.addView(
            TextView(this).apply {
                text = "${fmt1(ligne.kmRoules)} km · ${fmt0(ligne.minutes)} min · ${ligne.plateforme}"
                textSize = 11f
                alpha = 0.65f
            }
        )
        bloc.addView(milieu)

        val droite = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        droite.addView(
            TextView(this).apply {
                text = "${fmt2(ligne.euroKm)} €/km"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(couleur)
                gravity = Gravity.END
            }
        )
        droite.addView(
            TextView(this).apply {
                text = ligne.decision
                textSize = 11f
                setTextColor(couleur)
                gravity = Gravity.END
            }
        )
        bloc.addView(droite)
        return bloc
    }

    // --- Permissions ---------------------------------------------------------

    private fun accesNotifications(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun etatPermissions() {
        etat(R.id.etat_notif, R.id.bouton_notif, accesNotifications())
        etat(R.id.etat_superposition, R.id.bouton_superposition, Settings.canDrawOverlays(this))
        etat(R.id.etat_ecran, R.id.bouton_ecran, lectureEcranActive())
        peuplerSante()
        findViewById<TextView>(R.id.diagnostic).text = diagnostic()
        BoutonFlottant.synchroniser(this)
    }

    // --- Tableau de santé ----------------------------------------------------

    /** Un organe et son état, tels qu'ils s'affichent dans le tableau. */
    private class Organe(
        val nom: String,
        val etat: String,
        /** Vert : rien à faire. Ambre : dégradé. Rouge : rien ne marchera. */
        val couleur: Int,
        /** Ce qu'un appui doit ouvrir, s'il y a quelque chose à ouvrir. */
        val action: (() -> Unit)? = null,
    )

    /**
     * L'état réel, ligne par ligne, et non une liste de phrases.
     *
     * La distinction décisive est celle entre *autorisé* et *lié* : Android
     * affiche un service d'accessibilité comme coché alors qu'il ne reçoit
     * plus rien. C'est la panne la plus déroutante qui soit — tout paraît en
     * ordre, et aucune offre n'arrive — donc elle mérite une puce rouge, pas
     * une ligne de texte perdue au milieu de quatre autres.
     */
    private fun peuplerSante() {
        val conteneur = findViewById<LinearLayout>(R.id.conteneur_sante)
        conteneur.removeAllViews()
        val reglages = Reglages(this)

        val organes = listOf(
            Organe(
                getString(R.string.sante_ecran),
                when {
                    !lectureEcranActive() -> getString(R.string.service_absent)
                    LectureEcran.lie -> getString(R.string.service_lie)
                    else -> getString(R.string.service_non_lie)
                },
                when {
                    !lectureEcranActive() -> R.color.rouge
                    LectureEcran.lie -> R.color.vert
                    else -> R.color.ambre
                },
                { ouvrir(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            ),
            Organe(
                getString(R.string.sante_superposition),
                if (Settings.canDrawOverlays(this)) {
                    getString(R.string.accorde)
                } else {
                    getString(R.string.a_accorder)
                },
                if (Settings.canDrawOverlays(this)) R.color.vert else R.color.rouge,
                {
                    ouvrir(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"),
                        )
                    )
                },
            ),
            Organe(
                getString(R.string.sante_notifications),
                when {
                    !accesNotifications() -> getString(R.string.service_absent)
                    EcouteNotifications.lie -> getString(R.string.service_lie)
                    else -> getString(R.string.service_non_lie)
                },
                when {
                    !accesNotifications() -> R.color.ambre
                    EcouteNotifications.lie -> R.color.vert
                    else -> R.color.ambre
                },
                { ouvrir(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            ),
            Organe(
                getString(R.string.sante_batterie),
                if (batterieLibre()) {
                    getString(R.string.batterie_desactivee)
                } else {
                    getString(R.string.batterie_active)
                },
                if (batterieLibre()) R.color.vert else R.color.ambre,
                { demanderExemptionBatterie() },
            ),
            Organe(
                getString(R.string.sante_veille),
                if (reglages.veille) getString(R.string.sante_ok) else getString(R.string.sante_eteint),
                if (reglages.veille) R.color.vert else R.color.gris,
            ),
            Organe(
                getString(R.string.sante_ocr),
                when {
                    !Ocr.disponible -> getString(R.string.sante_indisponible)
                    reglages.ocrSecours -> getString(R.string.sante_ok)
                    else -> getString(R.string.sante_eteint)
                },
                when {
                    !Ocr.disponible -> R.color.gris
                    reglages.ocrSecours -> R.color.vert
                    else -> R.color.gris
                },
            ),
        )

        for (organe in organes) conteneur.addView(ligneSante(organe))
    }

    private fun ligneSante(organe: Organe): View {
        val teinte = ContextCompat.getColor(this, organe.couleur)
        val ligne = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(9), dp(8), dp(9))
            organe.action?.let { action ->
                isClickable = true
                setOnClickListener { action() }
            }
        }
        ligne.addView(
            View(this).apply {
                background = ContextCompat.getDrawable(context, R.drawable.fond_puce)
                backgroundTintList = ColorStateList.valueOf(teinte)
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                    marginEnd = dp(10)
                }
            }
        )
        ligne.addView(
            TextView(this).apply {
                text = organe.nom
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        ligne.addView(
            TextView(this).apply {
                text = organe.etat
                textSize = 12f
                setTextColor(teinte)
                gravity = Gravity.END
            }
        )
        return ligne
    }

    /** Ce qui reste utile sans mériter une puce : des compteurs. */
    private fun diagnostic(): String {
        val reglages = Reglages(this)
        return listOf(
            "Pastille : " + if (BoutonFlottant.visible) "affichée" else "masquée",
            "Écoute : " + if (reglages.ecouteToutesApps) {
                "toutes les applications"
            } else {
                "${reglages.paquets.size} application(s) cochée(s)"
            },
            "Notifications repérées : ${Journal.notificationsVues(this).size}",
        ).joinToString(" · ")
    }

    /**
     * L'application est-elle exemptée des restrictions d'énergie ?
     *
     * Un service d'accessibilité n'est pas mis en veille par le Doze — c'est
     * le système qui le maintient lié. Mais les surcouches constructeur, qui
     * arrêtent des processus entiers selon leurs propres règles, consultent
     * bien cette liste. L'exemption ne garantit rien ; c'est simplement le
     * seul levier qu'une application possède.
     */
    private fun batterieLibre(): Boolean = try {
        val energie = getSystemService(Context.POWER_SERVICE) as PowerManager
        energie.isIgnoringBatteryOptimizations(packageName)
    } catch (e: Exception) {
        false
    }

    private fun demanderExemptionBatterie() {
        if (batterieLibre()) {
            ouvrir(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            return
        }
        // L'intention ciblée demande directement l'exemption ; si le
        // constructeur l'a retirée, on retombe sur la liste complète.
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                )
            )
        } catch (e: Exception) {
            ouvrir(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /**
     * Le système ne donne pas d'API directe : on lit la liste des services
     * d'accessibilité activés, où le nôtre apparaît sous forme de composant.
     */
    private fun lectureEcranActive(): Boolean {
        val actifs = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val attendu = ComponentName(this, LectureEcran::class.java)
        return actifs.split(':').any {
            ComponentName.unflattenFromString(it.trim()) == attendu
        }
    }

    private fun etat(idTexte: Int, idBouton: Int, accorde: Boolean) {
        val texte = findViewById<TextView>(idTexte)
        texte.text = getString(if (accorde) R.string.accorde else R.string.a_accorder)
        texte.setTextColor(
            ContextCompat.getColor(this, if (accorde) R.color.vert else R.color.rouge)
        )
        findViewById<Button>(idBouton).isEnabled = !accorde
    }

    /** La notification de repli n'a de sens que si on a le droit d'en poster. */
    private fun demanderNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val accorde = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (accorde != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1,
            )
        }
    }

    private fun versionLisible(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName?.let { "v$it" }.orEmpty()
    } catch (e: Exception) {
        ""
    }

    // --- Barème --------------------------------------------------------------

    /**
     * Où ranger un paramètre du barème.
     *
     * La distinction n'est pas décorative : on ne règle pas son objectif
     * horaire dans le même état d'esprit qu'on décide du temps d'attente
     * toléré sur place. Le premier se pense une fois, les seconds se
     * retouchent après une mauvaise soirée.
     */
    private enum class Rubrique { RENTABILITE, COURSE }

    /** Un paramètre du barème, tel qu'il apparaît à l'écran. */
    private class Champ(
        val titre: String,
        val aide: String,
        /** Coefficient d'affichage : 100 pour montrer une fraction en pourcents. */
        val facteur: Double,
        val rubrique: Rubrique,
        val lire: (Bareme) -> Double,
        val ecrire: (Bareme, Double) -> Bareme,
    )

    private val descripteurs = listOf(
        Champ(
            "Objectif, en € nets par heure",
            "Ce que tu veux qu'il te reste par heure de travail, carburant et usure déduits. C'est l'étalon de tout le reste.",
            1.0, Rubrique.RENTABILITE, { it.objectifHeure }, { b, v -> b.copy(objectifHeure = v) },
        ),
        Champ(
            "Plancher, en € par km roulé",
            "Un veto, et rien d'autre : il refuse une course, il n'en autorise jamais. Laisse 0 pour le désactiver. Attention, il défavorise mécaniquement les courses longues, qui étalent leur temps mort sur plus de kilomètres payés.",
            1.0, Rubrique.RENTABILITE,
            { it.plancherEuroKm }, { b, v -> b.copy(plancherEuroKm = v.coerceIn(0.0, 5.0)) },
        ),
        Champ(
            "Carburant, en € par km",
            "Le ticket de la station divisé par les kilomètres du plein. Le seul des trois postes qu'on connaisse au centime.",
            1.0, Rubrique.RENTABILITE,
            { it.coutCarburant }, { b, v -> b.copy(coutCarburant = v.coerceAtLeast(0.0)) },
        ),
        Champ(
            "Usure et entretien, en € par km",
            "Pneus, freins, révisions, embrayage. Invisible au quotidien et parfaitement réel : un jeu de pneus tous les 40 000 km, c'est déjà deux centimes du kilomètre.",
            1.0, Rubrique.RENTABILITE,
            { it.coutUsure }, { b, v -> b.copy(coutUsure = v.coerceAtLeast(0.0)) },
        ),
        Champ(
            "Coûts fixes, en € par km",
            "Assurance, licence, amortissement, ramenés au kilomètre. Ils tombent que la voiture roule ou non : plus tu roules, moins ils pèsent par kilomètre.",
            1.0, Rubrique.RENTABILITE,
            { it.coutFixes }, { b, v -> b.copy(coutFixes = v.coerceAtLeast(0.0)) },
        ),
        Champ(
            "Commission prélevée, en %",
            "À laisser à 0 si l'offre annonce déjà ta part et non le prix client — c'est le cas d'Uber, qui écrit « Montant net de frais ».",
            100.0, Rubrique.RENTABILITE, { it.commission }, { b, v -> b.copy(commission = v.coerceIn(0.0, 0.9)) },
        ),
        Champ(
            "Prix plancher, en €",
            "En dessous, la course est refusée : l'usure mange la recette.",
            1.0, Rubrique.RENTABILITE, { it.prixPlancher }, { b, v -> b.copy(prixPlancher = v) },
        ),
        Champ(
            "Zone « limite », en ± %",
            "Largeur de la bande orange autour de ton objectif.",
            100.0, Rubrique.RENTABILITE, { it.marge }, { b, v -> b.copy(marge = v.coerceIn(0.0, 0.5)) },
        ),
        Champ(
            "Retour à vide, en % du trajet",
            "La part du trajet qu'il faudra refaire à vide pour se repositionner. 0 si tu enchaînes toujours sur place, 100 si tu reviens systématiquement à ton point de départ. C'est ce qui distingue une course rentable d'une course qui t'exile.",
            100.0, Rubrique.COURSE, { it.partRetour }, { b, v -> b.copy(partRetour = v.coerceIn(0.0, 2.0)) },
        ),
        Champ(
            "Attente au ramassage, en min",
            "Le temps mort entre l'arrivée sur place et le départ réel.",
            1.0, Rubrique.COURSE, { it.minutesAttente }, { b, v -> b.copy(minutesAttente = v) },
        ),
        Champ(
            "Approche maximale, en min",
            "Au-delà, la course est refusée quel que soit le prix : trop de temps non payé.",
            1.0, Rubrique.COURSE, { it.approcheMaxMinutes }, { b, v -> b.copy(approcheMaxMinutes = v) },
        ),
        Champ(
            "Vitesse supposée, en km/h",
            "Sert seulement à compléter une donnée absente de la notification.",
            1.0, Rubrique.COURSE, { it.vitesseParDefaut }, { b, v -> b.copy(vitesseParDefaut = v.coerceAtLeast(5.0)) },
        ),
    )

    /** Réglage d'affichage, hors barème économique. */
    private lateinit var champSecondes: EditText

    private fun construireChamps() {
        val conteneurs = mapOf(
            Rubrique.RENTABILITE to findViewById<LinearLayout>(R.id.conteneur_rentabilite),
            Rubrique.COURSE to findViewById<LinearLayout>(R.id.conteneur_course),
        )
        for (d in descripteurs) {
            val conteneur = conteneurs.getValue(d.rubrique)
            val ligne = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            val libelle = TextView(this).apply {
                text = d.titre
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val saisie = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                gravity = Gravity.END
                textSize = 17f
                layoutParams = LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            ligne.addView(libelle)
            ligne.addView(saisie)

            val aide = TextView(this).apply {
                text = d.aide
                textSize = 12f
                alpha = 0.65f
                setPadding(0, dp(2), dp(96), dp(6))
            }

            conteneur.addView(ligne)
            conteneur.addView(aide)
            champs += d to saisie
        }
        champSecondes = findViewById(R.id.champ_secondes)
    }

    private fun peuplerChamps() {
        val b = reglages.bareme
        for ((d, saisie) in champs) {
            if (saisie.hasFocus()) continue
            saisie.setText(affiche(d.lire(b) * d.facteur))
        }
        if (!champSecondes.hasFocus()) {
            champSecondes.setText(reglages.secondesAffichage.toString())
        }
    }

    private fun enregistrerChamps() {
        var b = reglages.bareme
        for ((d, saisie) in champs) {
            val valeur = saisie.text.toString().replace(',', '.').toDoubleOrNull() ?: continue
            b = d.ecrire(b, valeur / d.facteur)
        }
        reglages.bareme = b
        champSecondes.text.toString().toIntOrNull()?.let { reglages.secondesAffichage = it }
    }

    /** « 25,00 » → « 25 », « 12,50 » → « 12,5 », « 0,22 » inchangé. */
    private fun affiche(v: Double): String {
        val s = fmt2(v).removeSuffix(",00")
        return if (s.contains(',') && s.endsWith("0")) s.dropLast(1) else s
    }

    // --- Simulateur ----------------------------------------------------------

    /**
     * Un curseur du simulateur : ce qui se déforme, et entre quelles bornes.
     *
     * Le simulateur a changé de nature avec la maquette, et c'est un progrès.
     * Il déformait le **barème** sur une course figée ; il déforme désormais
     * la **course** sur un barème figé. On ne cherche plus « quel réglage me
     * convient » mais « à partir de quel prix cette course-là devient
     * bonne », qui est la question qu'on se pose vraiment au volant.
     */
    private class Curseur(
        val titre: Int,
        val minimum: Double,
        val maximum: Double,
        val unite: String,
        val decimales: Int,
        val lire: (Simulation) -> Double,
        val ecrire: (Simulation, Double) -> Simulation,
    )

    /** La course fictive qu'on déforme. Les valeurs d'origine sont celles de la maquette. */
    private data class Simulation(
        val prix: Double = 15.0,
        val kmApproche: Double = 2.0,
        val kmTrajet: Double = 6.0,
        val minutesTrajet: Double = 18.0,
    ) {
        fun versCourse(bareme: Bareme): Course = Course(
            plateforme = "Simulation",
            prix = prix,
            kmApproche = kmApproche,
            minutesApproche = kmApproche / bareme.vitesseParDefaut * 60.0,
            kmTrajet = kmTrajet,
            minutesTrajet = minutesTrajet,
        )
    }

    private var simulation = Simulation()

    private val curseurs = listOf(
        Curseur(
            R.string.curseur_prix, 5.0, 50.0, "€", 2,
            { it.prix }, { s, v -> s.copy(prix = v) },
        ),
        Curseur(
            R.string.curseur_approche, 0.0, 10.0, "km", 1,
            { it.kmApproche }, { s, v -> s.copy(kmApproche = v) },
        ),
        Curseur(
            R.string.curseur_distance, 0.5, 50.0, "km", 1,
            { it.kmTrajet }, { s, v -> s.copy(kmTrajet = v) },
        ),
        Curseur(
            R.string.curseur_duree, 1.0, 120.0, "min", 0,
            { it.minutesTrajet }, { s, v -> s.copy(minutesTrajet = v) },
        ),
    )

    private val etiquettes = mutableListOf<Pair<Curseur, TextView>>()

    private fun construireCurseurs() {
        val conteneur = findViewById<LinearLayout>(R.id.conteneur_curseurs)
        for (c in curseurs) {
            val etiquette = TextView(this).apply {
                textSize = 13f
                setPadding(0, dp(8), 0, 0)
            }
            val glissiere = SeekBar(this).apply {
                max = PAS_CURSEUR
                progress = versPas(c, c.lire(simulation))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(barre: SeekBar, valeur: Int, duUtilisateur: Boolean) {
                        if (!duUtilisateur) return
                        simulation = c.ecrire(simulation, depuisPas(c, valeur))
                        rafraichirSimulateur()
                    }

                    override fun onStartTrackingTouch(barre: SeekBar) = Unit
                    override fun onStopTrackingTouch(barre: SeekBar) = Unit
                })
            }
            conteneur.addView(etiquette)
            conteneur.addView(glissiere)
            etiquettes += c to etiquette
        }
    }

    private fun versPas(c: Curseur, valeur: Double): Int =
        (((valeur - c.minimum) / (c.maximum - c.minimum)) * PAS_CURSEUR)
            .toInt().coerceIn(0, PAS_CURSEUR)

    private fun depuisPas(c: Curseur, pas: Int): Double =
        c.minimum + (c.maximum - c.minimum) * pas / PAS_CURSEUR

    /**
     * Recalcule le verdict de la course fictive et l'affiche.
     *
     * Le calcul passe par le moteur réel, pas par une approximation
     * d'aperçu : un simulateur qui ne simule pas exactement induit en erreur
     * plus sûrement qu'il ne renseigne.
     */
    private fun rafraichirSimulateur() {
        val bareme = reglages.bareme
        for ((c, etiquette) in etiquettes) {
            val valeur = c.lire(simulation)
            etiquette.text = "${getString(c.titre)} : ${arrondi(valeur, c.decimales)} ${c.unite}"
        }

        val verdict = Arbitre.arbitrer(simulation.versCourse(bareme), bareme)
        val carte = findViewById<LinearLayout>(R.id.carte_resultat)
        carte.removeAllViews()

        val couleur = ContextCompat.getColor(
            this,
            when (verdict.decision) {
                Decision.PRENDS -> R.color.vert
                Decision.LIMITE -> R.color.ambre
                Decision.LAISSE -> R.color.rouge
                Decision.INCOMPLET -> R.color.gris
            },
        )

        carte.addView(
            TextView(this).apply {
                text = getString(R.string.resultat_simulation)
                textSize = 12f
                alpha = 0.65f
            }
        )
        carte.addView(
            TextView(this).apply {
                text = "${fmt2(verdict.euroKmRoule)} €/km"
                textSize = 32f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(couleur)
            }
        )
        carte.addView(
            TextView(this).apply {
                text = "${fmt0(verdict.euroHeure)} €/h  ·  ${verdict.decision.libelle}"
                textSize = 16f
                setTextColor(couleur)
            }
        )
        carte.addView(
            troisColonnes(
                "${fmt2(coutTotal(verdict))} €" to "coûts estimés",
                "${fmt2(verdict.revenuNet)} €" to "gain net",
                "${fmt0(verdict.minutesTotal)} min" to "temps total",
            )
        )
        carte.addView(
            TextView(this).apply {
                text = getString(R.string.pourquoi)
                textSize = 12f
                alpha = 0.65f
                setPadding(0, dp(14), 0, dp(4))
            }
        )
        carte.addView(raisons(verdict, bareme))
        verdict.motif?.let { motif ->
            carte.addView(
                TextView(this).apply {
                    text = motif
                    textSize = 13f
                    setTextColor(couleur)
                    setPadding(0, dp(8), 0, 0)
                }
            )
        }
    }

    private fun coutTotal(verdict: Verdict): Double? {
        val postes = listOfNotNull(verdict.coutCarburant, verdict.coutUsure, verdict.coutFixes)
        return if (postes.size == 3) postes.sum() else null
    }

    /**
     * Les trois raisons, en pilules.
     *
     * Chacune se lit contre un seuil que le chauffeur a lui-même réglé —
     * l'objectif horaire, le plancher d'euro/kilomètre, la part de temps mort
     * au-delà de laquelle le moteur alerte déjà. Aucun seuil n'est inventé
     * pour la circonstance : une pilule qui jugerait selon un chiffre venu de
     * nulle part serait pire que pas de pilule du tout.
     */
    private fun raisons(verdict: Verdict, bareme: Bareme): View {
        val ligne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val euroHeure = verdict.euroHeure
        val euroKm = verdict.euroKmRoule
        val part = verdict.partMorte

        val pilules = listOf(
            Triple(
                "${fmt0(euroHeure)} €/h",
                euroHeure != null && euroHeure >= bareme.objectifHeure,
                euroHeure != null,
            ),
            Triple(
                "${fmt2(euroKm)} €/km",
                euroKm != null && (bareme.plancherEuroKm <= 0.0 || euroKm >= bareme.plancherEuroKm),
                euroKm != null,
            ),
            Triple(
                "${fmt0((part ?: 0.0) * 100)} % non payé",
                part != null && part <= 0.45,
                part != null,
            ),
        )
        for ((index, p) in pilules.withIndex()) {
            val (texte, bon, connu) = p
            val vue = chip(texte, if (!connu) R.color.gris else if (bon) R.color.vert else R.color.ambre)
            vue.layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            ).apply { if (index > 0) marginStart = dp(8) }
            ligne.addView(vue)
        }
        return ligne
    }

    private fun arrondi(valeur: Double, decimales: Int): String = when (decimales) {
        0 -> fmt0(valeur)
        1 -> fmt1(valeur)
        else -> fmt2(valeur)
    }

    // --- Applications écoutées -----------------------------------------------

    private fun peuplerApplications() {
        val conteneur = findViewById<LinearLayout>(R.id.conteneur_apps)
        conteneur.removeAllViews()

        val ecoutes = reglages.paquets
        val connus = (Plateformes.PAR_DEFAUT.keys + ecoutes).sorted()
        for (paquet in connus) {
            conteneur.addView(caseApplication(paquet, paquet in ecoutes))
        }

        val decouverts = Journal.inconnus(this).filter { it !in connus }
        if (decouverts.isEmpty()) return

        conteneur.addView(TextView(this).apply {
            text = getString(R.string.apps_reperees)
            textSize = 13f
            alpha = 0.7f
            setPadding(0, dp(12), 0, dp(2))
        })
        for (paquet in decouverts.sorted()) {
            conteneur.addView(caseApplication(paquet, false))
        }
    }

    private fun caseApplication(paquet: String, coche: Boolean): View {
        val nom = Plateformes.PAR_DEFAUT[paquet]
        return CheckBox(this).apply {
            text = if (nom != null) "$nom — $paquet" else paquet
            textSize = 14f
            isChecked = coche
            setOnCheckedChangeListener { _, actif ->
                if (actif) {
                    reglages.ajouterPaquet(paquet)
                    Journal.oublierInconnu(this@ActivitePrincipale, paquet)
                } else {
                    reglages.retirerPaquet(paquet)
                }
                // Le filtre d'écoute est appliqué par le système : sans ce
                // rappel, une application tout juste cochée n'enverrait
                // jamais le premier événement.
                LectureEcran.rafraichirFiltre()
            }
        }
    }

    // --- Divers --------------------------------------------------------------

    /**
     * Pose la bulle sur la course du simulateur : le meilleur moyen de la
     * placer où on veut, et de vérifier son réglage sans attendre une offre.
     */
    private fun essai() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.superposition_requise, Toast.LENGTH_LONG).show()
            return
        }
        enregistrerChamps()
        val bareme = reglages.bareme
        val verdict = Arbitre.arbitrer(simulation.versCourse(bareme), bareme)
        Bulle.afficher(this, verdict, 180L, Source.ESSAI, reglages)
    }

    private fun interrupteur(id: Int, valeur: Boolean, action: (Boolean) -> Unit) {
        findViewById<SwitchCompat>(id).apply {
            isChecked = valeur
            setOnCheckedChangeListener { _, coche -> action(coche) }
        }
    }

    private fun ouvrir(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.reglages_introuvables, Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(valeur: Int): Int = (valeur * resources.displayMetrics.density).toInt()

    private companion object {
        /** Finesse des curseurs : assez pour le centime, pas plus. */
        const val PAS_CURSEUR = 200
    }
}
