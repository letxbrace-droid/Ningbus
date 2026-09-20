package fr.ningbus.arbitre

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le banc d'essai : Arbitre face à une vraie carte d'offre, sur un vrai
 * Android.
 *
 * Les tests du moteur vérifient le calcul, pas la plomberie. Or toutes les
 * pannes rencontrées jusqu'ici étaient dans la plomberie : la mauvaise fenêtre
 * lue, l'arbre des vues tronqué, la carte pas encore dessinée. Aucun test
 * unitaire ne pouvait les voir.
 *
 * Deux précautions gouvernent l'écriture de ces essais :
 *
 *  - **le journal est lu sur le disque**, par `run-as`, et non par l'objet
 *    [Journal]. Le service d'accessibilité peut tourner dans un autre
 *    processus que celui des essais ; les préférences partagées sont alors
 *    mises en cache de chaque côté et le processus d'essai lirait un état
 *    périmé, concluant à tort que rien ne s'est produit ;
 *  - **on compte les verdicts avant et après**, au lieu d'exiger un journal
 *    vide. Remettre à zéro un état détenu par un autre processus est un
 *    problème qu'il vaut mieux contourner que résoudre.
 */
@RunWith(AndroidJUnit4::class)
class ParcoursCompletTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val contexte get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val paquet get() = contexte.packageName

    @Before
    fun preparerLeTelephone() {
        shell("appops set $paquet SYSTEM_ALERT_WINDOW allow")
        shell("appops set $SIMULATEUR SYSTEM_ALERT_WINDOW allow")
        shell("settings put secure enabled_accessibility_services $composant")
        shell("settings put secure accessibility_enabled 1")

        // On attend que le système ait enregistré le service, et non que
        // l'objet Kotlin s'en aperçoive : selon le processus dans lequel le
        // service est lié, le drapeau statique peut rester faux ici alors que
        // le service fonctionne parfaitement.
        attendre("enregistrement du service par le système") {
            shell("settings get secure enabled_accessibility_services").contains("LectureEcran")
        }
        SystemClock.sleep(3_000)

        Reglages(contexte).apply {
            actif = true
            ecouteToutesApps = true
            filtrerEcrans = true
            vibration = false
            boutonFlottant = false
        }
        fermerOffre()
    }

    // --- Le cas qui a coûté quatre versions ---------------------------------

    @Test
    fun uneOffreEnFenetreFlottanteEstLue() {
        val avant = verdictsRendus()
        afficher("ULIS", "FLOTTANTE")
        attendreUnVerdictDePlus(avant)

        val journal = journalBrut()
        assertTrue(
            "le trajet n'a pas été lu.\n${diagnostic()}",
            journal.contains("12.6 km"),
        )
        assertTrue(
            "l'approche n'a pas été lue.\n${diagnostic()}",
            journal.contains("2.5 km"),
        )
        assertTrue(
            "verdict incomplet sur une carte complète.\n${diagnostic()}",
            !journal.contains("INCOMPLET"),
        )
    }

    @Test
    fun uneOffreEnPleinEcranEstLue() {
        val avant = verdictsRendus()
        afficher("BRIIS", "PLEIN_ECRAN")
        attendreUnVerdictDePlus(avant)

        // 17,08 € pour 16 min d'approche : au-dessus de la limite de 12 min.
        assertTrue(
            "la course de Briis aurait dû être refusée.\n${diagnostic()}",
            journalBrut().contains("LAISSE"),
        )
    }

    @Test
    fun le_montant_retenu_est_le_prix_et_non_le_bonus() {
        val avant = verdictsRendus()
        afficher("ULIS", "FLOTTANTE")
        attendreUnVerdictDePlus(avant)

        // 12,51 € et non 2,43 € : à 2,43 € la course tomberait sous le
        // plancher et serait refusée pour la mauvaise raison.
        assertTrue(
            "le bonus a été pris pour le prix.\n${diagnostic()}",
            !journalBrut().contains("plancher"),
        )
    }

    @Test
    fun un_ecran_de_navigation_ne_declenche_aucune_bulle() {
        val avant = verdictsRendus()
        afficher("NAVIGATION", "FLOTTANTE")

        // On laisse largement le temps qu'une bulle sorte si elle doit sortir,
        // y compris le verdict incomplet différé.
        SystemClock.sleep(8_000)

        assertTrue(
            "une bulle a surgi en pleine conduite.\n${diagnostic()}",
            verdictsRendus() == avant,
        )
        assertTrue(
            "l'écran écarté n'a pas été journalisé, le diagnostic serait aveugle.\n" +
                diagnostic(),
            journalBrut().contains("ecrans_ecartes"),
        )
    }

    // --- Outils -------------------------------------------------------------

    private val composant get() = "$paquet/${LectureEcran::class.java.name}"

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
        SystemClock.sleep(800)
    }

    /**
     * Le journal tel qu'il est sur le disque, lu par `run-as` pour rester
     * indépendant du processus dans lequel le service tourne.
     */
    private fun journalBrut(): String =
        shell("run-as $paquet cat /data/data/$paquet/shared_prefs/journal.xml")

    /** Nombre de verdicts rendus, compté dans le journal du disque. */
    private fun verdictsRendus(): Int =
        Regex("&quot;decision&quot;").findAll(journalBrut()).count() +
            Regex("\"decision\"").findAll(journalBrut()).count()

    private fun attendreUnVerdictDePlus(avant: Int) {
        attendre("un verdict de plus dans le journal", 25_000) { verdictsRendus() > avant }
    }

    private fun attendre(quoi: String, limiteMs: Long = 20_000, condition: () -> Boolean) {
        val limite = SystemClock.uptimeMillis() + limiteMs
        while (SystemClock.uptimeMillis() < limite) {
            if (runCatching(condition).getOrDefault(false)) return
            SystemClock.sleep(500)
        }
        throw AssertionError("délai dépassé en attendant : $quoi\n${diagnostic()}")
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
        append("LectureEcran.lie (vu du processus d'essai) = ").append(LectureEcran.lie).append('\n')
        append("--- dumpsys accessibility ---\n")
        append(shell("dumpsys accessibility").lineSequence().take(25).joinToString("\n"))
        append("\n--- journal sur disque ---\n")
        append(journalBrut().take(3_000))
    }

    private fun shell(commande: String): String {
        val descripteur = instrumentation.uiAutomation.executeShellCommand(commande)
        return ParcelFileDescriptor.AutoCloseInputStream(descripteur).use {
            it.bufferedReader().readText()
        }
    }

    private companion object {
        const val SIMULATEUR = "fr.ningbus.simulateur"
    }
}
