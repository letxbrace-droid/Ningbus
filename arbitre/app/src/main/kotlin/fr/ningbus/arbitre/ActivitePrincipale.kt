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
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
import androidx.core.graphics.ColorUtils
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
        findViewById<View>(R.id.bouton_test).apply {
            isClickable = true
            setOnClickListener { essai() }
        }
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
        construireResultat()
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
        // La barre flotte au-dessus du fond : sur une dalle sans bordure,
        // une barre collée en bas se confond avec le trait de navigation du
        // système, et cesse d'appartenir à l'application.
        barre.background = Peinture.carte(
            this,
            ContextCompat.getColor(this, R.color.primaire),
            intensite = 0.04f,
        )
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
     * reconstruire une section à chaque appui ferait perdre la position de
     * défilement — le genre de détail qu'on ne remarque que lorsqu'il manque.
     *
     * Le fondu et les quelques pixels de remontée ne sont pas de la
     * décoration : sans eux, l'écran change d'un coup et l'œil ne sait pas
     * s'il a changé d'onglet ou si l'application a sauté. Deux cents
     * millisecondes suffisent à le dire, et restent sous le seuil où
     * l'attente devient perceptible.
     */
    private fun afficher(onglet: Onglet) {
        val premier = ongletCourant == onglet && !dejaAffiche
        dejaAffiche = true
        ongletCourant = onglet

        for (autre in Onglet.entries) {
            val vue = findViewById<View>(autre.section)
            if (autre != onglet) {
                vue.visibility = View.GONE
                continue
            }
            if (vue.visibility == View.VISIBLE && !premier) continue
            vue.alpha = 0f
            vue.translationY = dp(12).toFloat()
            vue.visibility = View.VISIBLE
            vue.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(DUREE_ONGLET)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        val actif = ContextCompat.getColor(this, R.color.primaire)
        val dormant = ContextCompat.getColor(this, R.color.gris)
        for ((cible, vue) in ongletsPeints) {
            val choisi = cible == onglet
            val teinte = if (choisi) actif else dormant
            val colonne = vue as LinearLayout
            val icone = colonne.getChildAt(0) as ImageView
            icone.imageTintList = ColorStateList.valueOf(teinte)
            (colonne.getChildAt(1) as TextView).setTextColor(teinte)

            // L'icône de l'onglet choisi enfle très légèrement. C'est le
            // retour tactile qui manque à une barre plate : on voit ce qu'on
            // vient de toucher avant même d'avoir lu le libellé.
            icone.animate()
                .scaleX(if (choisi) 1.15f else 1f)
                .scaleY(if (choisi) 1.15f else 1f)
                .setDuration(DUREE_ONGLET)
                .setInterpolator(OvershootInterpolator(1.6f))
                .start()
        }
    }

    private var dejaAffiche = false

    /** L'ondulation du système, pour tout ce qui réagit au doigt. */
    private fun ondulation(vue: View) {
        val attributs = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        vue.background = attributs.getDrawable(0)
        attributs.recycle()
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

        peuplerEtiquetteLigne(couleur, !reglages.actif || enPanne)
        peuplerCarteEtat(couleur, enPanne)
        peuplerDerniereCourse()
        peuplerAtouts()
    }

    /** Le point « En ligne » de l'en-tête : l'état, avant même de lire. */
    private fun peuplerEtiquetteLigne(couleur: Int, ennui: Boolean) {
        findViewById<ImageView>(R.id.marque).imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primaire))
        findViewById<ImageView>(R.id.icone_derniere).imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primaire))

        findViewById<TextView>(R.id.etiquette_ligne).apply {
            text = getString(if (ennui) R.string.hors_ligne else R.string.en_ligne)
            setTextColor(couleur)
            val puce = ContextCompat.getDrawable(context, R.drawable.fond_puce)?.mutate()
            puce?.setTint(couleur)
            puce?.setBounds(0, 0, dp(8), dp(8))
            setCompoundDrawables(puce, null, null, null)
        }
    }

    /**
     * La carte d'état, avec son halo.
     *
     * Elle est la première chose qu'on regarde en ouvrant l'application, et
     * souvent la seule : le halo permet de conclure sans lire un mot, ce qui
     * est exactement ce qu'on demande à un voyant.
     */
    private fun peuplerCarteEtat(couleur: Int, enPanne: Boolean) {
        val carte = findViewById<LinearLayout>(R.id.carte_etat)
        carte.removeAllViews()
        carte.background = Peinture.lueur(this, couleur, intensite = 0.08f)

        val entete = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        entete.addView(
            View(this).apply {
                background = ContextCompat.getDrawable(context, R.drawable.fond_puce)
                backgroundTintList = ColorStateList.valueOf(couleur)
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                    marginEnd = dp(10)
                }
            }
        )
        entete.addView(
            TextView(this).apply {
                text = getString(
                    when {
                        !reglages.actif -> R.string.arbitre_eteint
                        enPanne -> R.string.arbitre_en_panne
                        else -> R.string.arbitre_actif
                    }
                ).uppercase()
                textSize = 19f
                letterSpacing = 0.02f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(couleur)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        carte.addView(entete)

        carte.addView(
            TextView(this).apply {
                text = when {
                    !reglages.actif -> getString(R.string.arbitre_eteint_detail)
                    enPanne -> getString(R.string.service_non_lie)
                    else -> getString(R.string.arbitre_actif_detail)
                }
                textSize = 13f
                alpha = 0.8f
                setPadding(0, dp(4), 0, 0)
            }
        )
        carte.addView(voyants())
    }

    /** Les trois voyants qui décident si une offre sera vue, ou non. */
    private fun voyants(): View {
        val ligne = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        val voyants = listOf(
            Triple(R.string.chip_accessibilite, R.drawable.ic_ecran, lectureEcranActive() && LectureEcran.lie)
                to { ouvrir(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            Triple(R.string.chip_image, R.drawable.ic_image, Ocr.disponible && reglages.ocrSecours)
                to { afficher(Onglet.REGLAGES) },
            Triple(R.string.chip_detection, R.drawable.ic_cible, reglages.actif && reglages.filtrerEcrans)
                to { afficher(Onglet.REGLAGES) },
        )
        for ((index, entree) in voyants.withIndex()) {
            val (description, action) = entree
            val (libelle, icone, bon) = description
            val teinte = ContextCompat.getColor(this, if (bon) R.color.vert else R.color.ambre)

            val pilule = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.fond_chip_cliquable)
                setPadding(dp(8), dp(9), dp(8), dp(9))
                isClickable = true
                setOnClickListener { action() }
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                ).apply { if (index > 0) marginStart = dp(8) }
            }
            pilule.addView(
                ImageView(this).apply {
                    setImageResource(icone)
                    imageTintList = ColorStateList.valueOf(teinte)
                    layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply {
                        marginEnd = dp(6)
                    }
                }
            )
            pilule.addView(
                TextView(this).apply {
                    text = getString(libelle)
                    textSize = 12f
                    setTextColor(teinte)
                }
            )
            ligne.addView(pilule)
        }
        return ligne
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

        // La carte rayonne de la couleur de son verdict. C'est ce qui manquait
        // le plus : un cockpit où seul le chiffre était coloré se lisait comme
        // un tableau, et il faut qu'il se lise comme un feu.
        carte.background = Peinture.lueur(this, couleur)
        carte.alpha = 0f
        carte.animate().alpha(1f).setDuration(DUREE_ONGLET).start()

        // Étage 1 : l'euro par kilomètre, le verdict, puis l'euro/heure. Dans
        // cet ordre parce que le premier ne suppose aucune durée, et que c'est
        // celui qu'un chauffeur compare d'une plateforme à l'autre.
        val entete = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        entete.addView(
            TextView(this).apply {
                text = "${fmt2(ligne.euroKm)} €/km"
                textSize = 34f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(couleur)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        entete.addView(pastilleVerdict(ligne.decision, couleur))
        carte.addView(entete)

        carte.addView(
            TextView(this).apply {
                text = "${fmt0(ligne.euroHeure)} €/h"
                textSize = 16f
                setTextColor(couleur)
                alpha = 0.85f
            }
        )

        carte.addView(separateur(couleur))

        // Étage 2 : les chiffres de l'offre, tels qu'elle les annonçait.
        carte.addView(
            grille(
                Tuile(R.drawable.ic_euro, "${fmt2(ligne.prix)} €", getString(R.string.legende_prix)),
                Tuile(R.drawable.ic_route, "${fmt1(ligne.kmRoules)} km", getString(R.string.legende_roules)),
                Tuile(R.drawable.ic_horloge, "${fmt0(ligne.minutes)} min", getString(R.string.legende_mobilisees)),
            )
        )

        // Étage 3 : les kilomètres séparés — c'est là que se voit l'exil.
        carte.addView(
            grille(
                Tuile(R.drawable.ic_approche, "${fmt1(ligne.kmApproche)} km", getString(R.string.legende_approche)),
                Tuile(R.drawable.ic_client, "${fmt1(ligne.kmCourse)} km", getString(R.string.legende_bord)),
                Tuile(null, "${ligne.confiance} %", getString(R.string.legende_confiance), ligne.confiance),
            )
        )

        if (ligne.motif.isNotEmpty()) carte.addView(bandeau(ligne.motif, couleur))

        if (reglages.detailsCouts && ligne.cout != null) {
            carte.addView(
                grille(
                    Tuile(R.drawable.ic_carburant, "${fmt2(ligne.cout)} €", getString(R.string.legende_cout)),
                    Tuile(R.drawable.ic_gain, "${fmt2(ligne.revenuNet)} €", getString(R.string.legende_gain)),
                    Tuile(R.drawable.ic_etoile, "${ligne.scoreOffre}/100", getString(R.string.legende_score)),
                )
            )
        }
    }

    /** Le verdict en pilule, avec son signe : ✓ pour prendre, ✕ pour laisser. */
    private fun pastilleVerdict(decision: String, couleur: Int): View {
        val pilule = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Peinture.bandeau(this@ActivitePrincipale, couleur)
            setPadding(dp(10), dp(6), dp(12), dp(6))
        }
        pilule.addView(
            ImageView(this).apply {
                setImageResource(
                    when (decision) {
                        Decision.PRENDS.name -> R.drawable.ic_coche
                        Decision.LAISSE.name -> R.drawable.ic_croix
                        else -> R.drawable.ic_alerte
                    }
                )
                imageTintList = ColorStateList.valueOf(couleur)
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) }
            }
        )
        pilule.addView(
            TextView(this).apply {
                text = decision
                textSize = 13f
                letterSpacing = 0.05f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(couleur)
            }
        )
        return pilule
    }

    private fun separateur(couleur: Int): View = View(this).apply {
        setBackgroundColor(ColorUtils.setAlphaComponent(couleur, 60))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1),
        ).apply {
            topMargin = dp(14)
            bottomMargin = dp(2)
        }
    }

    private fun bandeau(texte: String, couleur: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Peinture.bandeau(this@ActivitePrincipale, couleur)
        setPadding(dp(10), dp(9), dp(10), dp(9))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) }

        addView(
            ImageView(this@ActivitePrincipale).apply {
                setImageResource(R.drawable.ic_info)
                imageTintList = ColorStateList.valueOf(couleur)
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) }
            }
        )
        addView(
            TextView(this@ActivitePrincipale).apply {
                text = texte
                textSize = 13f
                setTextColor(couleur)
            }
        )
    }

    /**
     * Une valeur du cockpit : une icône, un chiffre, une légende.
     *
     * L'icône n'est pas un ornement. Dans une grille de neuf chiffres, l'œil
     * retrouve « le carburant » à sa tuile bien avant d'avoir lu la légende —
     * et c'est cette relecture-là qu'on fait au feu rouge.
     *
     * @param confiance non nul : la tuile devient un anneau de progression.
     */
    private class Tuile(
        val icone: Int?,
        val valeur: String,
        val legende: String,
        val confiance: Int? = null,
    )

    private fun grille(vararg tuiles: Tuile): View {
        val ligne = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        val accent = ContextCompat.getColor(this, R.color.primaire)
        for (t in tuiles) {
            val colonne = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                )
            }
            if (t.confiance != null) {
                val vert = ContextCompat.getColor(this, R.color.vert)
                colonne.addView(
                    Anneau(this).apply {
                        pourcent = t.confiance
                        couleur = if (t.confiance >= 75) vert else
                            ContextCompat.getColor(this@ActivitePrincipale, R.color.ambre)
                        layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                            marginEnd = dp(8)
                        }
                    }
                )
            } else if (t.icone != null) {
                colonne.addView(
                    ImageView(this).apply {
                        setImageResource(t.icone)
                        imageTintList = ColorStateList.valueOf(accent)
                        background = Peinture.tuile(this@ActivitePrincipale, accent)
                        setPadding(dp(6), dp(6), dp(6), dp(6))
                        layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                            marginEnd = dp(8)
                        }
                    }
                )
            }
            val textes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            textes.addView(
                TextView(this).apply {
                    text = t.valeur
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                }
            )
            textes.addView(
                TextView(this).apply {
                    text = t.legende
                    textSize = 10f
                    alpha = 0.65f
                }
            )
            colonne.addView(textes)
            ligne.addView(colonne)
        }
        return ligne
    }

    /** Ce que l'application promet, en trois pilules. */
    private fun peuplerAtouts() {
        val conteneur = findViewById<LinearLayout>(R.id.conteneur_atouts)
        conteneur.removeAllViews()

        findViewById<View>(R.id.bouton_test).background = Peinture.degrade(
            this,
            ContextCompat.getColor(this, R.color.primaire),
            ContextCompat.getColor(this, R.color.bleu_clair),
        )

        val atouts = listOf(
            Triple(R.drawable.ic_graphique, R.string.atout_rapide, R.string.atout_rapide_detail),
            Triple(R.drawable.ic_bouclier, R.string.atout_prive, R.string.atout_prive_detail),
            Triple(R.drawable.ic_eclair, R.string.atout_revenus, R.string.atout_revenus_detail),
        )
        val accent = ContextCompat.getColor(this, R.color.primaire)
        for ((index, atout) in atouts.withIndex()) {
            val (icone, titre, detail) = atout
            val bloc = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.fond_carte)
                setPadding(dp(10), dp(12), dp(10), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                ).apply { if (index > 0) marginStart = dp(8) }
            }
            bloc.addView(
                ImageView(this).apply {
                    setImageResource(icone)
                    imageTintList = ColorStateList.valueOf(accent)
                    layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                }
            )
            bloc.addView(
                TextView(this).apply {
                    text = getString(titre)
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(6), 0, 0)
                }
            )
            bloc.addView(
                TextView(this).apply {
                    text = getString(detail)
                    textSize = 10f
                    alpha = 0.6f
                }
            )
            conteneur.addView(bloc)
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
            // Le verdict teinte la ligne entière, et non le seul chiffre de
            // droite : une liste de vingt courses se parcourt à la couleur,
            // pas à la lecture.
            background = Peinture.carte(this@ActivitePrincipale, couleur)
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
                ondulation(this)
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

    // Les vues du résultat, construites une seule fois.
    //
    // Elles l'étaient à chaque mouvement du doigt : une dizaine de vues
    // détruites et recréées par pixel de curseur, ce qui donnait exactement
    // la sensation poisseuse qu'un simulateur ne doit pas avoir. Elles ne
    // changent plus que de texte et de couleur.
    private lateinit var resultatEuroKm: TextView
    private lateinit var resultatHeure: TextView
    private lateinit var resultatMotif: TextView
    private lateinit var resultatChiffres: List<TextView>
    private lateinit var resultatRaisons: List<TextView>

    private fun construireResultat() {
        val carte = findViewById<LinearLayout>(R.id.carte_resultat)

        carte.addView(
            TextView(this).apply {
                text = getString(R.string.resultat_simulation)
                textSize = 12f
                alpha = 0.65f
            }
        )
        resultatEuroKm = TextView(this).apply {
            textSize = 32f
            setTypeface(typeface, Typeface.BOLD)
        }
        carte.addView(resultatEuroKm)

        resultatHeure = TextView(this).apply { textSize = 16f }
        carte.addView(resultatHeure)

        val chiffres = trio(listOf("coûts estimés", "gain net", "temps total"))
        resultatChiffres = chiffres.valeurs
        carte.addView(chiffres.vue)

        carte.addView(
            TextView(this).apply {
                text = getString(R.string.pourquoi)
                textSize = 12f
                alpha = 0.65f
                setPadding(0, dp(14), 0, dp(4))
            }
        )

        val raisons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        resultatRaisons = (0 until 3).map { index ->
            chip("", R.color.gris).also { pilule ->
                pilule.layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                ).apply { if (index > 0) marginStart = dp(8) }
                raisons.addView(pilule)
            }
        }
        carte.addView(raisons)

        resultatMotif = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        }
        carte.addView(resultatMotif)
    }

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
        val couleur = ContextCompat.getColor(
            this,
            when (verdict.decision) {
                Decision.PRENDS -> R.color.vert
                Decision.LIMITE -> R.color.ambre
                Decision.LAISSE -> R.color.rouge
                Decision.INCOMPLET -> R.color.gris
            },
        )

        findViewById<LinearLayout>(R.id.carte_resultat).background = Peinture.carte(this@ActivitePrincipale, couleur)

        resultatEuroKm.text = "${fmt2(verdict.euroKmRoule)} €/km"
        resultatEuroKm.setTextColor(couleur)
        resultatHeure.text = "${fmt0(verdict.euroHeure)} €/h  ·  ${verdict.decision.libelle}"
        resultatHeure.setTextColor(couleur)

        resultatChiffres[0].text = "${fmt2(coutTotal(verdict))} €"
        resultatChiffres[1].text = "${fmt2(verdict.revenuNet)} €"
        resultatChiffres[2].text = "${fmt0(verdict.minutesTotal)} min"

        peindreRaisons(verdict, bareme)

        resultatMotif.text = verdict.motif.orEmpty()
        resultatMotif.setTextColor(couleur)
        resultatMotif.visibility = if (verdict.motif == null) View.GONE else View.VISIBLE
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
    private fun peindreRaisons(verdict: Verdict, bareme: Bareme) {
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
                part != null && part <= SEUIL_TEMPS_MORT,
                part != null,
            ),
        )
        for ((pilule, p) in resultatRaisons.zip(pilules)) {
            val (texte, bon, connu) = p
            pilule.text = texte
            pilule.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (!connu) R.color.gris else if (bon) R.color.vert else R.color.ambre,
                )
            )
        }
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

        /**
         * Le même seuil de temps mort que le moteur, et non un second.
         * Une pilule qui jugerait selon un chiffre propre à l'écran dirait
         * autre chose que le verdict qu'elle accompagne.
         */
        const val SEUIL_TEMPS_MORT = 0.45

        /**
         * Durée d'une bascule d'onglet, en millisecondes.
         *
         * Assez pour que l'œil suive le mouvement, assez peu pour qu'on
         * n'attende pas : au-delà de 250 ms, une transition d'interface cesse
         * d'être ressentie comme une réponse et devient une animation.
         */
        const val DUREE_ONGLET = 200L
    }
}
