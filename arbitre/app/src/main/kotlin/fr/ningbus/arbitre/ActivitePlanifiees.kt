package fr.ningbus.arbitre

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import fr.ningbus.arbitre.moteur.Comparatif
import fr.ningbus.arbitre.moteur.Planifiees
import fr.ningbus.arbitre.moteur.RangPlanifiee
import fr.ningbus.arbitre.moteur.fmt1
import fr.ningbus.arbitre.moteur.fmt2

/**
 * Le comparateur de courses planifiées.
 *
 * Il répond à une question qu'aucun verdict ne pouvait traiter : **laquelle**.
 * Une offre live se prend ou se laisse, et l'arbitrage suffit. Une liste de
 * courses planifiées, non : les dix-neuf offres relevées le 24/09 tenaient
 * toutes dans la même demi-heure, une seule serait faite, et l'écart entre le
 * meilleur et le pire choix allait de 0,80 à 3,06 €/km. Il n'y avait rien à
 * arbitrer, tout à classer.
 *
 * Le classement suit le **budget d'approche** et non l'euro par kilomètre,
 * parce que c'est le seul chiffre que la liste ne montre pas et le seul qui
 * dépende d'où le chauffeur se trouve. Une course planifiée est tarifée comme
 * si la voiture était déjà devant la porte : sur ce relevé, aucune ne
 * supportait plus de dix kilomètres à vide.
 */
class ActivitePlanifiees : AppCompatActivity() {

    override fun onCreate(etat: Bundle?) {
        super.onCreate(etat)
        setContentView(R.layout.planifiees)

        findViewById<Button>(R.id.bouton_nouvelle_comparaison).setOnClickListener {
            Comparateur.vider(this)
            peupler()
        }
        findViewById<Button>(R.id.bouton_copier_planifiees).setOnClickListener { copier() }
    }

    override fun onResume() {
        super.onResume()
        peupler()
    }

    private fun comparatif(): Comparatif =
        Planifiees.comparer(Comparateur.lire(this), Reglages(this).bareme)

    private fun peupler() {
        val conteneur = findViewById<LinearLayout>(R.id.conteneur_planifiees)
        conteneur.removeAllViews()

        val c = comparatif()
        findViewById<TextView>(R.id.resume_planifiees).text = resume(c)
        alertePeage(c)

        if (c.vide) {
            conteneur.addView(TextView(this).apply {
                text = getString(R.string.planifiees_vide)
                alpha = 0.7f
                setPadding(0, dp(16), 0, 0)
            })
            return
        }
        for ((rang, r) in c.rangs.withIndex()) conteneur.addView(carte(rang + 1, r, c))
    }

    /**
     * Ce que la liste dit d'elle-même, en deux lignes.
     *
     * La médiane plutôt qu'une moyenne : un péage de 13,60 € ou une course
     * trois fois plus longue que les autres déplacerait la moyenne, et c'est
     * précisément ce genre d'offre qu'il s'agit de situer.
     */
    private fun resume(c: Comparatif): String {
        if (c.vide) return getString(R.string.planifiees_vide_resume)
        val tenables = c.rangs.count { it.tenable }
        return "${c.rangs.size} courses relevées · médiane ${fmt2(c.medianeEuroKm)} €/km\n" +
            "$tenables tenable(s) sur place · au mieux " +
            "${fmt1(c.budgetMaximal)} km d'approche"
    }

    /**
     * Le piège de la plus grosse annonce, et il mérite son bandeau.
     *
     * « 53,43 € » est le premier chiffre que la main attrape. Les 13,60 € de
     * péage sortent de la poche du chauffeur, et la course retombe sous la
     * médiane de sa propre liste.
     */
    private fun alertePeage(c: Comparatif) {
        val bandeau = findViewById<TextView>(R.id.alerte_peage)
        if (c.peageMasque <= 0.0) {
            bandeau.visibility = View.GONE
            return
        }
        bandeau.visibility = View.VISIBLE
        bandeau.setBackgroundResource(R.drawable.fond_carte)
        bandeau.setTextColor(ContextCompat.getColor(this, R.color.ambre))
        bandeau.text = getString(R.string.alerte_peage, fmt2(c.peageMasque))
    }

