package fr.ningbus.arbitre

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import fr.ningbus.arbitre.moteur.Arbitre
import fr.ningbus.arbitre.moteur.Bareme
import fr.ningbus.arbitre.moteur.Plateformes
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

        construireChamps()
        demanderNotifications()
    }

    override fun onResume() {
        super.onResume()
        etatPermissions()
        peuplerChamps()
        peuplerApplications()
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
        findViewById<TextView>(R.id.diagnostic).text = diagnostic()
        BoutonFlottant.synchroniser(this)
    }

    /**
     * Ce que les réglages système ne disent pas : un service peut être
     * autorisé sans être lié. C'est le cas le plus déroutant — tout paraît en
     * ordre et rien ne se produit — donc il mérite d'être affiché tel quel.
     */
    private fun diagnostic(): String {
        val reglages = Reglages(this)
        val lignes = listOf(
            "Lecture d'écran : " + when {
                !lectureEcranActive() -> getString(R.string.service_absent)
                LectureEcran.lie -> getString(R.string.service_lie)
                else -> getString(R.string.service_non_lie)
            },
            "Notifications : " + when {
                !accesNotifications() -> getString(R.string.service_absent)
                EcouteNotifications.lie -> getString(R.string.service_lie)
                else -> getString(R.string.service_non_lie)
            },
            "Pastille : " + if (BoutonFlottant.visible) "affichée" else "masquée",
            "Écoute : " + if (reglages.ecouteToutesApps) {
                "toutes les applications"
            } else {
                "${reglages.paquets.size} application(s) cochée(s)"
            },
            "Notifications repérées : ${Journal.notificationsVues(this).size}",
        )
        return lignes.joinToString("\n")
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

    /** Un paramètre du barème, tel qu'il apparaît à l'écran. */
    private class Champ(
        val titre: String,
        val aide: String,
        /** Coefficient d'affichage : 100 pour montrer une fraction en pourcents. */
        val facteur: Double,
        val lire: (Bareme) -> Double,
        val ecrire: (Bareme, Double) -> Bareme,
    )

    private val descripteurs = listOf(
        Champ(
            "Objectif, en € nets par heure",
            "Ce que tu veux qu'il te reste par heure de travail, carburant et usure déduits. C'est l'étalon de tout le reste.",
            1.0, { it.objectifHeure }, { b, v -> b.copy(objectifHeure = v) },
        ),
        Champ(
            "Coût de roulage, en € par km",
            "Carburant ou électricité, pneus, entretien, amortissement. Environ 0,22 en thermique, 0,10 en électrique rechargé à la maison.",
            1.0, { it.coutKm }, { b, v -> b.copy(coutKm = v) },
        ),
        Champ(
            "Commission prélevée, en %",
            "À laisser à 0 si l'offre annonce déjà ta part et non le prix client — c'est le cas d'Uber, qui écrit « Montant net de frais ».",
            100.0, { it.commission }, { b, v -> b.copy(commission = v.coerceIn(0.0, 0.9)) },
        ),
        Champ(
            "Retour à vide, en % du trajet",
            "La part du trajet qu'il faudra refaire à vide pour se repositionner. 0 si tu enchaînes toujours sur place, 100 si tu reviens systématiquement à ton point de départ. C'est ce qui distingue une course rentable d'une course qui t'exile.",
            100.0, { it.partRetour }, { b, v -> b.copy(partRetour = v.coerceIn(0.0, 2.0)) },
        ),
        Champ(
            "Attente au ramassage, en min",
            "Le temps mort entre l'arrivée sur place et le départ réel.",
            1.0, { it.minutesAttente }, { b, v -> b.copy(minutesAttente = v) },
        ),
        Champ(
            "Approche maximale, en min",
            "Au-delà, la course est refusée quel que soit le prix : trop de temps non payé.",
            1.0, { it.approcheMaxMinutes }, { b, v -> b.copy(approcheMaxMinutes = v) },
        ),
        Champ(
            "Prix plancher, en €",
            "En dessous, la course est refusée : l'usure mange la recette.",
            1.0, { it.prixPlancher }, { b, v -> b.copy(prixPlancher = v) },
        ),
        Champ(
            "Zone « limite », en ± %",
            "Largeur de la bande orange autour de ton objectif.",
            100.0, { it.marge }, { b, v -> b.copy(marge = v.coerceIn(0.0, 0.5)) },
        ),
        Champ(
            "Vitesse supposée, en km/h",
            "Sert seulement à compléter une donnée absente de la notification.",
            1.0, { it.vitesseParDefaut }, { b, v -> b.copy(vitesseParDefaut = v.coerceAtLeast(5.0)) },
        ),
    )

    /** Réglage d'affichage, hors barème économique. */
    private lateinit var champSecondes: EditText

    private fun construireChamps() {
        val conteneur = findViewById<LinearLayout>(R.id.conteneur_champs)
        for (d in descripteurs) {
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
        val verdict = Arbitre.arbitrer(
            "UberX · 18,40 €\n5 min (2,1 km) de vous\n21 min (9,4 km) de trajet",
            "Essai",
            reglages.bareme,
        )
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
}
