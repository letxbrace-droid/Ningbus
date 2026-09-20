package fr.ningbus.simulateur

import android.app.UiAutomation
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le banc d'essai : Arbitre face à une vraie carte d'offre, sur un vrai
 * Android.
 *
 * **Le banc s'éteignait en s'allumant**, et c'est ce qui a coûté le plus de
 * tours. Le `dumpsys` le disait pourtant ligne à ligne : le service figurait
 * parmi les services *activés* mais jamais parmi les *liés*, et quelques
 * lignes plus bas trônait `Ui Automation[eventTypes=TYPES_ALL_MASK]`. Une
 * instrumentation qui ouvre un automate d'interface devient par défaut le
 * seul client d'accessibilité du système, qui délie alors tous les autres —
 * voir [automate]. Rien n'a jamais été cassé dans Arbitre.
 *
 * Trois précautions gouvernent donc l'écriture de ces essais :
 *
 *  - **l'automate ne supprime pas les services d'accessibilité.** C'est la
 *    condition sans laquelle rien de ce qui suit n'a de sens ;
 *  - **Arbitre est installé et lancé comme sur le téléphone du chauffeur.**
 *    Les essais vivent du côté du simulateur : aucun processus d'essai dans
 *    l'espace d'Arbitre, aucun réglage écrit dans son dos. Le banc ne lui
 *    parle que par où le système lui parle — une fenêtre qui s'affiche — et
 *    ne le relit que par le disque ;
 *
 *  - **la configuration n'est pas truquée.** Le simulateur figure dans la
 *    liste d'origine des applications écoutées, mais sur émulateur seulement
 *    (voir `Reglages.paquetsDOrigine`). Le banc éprouve donc les réglages
 *    livrés, et non des réglages posés pour la circonstance ;
 *  - **on lit le dernier verdict, pas le journal entier.** Les essais
 *    s'enchaînent sur le même téléphone : une assertion portant sur tout le
 *    journal conclurait sur ce qu'a laissé l'essai précédent.
 */
