package fr.ningbus.arbitre

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.ningbus.arbitre.moteur.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le banc d'essai : Arbitre face à une vraie carte d'offre, sur un vrai
 * Android.
 *
 * Les tests du moteur vérifient le calcul, pas la plomberie. Or toutes les
 * pannes rencontrées jusqu'ici étaient dans la plomberie : la bonne fenêtre
 * n'était pas lue, l'arbre des vues était tronqué, la carte n'était pas
 * encore dessinée. Aucun test unitaire ne pouvait les voir.
 *
 * Ces essais installent une fausse application chauffeur, lui font afficher
 * une carte relevée sur une capture réelle, et vérifient qu'un verdict chiffré
 * atterrit dans le journal. Le cas décisif est l'offre **en fenêtre
 * flottante** : c'est celui où trois corrections successives avaient échoué.
 */
@RunWith(AndroidJUnit4::class)
class ParcoursCompletTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val contexte get() = instrumentation.targetContext

    @Before
    fun preparerLeTelephone() {
        // Droits que l'utilisateur accorde à la main, posés ici par la commande
        // shell dont dispose l'instrumentation.
        shell("appops set ${contexte.packageName} SYSTEM_ALERT_WINDOW allow")
        shell("appops set $SIMULATEUR SYSTEM_ALERT_WINDOW allow")
        shell(
            "settings put secure enabled_accessibility_services " +
                "${contexte.packageName}/${LectureEcran::class.java.name}"
        )
        shell("settings put secure accessibility_enabled 1")
        attendre("liaison du service d'accessibilité") { LectureEcran.lie }

        Reglages(contexte).apply {
            actif = true
            ecouteToutesApps = true
            filtrerEcrans = true
            vibration = false
            boutonFlottant = false
            bareme = fr.ningbus.arbitre.moteur.Bareme()
        }
        fermerOffre()
        Journal.vider(contexte)
    }

    // --- Le cas qui a coûté quatre versions ---------------------------------

    @Test
    fun uneOffreEnFenetreFlottanteEstLue() {
        afficher("ULIS", "FLOTTANTE")

        val ligne = attendreUneCourse()
        assertEquals("écran", ligne.source)
        assertTrue(
            "verdict incomplet : ${ligne.resume} — texte lu : ${ligne.texteBrut}",
            ligne.decision != Decision.INCOMPLET.name,
        )
        assertTrue(
            "le trajet n'a pas été lu : ${ligne.texteBrut}",
            ligne.texteBrut.contains("12.6 km"),
        )
        assertTrue(
            "l'approche n'a pas été lue : ${ligne.texteBrut}",
            ligne.texteBrut.contains("2.5 km"),
        )
    }

    @Test
    fun uneOffreEnPleinEcranEstLue() {
        afficher("BRIIS", "PLEIN_ECRAN")

        val ligne = attendreUneCourse()
        assertTrue(
            "verdict incomplet : ${ligne.resume}",
            ligne.decision != Decision.INCOMPLET.name,
        )
        // 17,08 € pour 16 min d'approche : au-dessus de la limite de 12 min.
        assertEquals(Decision.LAISSE.name, ligne.decision)
    }

    @Test
    fun le_montant_retenu_est_le_prix_et_non_le_bonus() {
        afficher("ULIS", "FLOTTANTE")
        val ligne = attendreUneCourse()

        // 12,51 € et non 2,43 € : à 2,43 € la course tomberait sous le
        // plancher et serait refusée pour la mauvaise raison.
        assertTrue(
            "prix mal lu, résumé : ${ligne.resume}",
            !ligne.resume.contains("plancher"),
        )
    }

    @Test
    fun un_ecran_de_navigation_ne_declenche_aucune_bulle() {
        afficher("NAVIGATION", "FLOTTANTE")

        // On laisse largement le temps qu'une bulle sorte si elle doit sortir,
        // y compris le verdict incomplet différé.
        SystemClock.sleep(6_000)
        assertTrue(
            "une bulle a surgi en pleine conduite : ${Journal.lignes(contexte).firstOrNull()?.resume}",
            Journal.lignes(contexte).isEmpty(),
        )
        assertTrue(
            "l'écran écarté n'a pas été journalisé, le diagnostic serait aveugle",
            Journal.ecransEcartes(contexte).isNotEmpty(),
        )
    }

    @Test
    fun la_latence_reste_tres_en_dessous_de_cinq_secondes() {
        afficher("ULIS", "FLOTTANTE")
        val ligne = attendreUneCourse()
        assertTrue(
            "latence de ${ligne.latenceMs} ms",
            ligne.latenceMs in 0..5_000,
        )
    }

    // --- Outils -------------------------------------------------------------

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
        SystemClock.sleep(500)
    }

    private fun attendreUneCourse(): Ligne {
        attendre("une course arbitrée") { Journal.lignes(contexte).isNotEmpty() }
        return Journal.lignes(contexte).first()
    }

    private fun attendre(quoi: String, limiteMs: Long = 20_000, condition: () -> Boolean) {
        val limite = SystemClock.uptimeMillis() + limiteMs
        while (SystemClock.uptimeMillis() < limite) {
            if (condition()) return
            SystemClock.sleep(250)
        }
        fail("délai dépassé en attendant : $quoi")
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
