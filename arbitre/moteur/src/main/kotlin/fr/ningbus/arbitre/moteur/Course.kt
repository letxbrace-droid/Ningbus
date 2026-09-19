package fr.ningbus.arbitre.moteur

/**
 * Une proposition de course, telle qu'elle a pu être extraite de la
 * notification. Tout est nullable : une notification tronquée ou un format
 * inconnu ne doit jamais faire inventer un chiffre au moteur.
 *
 * Distances en kilomètres, durées en minutes, prix en euros.
 */
data class Course(
    /** Nom lisible de la plateforme ("Uber", "Bolt"…), ou le nom du paquet. */
    val plateforme: String = "",
    /** Montant annoncé au chauffeur. Sans lui, aucun arbitrage n'est possible. */
    val prix: Double? = null,
    /** Trajet à vide jusqu'au client. */
    val minutesApproche: Double? = null,
    val kmApproche: Double? = null,
    /** Trajet payé, client à bord. */
    val minutesTrajet: Double? = null,
    val kmTrajet: Double? = null,
    /** Ce que la notification disait, conservé tel quel pour le journal. */
    val texteBrut: String = "",
    /** Ce que l'analyseur n'a pas su lire. */
    val remarques: List<String> = emptyList(),
) {
    /** Le minimum vital pour arbitrer : un prix et une idée du trajet payé. */
    val exploitable: Boolean
        get() = prix != null && (kmTrajet != null || minutesTrajet != null)
}

/** État de la circulation, déduit de la vitesse moyenne implicite. */
enum class Trafic(val libelle: String) {
    INCONNU("—"),
    BOUCHONS("bouchons"),
    DENSE("dense"),
    FLUIDE("fluide"),
    VOIE_RAPIDE("voie rapide"),
}

/**
 * Vitesse moyenne implicite du trajet, en km/h.
 *
 * C'est la seule mesure de trafic dont on dispose, et c'est la bonne : la
 * durée annoncée par la plateforme est une ETA calculée sur le trafic réel
 * du moment. Le rapport distance/durée contient donc déjà les bouchons, sans
 * aucun appel réseau — donc sans latence.
 */
fun vitesse(km: Double?, minutes: Double?): Double? {
    if (km == null || minutes == null || minutes <= 0.0 || km <= 0.0) return null
    return km / (minutes / 60.0)
}

/** Classe une vitesse moyenne en état de circulation. */
fun trafic(kmh: Double?): Trafic = when {
    kmh == null -> Trafic.INCONNU
    kmh < 13.0 -> Trafic.BOUCHONS
    kmh < 22.0 -> Trafic.DENSE
    kmh < 45.0 -> Trafic.FLUIDE
    else -> Trafic.VOIE_RAPIDE
}

/**
 * Coefficient de prudence appliqué à la durée annoncée.
 *
 * Les ETA des plateformes sont optimistes, et elles le sont d'autant plus que
 * la circulation est déjà chargée : un bouchon se dégrade plus souvent qu'il
 * ne se résorbe pendant qu'on y roule. On majore donc la durée avant de
 * calculer l'euro/heure, plutôt que de découvrir le dépassement en route.
 */
fun prudence(trafic: Trafic): Double = when (trafic) {
    Trafic.BOUCHONS -> 1.25
    Trafic.DENSE -> 1.12
    Trafic.FLUIDE -> 1.0
    Trafic.VOIE_RAPIDE -> 1.0
    Trafic.INCONNU -> 1.0
}