    private fun carte(rang: Int, r: RangPlanifiee, c: Comparatif): View {
        val course = r.course
        val teinte = ContextCompat.getColor(
            this,
            when {
                !r.tenable -> R.color.rouge
                r.budgetApprocheKm >= SEUIL_CONFORTABLE -> R.color.vert
                else -> R.color.ambre
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

        // Étage 1 : le rang, le prix, et l'euro par kilomètre. De quoi décider
        // sans lire la suite.
        val entete = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        entete.addView(TextView(this).apply {
            text = "$rang."
            setTextColor(teinte)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, dp(10), 0)
        })
        entete.addView(TextView(this).apply {
            text = "${fmt2(course.prixNet)} €"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        entete.addView(TextView(this).apply {
            text = "${fmt2(course.euroParKm)} €/km"
            setTextColor(teinte)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        bloc.addView(entete)

        // Étage 2 : ce que la course est.
        bloc.addView(TextView(this).apply {
            text = buildString {
                append(fmt1(course.km)).append(" km")
                if (course.categorie.isNotEmpty()) append(" · ").append(course.categorie)
                if (course.creneau.isNotEmpty()) append(" · ").append(course.creneau)
            }
            textSize = 13f
            alpha = 0.85f
            setPadding(0, dp(4), 0, 0)
        })

        // Étage 3 : d'où à où. C'est ce qui dit si la course t'exile, et aucun
        // euro par kilomètre ne le montre.
        if (course.depart.isNotEmpty() || course.arrivee.isNotEmpty()) {
            bloc.addView(TextView(this).apply {
                text = "${course.depart} → ${course.arrivee}"
                textSize = 13f
                alpha = 0.75f
                setPadding(0, dp(2), 0, 0)
            })
        }

        // Étage 4 : le chiffre qui décide, et les avertissements.
        bloc.addView(TextView(this).apply {
            text = if (r.tenable) {
                getString(R.string.budget_approche, fmt1(r.budgetApprocheKm))
            } else {
                getString(R.string.budget_nul)
            }
            setTextColor(teinte)
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
        })

        if (course.peage > 0.0) {
            bloc.addView(note(getString(R.string.note_peage, fmt2(course.prix), fmt2(course.peage))))
        }
        if (r.auPlancher) {
            bloc.addView(note(getString(R.string.note_plancher, fmt2(course.prix))))
        }
        if (course.euroParKm < c.medianeEuroKm) {
            bloc.addView(note(getString(R.string.note_sous_mediane, fmt2(c.medianeEuroKm))))
        }
        return bloc
    }

    private fun note(texte: String): TextView = TextView(this).apply {
        text = "⚠ $texte"
        setTextColor(ContextCompat.getColor(this@ActivitePlanifiees, R.color.ambre))
        textSize = 12f
        setPadding(0, dp(4), 0, 0)
    }

    /** Le classement en texte, pour le sortir de l'application. */
    private fun copier() {
        val c = comparatif()
        val texte = buildString {
            append(resume(c)).append("\n\n")
            for ((rang, r) in c.rangs.withIndex()) {
                append(rang + 1).append(". ")
                append(fmt2(r.course.prixNet)).append(" € · ")
                append(fmt1(r.course.km)).append(" km · ")
                append(fmt2(r.course.euroParKm)).append(" €/km · approche max ")
                append(if (r.tenable) "${fmt1(r.budgetApprocheKm)} km" else "aucune")
                append(" · ").append(r.course.creneau)
                append("\n   ").append(r.course.depart).append(" → ").append(r.course.arrivee)
                if (r.course.peage > 0.0) append(" (péage ${fmt2(r.course.peage)} €)")
                append("\n")
            }
        }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Arbitre", texte))
        Toast.makeText(this, R.string.journal_copie, Toast.LENGTH_SHORT).show()
    }

    private fun dp(valeur: Int): Int = (valeur * resources.displayMetrics.density).toInt()

    companion object {
        /**
         * Au-delà, la course se prend depuis l'autre bout de la ville.
         *
         * Dix kilomètres d'approche, c'est un quart d'heure : la limite au-delà
         * de laquelle on n'attend plus un client, on va le chercher.
         */
        private const val SEUIL_CONFORTABLE = 10.0
    }
}
