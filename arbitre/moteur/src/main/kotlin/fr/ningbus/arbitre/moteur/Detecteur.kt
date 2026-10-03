package fr.ningbus.arbitre.moteur

/** Ce qu'un écran est, autant qu'on puisse en juger. */
enum class Nature(val libelle: String) {
    OFFRE_CERTAINE("offre"),
    OFFRE_PROBABLE("offre probable"),
    AMBIGU("écran ambigu"),

    /** Une liste de courses, et non une carte : les chiffres se mélangeraient. */
    PLUSIEURS_OFFRES("plusieurs offres sur l'écran"),

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
 *
 * **Une journée de terrain a montré que le score seul ne suffisait pas.** Sur
 * 80 verdicts rendus le 21/09, 55 portaient sur des écrans qui n'avaient
 * jamais rien eu d'une course : l'écran d'accueil, la barre de notifications,
 * une boîte mail, une conversation. Aucun n'était une offre, tous atteignaient
 * 60 points — un montant (20), deux nombres suivis de « km » (15 + 15), une
 * durée (10) — c'est-à-dire au-dessus du seuil « offre probable ».
 *
 * Le compte était juste ; c'est la conclusion qui ne l'était pas. Des chiffres
 * ne prouvent rien : n'importe quel texte en contient. Une offre, elle, porte
 * une marque qui n'appartient qu'à elle — le bouton qui l'accepte, ou le
 * compte à rebours qui l'emporte. Sans cette marque, l'écran ne dépasse plus
 * « ambigu », quel que soit son score.
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

    /**
     * Ce qui trahit un écran de navigation, course déjà commencée.
     *
     * « restants » y figurait seul, et se retournait contre le compte à
     * rebours : « 12 s restantes » perdait 30 points pour en gagner 10, si
     * bien que la marque la plus sûre d'une offre la rendait moins
     * reconnaissable. Le mot n'accuse plus que collé à une distance — « 8.2 km
     * restants », ce qui reste d'un trajet en cours.
     */
    private val NAVIGATION = listOf(
        "en route", "arrivée estimée", "km restant",
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

        val acceptation = ACCEPTATION.any { t.contains(it) }
        val rebours = REBOURS.any { t.contains(it) }
        if (acceptation) compter(30, "bouton d'acceptation")
        if (rebours) compter(10, "compte à rebours")
        if (course.prix != null) compter(20, "montant")
        if (course.kmApproche != null) compter(15, "distance d'approche")
        if (course.kmTrajet != null) compter(15, "distance de course")
        if (course.minutesApproche != null || course.minutesTrajet != null) {
            compter(10, "durée")
        }

        if (NAVIGATION.any { t.contains(it) }) compter(-30, "vocabulaire de navigation")
        if (HISTORIQUE.any { t.contains(it) }) compter(-30, "vocabulaire d'historique")
        if (REGLAGES.any { t.contains(it) }) compter(-40, "écran de réglages")

        val offres = Analyseur.compterOffres(texte)
        if (offres > 1) compter(-60, "$offres offres empilées")

        // La marque d'une offre : ce qui l'accepte, ou ce qui l'expire. Sans
        // elle, des chiffres restent des chiffres.
        val marque = acceptation || rebours

        val nature = when {
            offres > 1 -> Nature.PLUSIEURS_OFFRES
            !marque -> if (score >= AMBIGU) Nature.AMBIGU else Nature.AUTRE
            score >= CERTAINE -> Nature.OFFRE_CERTAINE
            score >= PROBABLE -> Nature.OFFRE_PROBABLE
            score >= AMBIGU -> Nature.AMBIGU
            else -> Nature.AUTRE
        }
        return Jugement(score, nature, indices)
    }
}
