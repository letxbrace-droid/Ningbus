package fr.ningbus.arbitre

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Lire l'écran là où l'arbre d'accessibilité ne dit rien.
 *
 * L'arbre reste le chemin principal : il est exact, immédiat, et donne le
 * texte tel que l'application l'a écrit. Mais il a un angle mort, et il
 * s'élargit : une application qui dessine sa carte d'offre sur un `Canvas`,
 * ou qui compose une vue sans lui donner de sémantique, n'expose **aucun
 * nœud de texte**. Le service lit alors un écran vide et conclut, à tort,
 * qu'il n'y avait rien à arbitrer.
 *
 * D'où ce second chemin : une capture d'écran, et une reconnaissance de
 * texte exécutée entièrement sur le téléphone. Trois précautions le rendent
 * acceptable dans une application qui doit répondre en une fraction de
 * seconde :
 *
 *  - **il ne part jamais en premier.** L'arbre est lu d'abord ; l'OCR ne
 *    sert que s'il n'a rien donné d'exploitable ;
 *  - **il est étranglé.** Une capture coûte bien plus qu'un parcours de
 *    nœuds, et le système lui-même refuse les captures trop rapprochées ;
 *  - **il est borné en résolution.** Au-delà de [LARGEUR_MAX] pixels de
 *    large, l'image est réduite : le texte d'une carte d'offre reste
 *    parfaitement lisible, et le temps de reconnaissance suit la surface.
 */
object Ocr {

    private const val TAG = "Arbitre"

    /** `takeScreenshot` n'existe qu'à partir d'Android 11. */
    val disponible: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Délai minimal entre deux captures.
     *
     * Le système en impose déjà un — il rejette les demandes trop
     * rapprochées — mais s'en remettre à lui reviendrait à dépenser une
     * capture pour recevoir une erreur.
     */
    const val PAS_MINIMAL_MS = 1_500L

    /** Au-delà, l'image est réduite avant reconnaissance. */
    private const val LARGEUR_MAX = 1_280

    private var derniereCapture = 0L

    private val lecteur by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Vrai si une capture est permise maintenant, et réserve le créneau. */
    @Synchronized
    fun creneauLibre(): Boolean {
        val maintenant = SystemClock.elapsedRealtime()
        if (maintenant - derniereCapture < PAS_MINIMAL_MS) return false
        derniereCapture = maintenant
        return true
    }

    /**
     * Capture l'écran, en extrait le texte, et le rend à [suite].
     *
     * [suite] est toujours appelée, y compris en cas d'échec — avec une
     * chaîne vide. Un chemin de secours qui reste muet quand il échoue
     * transforme une panne lisible en silence inexplicable.
     */
    // `takeScreenshot` et `wrapHardwareBuffer` n'existent qu'à partir
    // d'Android 11, et [disponible] le vérifie à l'exécution. L'analyse
    // statique ne sait pas remonter jusqu'à cette propriété.
    @SuppressLint("NewApi")
    fun lire(service: AccessibilityService, suite: (String) -> Unit) {
        if (!disponible) return suite("")
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(capture: AccessibilityService.ScreenshotResult) {
                        reconnaitre(capture, suite)
                    }

                    override fun onFailure(code: Int) {
                        Log.w(TAG, "capture d'écran refusée (code $code)")
                        suite("")
                    }
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "capture d'écran impossible : ${e.message}")
            suite("")
        }
    }

    @SuppressLint("NewApi")
    private fun reconnaitre(
        capture: AccessibilityService.ScreenshotResult,
        suite: (String) -> Unit,
    ) {
        val tampon = capture.hardwareBuffer
        val image = try {
            // La capture arrive en mémoire graphique. ML Kit veut une image
            // ordinaire : on la recopie, puis on rend le tampon tout de
            // suite — il y en a peu, et les garder épuise le système.
            val materielle = Bitmap.wrapHardwareBuffer(tampon, capture.colorSpace)
                ?: return suite("")
            reduire(materielle.copy(Bitmap.Config.ARGB_8888, false) ?: return suite(""))
        } catch (e: Exception) {
            Log.w(TAG, "capture illisible : ${e.message}")
            return suite("")
        } finally {
            try {
                tampon.close()
            } catch (e: Exception) {
                // Déjà fermé.
            }
        }

        try {
            lecteur.process(InputImage.fromBitmap(image, 0))
                .addOnSuccessListener { texte ->
                    image.recycle()
                    suite(ordonner(texte))
                }
                .addOnFailureListener { e ->
                    image.recycle()
                    Log.w(TAG, "reconnaissance de texte échouée : ${e.message}")
                    suite("")
                }
        } catch (e: Exception) {
            image.recycle()
            Log.w(TAG, "reconnaissance impossible : ${e.message}")
            suite("")
        }
    }

    /**
     * Le texte reconnu, remis dans l'ordre de lecture.
     *
     * L'ordre n'est pas cosmétique : quand aucun mot ne désigne l'approche,
     * c'est la position qui tranche, une offre annonçant toujours l'approche
     * avant la course. ML Kit rend des blocs dont l'ordre suit la mise en
     * page, mais pas toujours la verticale — on retrie donc sur le haut de
     * chaque ligne.
     */
    private fun ordonner(texte: com.google.mlkit.vision.text.Text): String =
        texte.textBlocks
            .flatMap { it.lines }
            .sortedBy { it.boundingBox?.top ?: 0 }
            .joinToString("\n") { it.text }

    private fun reduire(image: Bitmap): Bitmap {
        if (image.width <= LARGEUR_MAX) return image
        val hauteur = image.height * LARGEUR_MAX / image.width
        val reduite = Bitmap.createScaledBitmap(image, LARGEUR_MAX, hauteur, true)
        if (reduite !== image) image.recycle()
        return reduite
    }
}