@RunWith(AndroidJUnit4::class)
class BancEssaiTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val contexte get() = instrumentation.targetContext

    /**
     * L'automate d'interface, obtenu en promettant de **ne pas supprimer les
     * services d'accessibilité**.
     *
     * Voilà la cause de tous les bancs vides. Par défaut, une instrumentation
     * qui ouvre un automate devient le seul client d'accessibilité du
     * système : tous les autres services sont déliés, et celui d'Arbitre avec
     * eux. Le banc mesurait donc une application qu'il venait lui-même
     * d'éteindre, et le journal ne pouvait que rester vide.
     *
     * Le drapeau existe depuis Android 7 exactement pour ce cas. Il faut
     * l'obtenir ainsi **partout** : redemander l'automate sans drapeau le
     * reconstruit, et la suppression revient. D'où un accesseur, et aucun
     * appel à `instrumentation.uiAutomation` ailleurs dans ce fichier.
     */
    private val automate: UiAutomation
        get() = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        )

    @Before
    fun preparerLeTelephone() {
        shell("appops set $ARBITRE SYSTEM_ALERT_WINDOW allow")
        shell("appops set $SIMULATEUR SYSTEM_ALERT_WINDOW allow")

        // Sans cela, une demande d'autorisation s'ouvre par-dessus tout au
        // premier lancement d'Arbitre et s'intercale entre l'offre et le
        // service. Le chauffeur, lui, la voit une fois et l'accorde.
        shell("pm grant $ARBITRE android.permission.POST_NOTIFICATIONS")

        // Arbitre repart de zéro à chaque essai, et ce n'est pas un luxe :
        // il refuse d'annoncer deux fois la même course dans les 45 secondes,
        // à très juste titre — un compte à rebours qui s'égrène produit des
        // dizaines d'événements pour une seule offre. Deux essais rejouant la
        // même carte, le second n'obtenait donc aucun verdict et accusait à
        // tort la lecture d'écran. Tuer le processus efface cette mémoire, et
        // supprime du même coup toute dépendance à l'ordre des essais.
        shell("am force-stop $ARBITRE")

        // Une application fraîchement installée est « arrêtée » tant que rien
        // ne l'a lancée. On l'ouvre donc une fois, comme le ferait le
        // chauffeur en accordant les permissions, avant de rendre l'écran au
        // lanceur : une offre arrive par-dessus ce qu'on regardait, jamais
        // par-dessus Arbitre.
        shell("am start -n $ARBITRE/.ActivitePrincipale")
        SystemClock.sleep(1_500)
        shell("input keyevent KEYCODE_HOME")

        activerLectureEcran()
        fermerOffre()
    }

    // --- Le cas qui a coûté quatre versions ---------------------------------

    @Test
    fun uneOffreEnFenetreFlottanteEstLue() {
        val avant = verdicts().length()
        afficher("ULIS", "FLOTTANTE")
        val verdict = attendreUnVerdictDePlus(avant)
        val brut = verdict.optString("brut")

        assertTrue(
            "le trajet n'a pas été lu dans la fenêtre flottante.\n${diagnostic()}",
            brut.contains("12.6 km"),
        )
        assertTrue(
            "l'approche n'a pas été lue dans la fenêtre flottante.\n${diagnostic()}",
            brut.contains("2.5 km"),
        )
        assertTrue(
            "verdict incomplet sur une carte complète.\n${diagnostic()}",
            verdict.optString("decision") != "INCOMPLET",
        )
    }

    @Test
    fun uneOffreEnPleinEcranEstLue() {
        val avant = verdicts().length()
        afficher("BRIIS", "PLEIN_ECRAN")
        val verdict = attendreUnVerdictDePlus(avant)

        // 17,08 € pour 16 min d'approche : au-dessus de la limite de 12 min.
        assertEquals(
            "la course de Briis aurait dû être refusée.\n${diagnostic()}",
            "LAISSE",
            verdict.optString("decision"),
        )
    }

    @Test
    fun le_montant_retenu_est_le_prix_et_non_le_bonus() {
        val avant = verdicts().length()
        afficher("ULIS", "FLOTTANTE")
        val verdict = attendreUnVerdictDePlus(avant)

        // 12,51 € et non 2,43 € : à 2,43 € la course tomberait sous le
        // plancher et serait refusée pour la mauvaise raison.
        assertTrue(
            "le bonus a été pris pour le prix.\n${diagnostic()}",
            !verdict.optString("resume").contains("plancher"),
        )
    }

    /**
     * L'angle mort de l'arbre d'accessibilité, éprouvé plutôt que supposé.
     *
     * La carte est ici **peinte** sur un `Canvas` : aucun nœud de texte,
     * aucune description de contenu, rien à lire. C'est le cas vers lequel
     * les applications chauffeur se dirigent, et le seul où la lecture d'écran
     * classique ne peut rien. Si ce verdict sort, il ne peut venir que de la
     * reconnaissance de texte.
     *
     * L'attente est plus longue que les autres : la première reconnaissance
     * charge le modèle embarqué, ce qui ne se produit qu'une fois mais coûte
     * une seconde ou deux.
     */
    @Test
    fun uneOffreDessineeEstLueParReconnaissanceDeTexte() {
        val avant = verdicts().length()

        // Une première offre, écrite celle-là, pour qu'une bulle d'Arbitre
        // soit posée à l'écran au moment de la suivante. C'est le cas qui a
        // fait tomber deux corrections successives : d'abord la bulle était
        // relue comme une offre, ensuite sa seule présence interdisait toute
        // capture. Les deux se rencontrent ici.
        afficher("ULIS", "FLOTTANTE")
        attendreUnVerdictDePlus(avant)
        val apresLaPremiere = verdicts().length()

        // La seconde est peinte : aucun nœud de texte, donc invisible à
        // l'arbre. Seule la reconnaissance de texte peut la voir — et elle
        // doit y parvenir alors que la bulle précédente est encore affichée.
        fermerOffre()
        afficher("BRIIS", "CANVAS")
        val verdict = attendreUnVerdictDePlus(apresLaPremiere, 40_000L)

        assertTrue(
            "la carte dessinée n'a pas été lue — l'arbre n'en dit rien, " +
                "et la reconnaissance de texte n'a pas pris le relais.\n${diagnostic()}",
            verdict.optString("brut").contains("12.1 km"),
        )
        assertTrue(
            "la bulle affichée a été relue comme une offre : " +
                "${verdicts().length() - apresLaPremiere} verdicts au lieu d'un.\n" +
                diagnostic(),
            verdicts().length() == apresLaPremiere + 1,
        )
    }

    /**
     * Une carte d'offre se redessine à chaque seconde du compte à rebours, et
     * chaque redessin est un événement. Sans ce dédoublonnage, une seule
     * course ferait surgir vingt bulles — et c'est lui qui a fait échouer un
     * essai entier du banc avant d'être éprouvé ici plutôt que subi.
     */
    @Test
    fun uneMemeOffreNEstArbitreeQuUneFois() {
        val avant = verdicts().length()
        afficher("ULIS", "FLOTTANTE")
        attendreUnVerdictDePlus(avant)
        val apresLePremier = verdicts().length()

        fermerOffre()
        afficher("ULIS", "FLOTTANTE")
        SystemClock.sleep(10_000)

        assertTrue(
            "la même course a été arbitrée deux fois.\n${diagnostic()}",
            verdicts().length() == apresLePremier,
        )
    }

    @Test
    fun un_ecran_de_navigation_ne_declenche_aucune_bulle() {
        val avant = verdicts().length()
        afficher("NAVIGATION", "FLOTTANTE")

        // On laisse largement le temps qu'une bulle sorte si elle doit sortir,
        // y compris le verdict incomplet différé.
        SystemClock.sleep(10_000)

        assertTrue(
            "une bulle a surgi en pleine conduite.\n${diagnostic()}",
            verdicts().length() == avant,
        )
        assertTrue(
            "l'écran écarté n'a pas été journalisé, le diagnostic serait aveugle.\n" +
                diagnostic(),
            journalBrut().contains("ecrans_ecartes"),
        )
    }

    // --- Mise en route du service d'accessibilité ---------------------------

    /**
     * Active la lecture d'écran, et **vérifie que le système a lié le
     * service** — pas seulement qu'il l'a autorisé.
     *
     * La distinction est toute l'histoire de ce banc : un service activé mais
     * non lié ne reçoit aucun événement, et l'application paraît en panne
     * alors que rien n'est cassé. Le second essai, après un réglage remis à
     * zéro, sert aux systèmes qui ne relient que sur transition.
     *
     * On attend toujours la liaison, sans jamais conclure d'un vidage pris
     * avant elle : l'arrêt forcé du préambule vient de tuer le service, et le
     * système met quelques secondes à le relier de nouveau.
     */
    private fun activerLectureEcran() {
        shell("settings put secure enabled_accessibility_services $SERVICE")
        shell("settings put secure accessibility_enabled 1")
        if (attendreLiaison(20_000)) return

        shell("settings delete secure enabled_accessibility_services")
        shell("settings put secure accessibility_enabled 0")
        SystemClock.sleep(1_500)
        shell("settings put secure enabled_accessibility_services $SERVICE")
        shell("settings put secure accessibility_enabled 1")
        if (attendreLiaison(25_000)) return

        echouer("le système n'a jamais lié le service de lecture d'écran")
    }

    /**
     * Le service est lié, vu du système et non de l'application.
     *
     * `dumpsys accessibility` distingue trois états — liés, en cours de
     * liaison, activés — et seul le premier signifie que les événements
     * partent vraiment.
     *
     * La ligne des services liés ne porte pas le nom du composant mais le
     * **libellé** de l'application : « Bound services:{Service[label=Arbitre,
     * feedbackType[FEEDBACK_GENERIC], …]} ». Chercher le nom de paquet y
     * revenait à déclarer en panne un service parfaitement vivant — le banc
     * a échoué un tour entier là-dessus. On accepte donc les deux formes,
     * les versions d'Android n'écrivant pas ce vidage de la même façon.
     */
    private fun serviceLie(): Boolean =
        shell("dumpsys accessibility").lineSequence().any {
            val ligne = it.trim()
            ligne.startsWith("Bound services:") &&
                (ligne.contains(ARBITRE) || ligne.contains("label=$LIBELLE"))
        }

    private fun attendreLiaison(limiteMs: Long): Boolean {
        val limite = SystemClock.uptimeMillis() + limiteMs
        while (SystemClock.uptimeMillis() < limite) {
            if (runCatching { serviceLie() }.getOrDefault(false)) {
                // Le service pose son filtre d'applications au moment où il
                // est lié : on lui laisse le temps de s'installer avant de
                // lui envoyer une offre.
                SystemClock.sleep(1_500)
                return true
            }
            SystemClock.sleep(500)
        }
        return false
    }

    // --- Piloter le simulateur ----------------------------------------------

    private fun afficher(offre: String, mode: String) {
        contexte.startActivity(
            Intent().apply {
                setClassName(SIMULATEUR, "$SIMULATEUR.ActivitePrincipale")
                putExtra("offre", offre)
                putExtra("mode", mode)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
    }

    private fun fermerOffre() {
        afficher("ULIS", "FERMER")
        SystemClock.sleep(1_000)
        shell("input keyevent KEYCODE_HOME")
        SystemClock.sleep(500)
    }

    // --- Relire Arbitre par le disque ---------------------------------------

    /**
     * Le journal tel qu'il est sur le disque, lu par `run-as`.
     *
     * Arbitre tourne dans un autre processus, et même dans une autre
     * application : ses préférences ne sont lisibles que là.
     */
    private fun journalBrut(): String =
        shell("run-as $ARBITRE cat /data/data/$ARBITRE/shared_prefs/journal.xml")

    /** Les verdicts rendus, du plus récent au plus ancien. */
    private fun verdicts(): JSONArray {
        val valeur = Regex(
            """<string name="lignes">(.*?)</string>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(journalBrut())?.groupValues?.get(1) ?: return JSONArray()
        return runCatching { JSONArray(desechapper(valeur)) }.getOrDefault(JSONArray())
    }

    /** Le XML des préférences échappe ce que le JSON, lui, ne connaît pas. */
    private fun desechapper(texte: String): String = texte
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    private fun attendreUnVerdictDePlus(
        avant: Int,
        limiteMs: Long = ATTENTE_VERDICT_MS,
    ): JSONObject {
        val limite = SystemClock.uptimeMillis() + limiteMs
        while (SystemClock.uptimeMillis() < limite) {
            val rendus = runCatching { verdicts() }.getOrDefault(JSONArray())
            if (rendus.length() > avant) {
                return rendus.optJSONObject(0) ?: JSONObject()
            }
            SystemClock.sleep(500)
        }
        echouer("aucun verdict de plus dans le journal")
    }

    // --- Diagnostic ---------------------------------------------------------

    private fun echouer(quoi: String): Nothing {
        val rapport = diagnostic()
        // Aussi dans logcat : le message d'assertion est tronqué à sa
        // première ligne dans la sortie de Gradle.
        Log.i("BancEssai", "échec — $quoi\n$rapport")
        throw AssertionError("$quoi\n$rapport")
    }

    /**
     * Tout ce qu'il faut pour comprendre un échec sans relancer la machine.
     * Un essai qui échoue sans dire pourquoi coûte un aller-retour complet.
     */
    private fun diagnostic(): String = buildString {
        append("--- services d'accessibilité activés ---\n")
        append(shell("settings get secure enabled_accessibility_services")).append('\n')
        append("accessibility_enabled = ")
        append(shell("settings get secure accessibility_enabled")).append('\n')
        append("service lié = ").append(runCatching { serviceLie() }.getOrNull()).append('\n')
        append("--- dumpsys accessibility ---\n")
        append(shell("dumpsys accessibility").take(6_000))
        append("\n--- logcat ---\n")
        append(
            shell("logcat -d -v time -s Arbitre:V AccessibilityManagerService:V AndroidRuntime:E")
                .takeLast(4_000)
        )
        append("\n--- journal sur disque ---\n")
        append(journalBrut().take(4_000))
    }

    /**
     * `UiAutomation.executeShellCommand` ne passe pas par un interpréteur :
     * la commande est découpée sur les espaces, sans guillemets, sans
     * redirection. Toutes les commandes de ce banc sont écrites en
     * conséquence.
     */
    private fun shell(commande: String): String {
        val descripteur = automate.executeShellCommand(commande)
        return ParcelFileDescriptor.AutoCloseInputStream(descripteur).use {
            it.bufferedReader().readText()
        }
    }

    private companion object {
        const val ARBITRE = "fr.ningbus.arbitre"
        const val SIMULATEUR = "fr.ningbus.simulateur"

        /** `android:label` du service, seul nom que porte le vidage système. */
        const val LIBELLE = "Arbitre"
        const val SERVICE = "$ARBITRE/$ARBITRE.LectureEcran"

        /** Cinq secondes promises, vingt-cinq accordées avant de conclure. */
        const val ATTENTE_VERDICT_MS = 25_000L
    }
}
