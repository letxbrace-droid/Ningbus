package fr.ningbus.arbitre.moteur

/** Ce qu'un écran est, autant qu'on puisse en juger. */
enum class Nature(val libelle: String) {
    OFFRE_CERTAINE("offre"),
    OFFRE_PROBABLE("offre probable"),
    AMBIGU("écran ambigu"),
    AUTRE("pas une offre"),
}

/** Le jugement porté sur un écran, avec ce qui l'a produit. */
data class Jugement(
    val score: Int,
    val nature: Nature,
    /** Chaque indice retenu et ce qu'il a pesé — le calcul est relisible. */
    val indices: List<String>,
) {
    /** Assez sûr pour déranger le chauffeur. */
    val arbitrable: Boolean
        get() = nature == Nature.OFFRE_CERTAINE || nature == Nature.OFFRE_PROBABLE

    val resume: String get() = "${nature.libelle} ($score)"
}

/**
 * Distinguer une offre de tout le reste de l'écran d'un chauffeur.
 *
 * L'ancienne règle était binaire : un bouton d'acceptation, **ou** deux
 * distances distinctes. Elle tenait, mais elle ne savait rien dire. Un écran
 * écarté l'était sans motif, et un écran accepté à tort l'était sans qu'on
 * puisse voir ce qui avait emporté la décision.
 *
 * Le score répond aux deux. Il additionne des indices dont chacun se défend
 * seul, et retranche ce qui trahit un autre écran — la navigation et
 * l'historique affichent eux aussi des prix et des kilomètres, et une bulle
 * qui surgit en pleine conduite coûte plus cher qu'une offre manquée.
 *
 * Les poids ne sont pas des réglages : ils sont fixés par ce qu'un indice
 * prouve. Un bouton « Mise en relation » ne se trouve que sur une offre ; un
 * montant, sur à peu près tout.
 */
object Detecteur {

    private const val CERTAINE = 75
    private const val PROBABLE = 55
    private const val AMBIGU = 35

    /** Le bouton qui accepte la course : l'indice qui ne trompe pas. */
    private val ACCEPTATION = listOf(
        "mise en relation", "accepter", "accept", "j'accepte",
        "correspondre", "prendre la course",
    )

    /** Un compte à rebours : il n'y en a que sur une offre. */
    private val REBOURS = listOf("secondes restantes", "s restantes", "temps restant")

    /** Ce qui trahit un écran de navigation, course déjà commencée. */
    private val NAVIGATION = listOf(
        "en route", "arrivée estimée", "restants", "restantes",
        "terminer la course", "naviguer", "itinéraire", "démarrer la course",
        "passager à bord", "je suis arrivé",
    )

    /** Ce qui trahit un écran d'historique ou de gains. */
    private val HISTORIQUE = listOf(
        "historique", "courses terminées", "gains", "revenus",
        "cette semaine", "aujourd'hui vous avez", "relevé",
    )

    /** Ce qui trahit un écran de réglages ou de tarifs. */
    private val REGLAGES = listOf(
        "préférences", "paramètres", "réglages", "tarifs",
        "conditions", "assistance", "aide",
    )

    fun juger(texte: String, course: Course): Jugement {
        val t = texte.lowercase()
        var score = 0
        val indices = mutableListOf<String>()

        fun compter(points: Int, motif: String) {
            score += points
            indices += "${if (points > 0) "+" else ""}$points $motif"
        }

        if (ACCEPTATION.any { t.contains(it) }) compter(30, "bouton d'acceptation")
        if (REBOURS.any { t.contains(it) }) compter(10, "compte à rebours")
        if (course.prix != null) compter(20, "montant")
        if (course.kmApproche != null) compter(15, "distance d'approche")
        if (course.kmTrajet != null) compter(15, "distance de course")
        if (course.minutesApproche != null || course.minutesTrajet != null) {
            compter(10, "durée")
        }

        if (NAVIGATION.any { t.contains(it) }) compter(-30, "vocabulaire de navigation")
        if (HISTORIQUE.any { t.contains(it) }) compter(-30, "vocabulaire d'historique")
        if (REGLAGES.any { t.contains(it) }) compter(-40, "écran de réglages")

        val nature = when {
            score >= CERTAINE -> Nature.OFFRE_CERTAINE
            score >= PROBABLE -> Nature.OFFRE_PROBABLE
            score >= AMBIGU -> Nature.AMBIGU
            else -> Nature.AUTRE
        }
        return Jugement(score, nature, indices)
    }
}
