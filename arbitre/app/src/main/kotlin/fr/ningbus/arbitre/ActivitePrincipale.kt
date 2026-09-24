package fr.ningbus.arbitre

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import fr.ningbus.arbitre.moteur.Decision
import fr.ningbus.arbitre.moteur.Plateformes
import fr.ningbus.arbitre.moteur.fmt0
import fr.ningbus.arbitre.moteur.fmt2

/**
 * L'écran de réglages : les trois permissions à accorder, le barème du
 * chauffeur, les applications écoutées.
 *
 * Les champs numériques sont engendrés à partir d'une liste de descripteurs
 * plutôt que décrits un à un en XML : ajouter un paramètre au barème ne
 * demande alors qu'une ligne ici.
 */
class ActivitePrincipale : AppCompatActivity() {

    private lateinit var reglages: Reglages
    private val champs = mutableListOf<Pair<Champ, EditText>>()

    override fun onCreate(etat: Bundle?) {
        super.onCreate(etat)
        setContentView(R.layout.principale)
        reglages = Reglages(this)

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
        findViewById<Button>(R.id.bouton_planifiees).setOnClickListener {
            startActivity(Intent(this, ActivitePlanifiees::class.java))
        }
        findViewById<Button>(R.id.bouton_journal).setOnClickListener {
            startActivity(Intent(this, ActiviteJournal::class.java))
        }
        findViewById<Button>(R.id.bouton_defaut).setOnClickListener {
            reglages.bareme = Bareme()
            peuplerChamps()
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
        interrupteur(R.id.prudence, reglages.bareme.prudenceTrafic) {
            reglages.bareme = reglages.bareme.copy(prudenceTrafic = it)
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
    }

    override fun onResume() {
        super.onResume()
        etatPermissions()
        peuplerChamps()
        peuplerApplications()
        rafraichirApercu()
    }

    override fun onPause() {
        super.onPause()
        enregistrerChamps()
    }

    // --- Permissions --------------------------------------------------------

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

    // --- Tableau de santé ---------------------------------------------------

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
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        ligne.addView(
            TextView(this).apply {
                text = organe.etat
                textSize = 13f
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

    // --- Barème -------------------------------------------------------------

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
            "Coût de roulage, en € par km",
            "Carburant ou électricité, pneus, entretien, amortissement. Environ 0,22 en thermique, 0,10 en électrique rechargé à la maison.",
            1.0, Rubrique.RENTABILITE, { it.coutKm }, { b, v -> b.copy(coutKm = v) },
        ),
        Champ(
            "Commission prélevée, en %",
            "À laisser à 0 si l'offre annonce déjà ta part et non le prix client — c'est le cas d'Uber, qui écrit « Montant net de frais ».",
            100.0, Rubrique.RENTABILITE, { it.commission }, { b, v -> b.copy(commission = v.coerceIn(0.0, 0.9)) },
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
                textSize = 15f
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
                alpha = 0.7f
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

    // --- Simulateur ---------------------------------------------------------

    /**
     * Un curseur du simulateur : un réglage, une plage, une unité.
     *
     * Les mêmes valeurs se saisissent au chiffre près plus bas. Le curseur
     * n'est pas un doublon pour autant : saisir « 0,18 » à la place de
     * « 0,22 » ne dit rien tant qu'on n'a pas vu ce que cela change. Le
     * verdict se recalcule sous le doigt, et c'est toute la différence entre
     * régler un barème et le comprendre.
     */
    private class Curseur(
        val titre: String,
        val minimum: Double,
        val maximum: Double,
        val unite: String,
        val decimales: Int,
        val lire: (Bareme) -> Double,
        val ecrire: (Bareme, Double) -> Bareme,
    )

    private val curseurs = listOf(
        Curseur(
            "Objectif", 10.0, 60.0, "€/h", 0,
            { it.objectifHeure }, { b, v -> b.copy(objectifHeure = v) },
        ),
        Curseur(
            "Coût de roulage", 0.0, 0.60, "€/km", 2,
            { it.coutKm }, { b, v -> b.copy(coutKm = v) },
        ),
        Curseur(
            "Retour à vide", 0.0, 100.0, "% du trajet", 0,
            { it.partRetour * 100.0 }, { b, v -> b.copy(partRetour = v / 100.0) },
        ),
    )

    private val etiquettes = mutableListOf<Pair<Curseur, TextView>>()

    private fun construireCurseurs() {
        findViewById<TextView>(R.id.course_essai).text = COURSE_ESSAI.replace('\n', ' ')
        val conteneur = findViewById<LinearLayout>(R.id.conteneur_curseurs)

        for (c in curseurs) {
            val etiquette = TextView(this).apply {
                textSize = 13f
                setPadding(0, dp(8), 0, 0)
            }
            val glissiere = SeekBar(this).apply {
                max = PAS_CURSEUR
                progress = versPas(c, c.lire(reglages.bareme))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(barre: SeekBar, valeur: Int, duUtilisateur: Boolean) {
                        if (!duUtilisateur) return
                        reglages.bareme = c.ecrire(reglages.bareme, depuisPas(c, valeur))
                        rafraichirApercu()
                        peuplerChamps()
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
    private fun rafraichirApercu() {
        val bareme = reglages.bareme
        for ((c, etiquette) in etiquettes) {
            val valeur = c.lire(bareme)
            etiquette.text = "${c.titre} : ${arrondi(valeur, c.decimales)} ${c.unite}"
        }

        val verdict = Arbitre.arbitrer(COURSE_ESSAI, "Essai", bareme)
        val teinte = ContextCompat.getColor(
            this,
            when (verdict.decision) {
                Decision.PRENDS -> R.color.vert
                Decision.LIMITE -> R.color.ambre
                Decision.LAISSE -> R.color.rouge
                Decision.INCOMPLET -> R.color.gris
            },
        )

        findViewById<TextView>(R.id.apercu_verdict).apply {
            text = buildString {
                append(verdict.decision.libelle)
                verdict.euroHeure?.let { append("  ·  ").append(fmt0(it)).append(" €/h") }
            }
            setTextColor(teinte)
        }
        findViewById<TextView>(R.id.apercu_detail).apply {
            text = verdict.resume
            setTextColor(teinte)
            alpha = 0.85f
        }
    }

    private fun arrondi(valeur: Double, decimales: Int): String =
        if (decimales == 0) fmt0(valeur) else fmt2(valeur)

    // --- Applications écoutées ----------------------------------------------

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
            alpha = 0.75f
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

    // --- Divers -------------------------------------------------------------

    /**
     * Affiche une course fictive : sert à placer la bulle où on veut et à
     * vérifier son réglage sans attendre une vraie offre.
     */
    private fun essai() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.superposition_requise, Toast.LENGTH_LONG).show()
            return
        }
        enregistrerChamps()
        val verdict = Arbitre.arbitrer(COURSE_ESSAI, "Essai", reglages.bareme)
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
        /**
         * La course du simulateur et du bouton d'essai — la même, pour que
         * l'aperçu chiffré et la bulle posée à l'écran disent la même chose.
         */
        const val COURSE_ESSAI =
            "UberX · 18,40 €\n5 min (2,1 km) de vous\n21 min (9,4 km) de trajet"

        /** Finesse des curseurs : assez pour le centime, pas plus. */
        const val PAS_CURSEUR = 200
    }
}
