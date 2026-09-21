package fr.ningbus.arbitre

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import fr.ningbus.arbitre.moteur.Decision
import fr.ningbus.arbitre.moteur.fmt0
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Le journal des courses arbitrées.
 *
 * Il sert deux choses : relire ses décisions à froid, et récupérer le texte
 * brut des notifications. C'est ce texte qui permet de corriger l'analyseur
 * quand une plateforme change ses libellés — sans lui, on ne peut que
 * deviner pourquoi une course a été mal lue.
 */
class ActiviteJournal : AppCompatActivity() {

    private val heure = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)

    override fun onCreate(etat: Bundle?) {
        super.onCreate(etat)
        setContentView(R.layout.journal)

        findViewById<Button>(R.id.bouton_copier).setOnClickListener { copier() }
        findViewById<Button>(R.id.bouton_vider).setOnClickListener {
            Journal.vider(this)
            peupler()
        }
    }

    override fun onResume() {
        super.onResume()
        peupler()
    }

    private fun peupler() {
        val conteneur = findViewById<LinearLayout>(R.id.conteneur_journal)
        conteneur.removeAllViews()

        val lignes = Journal.lignes(this)
        findViewById<TextView>(R.id.resume_journal).text = resumeGlobal(lignes)

        if (lignes.isEmpty()) {
            conteneur.addView(TextView(this).apply {
                text = getString(R.string.journal_vide)
                alpha = 0.7f
                setPadding(0, dp(16), 0, 0)
            })
        }
        for (ligne in lignes) conteneur.addView(carte(ligne))
        // Surtout pas après un retour anticipé : c'est quand aucune course
        // n'a été arbitrée que les écrans écartés expliquent pourquoi.
        ajouterCaptures(conteneur)
        ajouterEcarts(conteneur)
        ajouterVues(conteneur)
    }

    /** Le texte brut relevé à l'écran, la pièce à conviction. */
    private fun ajouterCaptures(conteneur: LinearLayout) {
        val captures = Journal.captures(this)
        if (captures.isEmpty()) return
        conteneur.addView(titre(R.string.titre_captures))
        conteneur.addView(TextView(this).apply {
            text = getString(R.string.aide_captures)
            textSize = 11f
            alpha = 0.6f
            setPadding(0, 0, 0, dp(4))
        })
        for ((paquet, brut) in captures) conteneur.addView(bloc(paquet, brut))
    }

    /**
     * Les notifications repérées avant tout filtrage.
     *
     * C'est le diagnostic décisif quand rien ne se passe : liste vide, la
     * notification n'atteint pas l'application ; liste pleine, c'est la
     * lecture qui a échoué — et le texte exact est là pour la corriger.
     */
    private fun ajouterVues(conteneur: LinearLayout) {
        val vues = Journal.notificationsVues(this)
        if (vues.isEmpty()) return
        conteneur.addView(titre(R.string.titre_vues))
        conteneur.addView(TextView(this).apply {
            text = getString(R.string.aide_vues)
            textSize = 11f
            alpha = 0.6f
            setPadding(0, 0, 0, dp(4))
        })
        for ((paquet, brut) in vues) conteneur.addView(bloc(paquet, brut))
    }

    private fun titre(id: Int): TextView = TextView(this).apply {
        text = getString(id)
        textSize = 13f
        alpha = 0.75f
        setPadding(0, dp(18), 0, dp(2))
    }

    private fun bloc(paquet: String, brut: String): TextView = TextView(this).apply {
        text = "$paquet\n$brut"
        textSize = 11f
        alpha = 0.55f
        setBackgroundResource(R.drawable.fond_carte)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) }
    }

    /**
     * Les écrans porteurs d'un montant que le filtre n'a pas retenus. Si une
     * course est passée à travers, c'est ici qu'on voit ce qu'il manquait.
     */
    private fun ajouterEcarts(conteneur: LinearLayout) {
        val ecarts = Journal.ecransEcartes(this)
        if (ecarts.isEmpty()) return

        conteneur.addView(titre(R.string.titre_ecarts))
        for ((paquet, brut) in ecarts) conteneur.addView(bloc(paquet, brut))
    }

    /** Ce que le journal apprend sur la session : latence réelle et tri. */
    private fun resumeGlobal(lignes: List<Ligne>): String {
        if (lignes.isEmpty()) return getString(R.string.journal_vide_resume)
        val latences = lignes.map { it.latenceMs }.sorted()
        val mediane = latences[latences.size / 2]
        val pire = latences.last()
        val prises = lignes.count { it.decision == Decision.PRENDS.name }
        return "${lignes.size} courses vues · $prises à prendre\n" +
            "détection : ${mediane} ms en médiane, ${pire} ms au pire"
    }

    private fun carte(ligne: Ligne): View {
        val decision = runCatching { Decision.valueOf(ligne.decision) }.getOrNull()
        val teinte = ContextCompat.getColor(
            this,
            when (decision) {
                Decision.PRENDS -> R.color.vert
                Decision.LIMITE -> R.color.ambre
                Decision.LAISSE -> R.color.rouge
                else -> R.color.gris
            },
        )

        val bloc = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.fond_carte)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }

        val entete = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        entete.addView(TextView(this).apply {
            text = decision?.libelle ?: ligne.decision
            setTextColor(teinte)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        entete.addView(TextView(this).apply {
            text = ligne.euroHeure?.let { "${fmt0(it)} €/h" } ?: "—"
            textSize = 16f
        })
        bloc.addView(entete)

        bloc.addView(TextView(this).apply {
            text = "${ligne.plateforme} · ${heure.format(Date(ligne.horodatage))} · " +
                "par ${ligne.source} en ${ligne.latenceMs} ms"
            textSize = 12f
            alpha = 0.7f
        })
        bloc.addView(TextView(this).apply {
            text = ligne.resume
            textSize = 14f
            setPadding(0, dp(4), 0, 0)
        })

        // Le détail champ par champ : ce qui a été lu, ce qui a été estimé,
        // ce qui manquait. C'est la différence entre « course non détectée »
        // et « j'ai vu le prix et l'approche, il me manquait la course » —
        // la première ne mène nulle part, la seconde dit quoi corriger.
        if (ligne.lectures.isNotEmpty()) {
            bloc.addView(TextView(this).apply {
                text = buildString {
                    append("confiance ").append(ligne.confiance).append(" %")
                    if (ligne.scoreOffre > 0) {
                        append(" · offre ").append(ligne.scoreOffre).append("/100")
                    }
                    append('\n').append(ligne.lectures)
                }
                textSize = 12f
                alpha = 0.8f
                setPadding(0, dp(6), 0, 0)
            })
        }

        bloc.addView(TextView(this).apply {
            text = ligne.texteBrut
            textSize = 11f
            alpha = 0.55f
            setPadding(0, dp(6), 0, 0)
        })
        return bloc
    }

    /** Copie tout le journal, texte brut compris, pour affiner l'analyseur. */
    private fun copier() {
        val lignes = Journal.lignes(this)
        val ecarts = Journal.ecransEcartes(this)
        val vues = Journal.notificationsVues(this)
        val captures = Journal.captures(this)
        if (lignes.isEmpty() && ecarts.isEmpty() && vues.isEmpty() && captures.isEmpty()) {
            Toast.makeText(this, R.string.journal_vide, Toast.LENGTH_SHORT).show()
            return
        }
        val texte = buildString {
            lignes.joinTo(this, "\n\n") { l ->
                "[${heure.format(Date(l.horodatage))}] ${l.paquet} · ${l.source} · " +
                    "${l.decision} · ${l.latenceMs} ms · confiance ${l.confiance} %\n" +
                    "${l.lectures}\n${l.texteBrut}"
            }
            if (ecarts.isNotEmpty()) {
                append("\n\n--- ").append(getString(R.string.titre_ecarts)).append(" ---")
                for ((paquet, brut) in ecarts) append("\n\n").append(paquet).append("\n").append(brut)
            }
            if (captures.isNotEmpty()) {
                append("\n\n--- ").append(getString(R.string.titre_captures)).append(" ---")
                for ((paquet, brut) in captures) {
                    append("\n\n").append(paquet).append("\n").append(brut)
                }
            }
            if (vues.isNotEmpty()) {
                append("\n\n--- ").append(getString(R.string.titre_vues)).append(" ---")
                for ((paquet, brut) in vues) append("\n\n").append(paquet).append("\n").append(brut)
            }
        }
        val presse = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        presse.setPrimaryClip(ClipData.newPlainText("journal arbitre", texte))
        Toast.makeText(this, R.string.journal_copie, Toast.LENGTH_SHORT).show()
    }

    private fun dp(valeur: Int): Int = (valeur * resources.displayMetrics.density).toInt()
}
