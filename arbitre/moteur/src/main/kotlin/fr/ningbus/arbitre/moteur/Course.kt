package fr.ningbus.arbitre.moteur

/**
 * Ce qui rend une lecture **douteuse**, par opposition à incomplète.
 *
 * La distinction a mis trois erreurs de terrain à s'imposer, et c'est la même
 * à chaque fois. À Grigny, un « 1 » lu « l ». Sur une carte du 25/09, un
 * montant de 4 768 € pour 47,7 km. À Antony, la distance du bandeau GPS prise
 * pour celle de la course. Les trois fois, la bulle annonçait **100 % de
 * confiance** — parce que tous les champs étaient remplis.
 *
 * Un champ vide se voit et fait baisser la confiance. Un champ rempli de
 * travers ne se voit pas et la fait monter. C'est exactement le contraire de
 * ce qu'il faudrait, et aucune finesse sur les poids ne le corrige : il faut
 * que l'analyseur dise quand il a dû choisir, deviner ou écarter.
 *
 * Ces moments-là sont peu nombreux et parfaitement identifiables — ce sont
 * ceux où le code prend une décision qu'un texte sans ambiguïté n'aurait pas
 * exigée.
 */
enum class Doute(val libelle: String) {
    /** Des nombres de l'écran n'appartenaient pas à la carte d'offre. */
    INTRUS("des nombres étrangers à la carte ont été écartés"),

    /** Plusieurs sommes pouvaient être le prix ; on a tranché. */
    PLUSIEURS_MONTANTS("plusieurs montants possibles, le plus élevé retenu"),

    /** Une attribution donnait une vitesse impossible et a été refaite. */
    ROLES_RELUS("une durée a dû être relue comme temps d'approche"),

    /** Un même nombre figurait deux fois : lequel décrivait quoi ? */
    REPETITION("un nombre figurait deux fois à l'écran"),
}

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
    /**
     * Ce que l'analyseur a dû trancher, deviner ou écarter.
     *
     * Séparé de [remarques], qui est du texte libre destiné à l'écran : ceux-ci
     * sont typés parce que la confiance s'en sert pour descendre. Une lecture
     * troublée ne peut pas se présenter comme certaine.
     */
    val doutes: List<Doute> = emptyList(),
) {
    /** Le minimum vital pour arbitrer : un prix et une idée du trajet payé. */
    val exploitable: Boolean
        get() = prix != null && (kmTrajet != null || minutesTrajet != null)
}

/**
 * Complète une lecture partielle par la précédente.
 *
 * Un écran ne se dessine pas d'un bloc : le prix peut être attaché à l'arbre
 * des vues avant les lignes du trajet, et une lecture déclenchée au millième
 * de seconde près attrape une carte à moitié construite. Plutôt que de rendre
 * un verdict sur ce qu'on a vu à cet instant, on cumule les lectures
 * successives — chaque champ manquant se remplit à la première lecture qui
 * le porte.
 *
 * Deux prix différents désignent deux offres différentes : dans ce cas on ne
 * mélange rien, au risque sinon d'arbitrer une course qui n'existe pas.
 */
fun Course.completer(precedente: Course?): Course {
    if (precedente == null) return this
    if (prix != null && precedente.prix != null && prix != precedente.prix) return this
    return copy(
        plateforme = plateforme.ifEmpty { precedente.plateforme },
        prix = prix ?: precedente.prix,
        minutesApproche = minutesApproche ?: precedente.minutesApproche,
        kmApproche = kmApproche ?: precedente.kmApproche,
        minutesTrajet = minutesTrajet ?: precedente.minutesTrajet,
        kmTrajet = kmTrajet ?: precedente.kmTrajet,
        texteBrut = if (texteBrut.length >= precedente.texteBrut.length) {
            texteBrut
        } else {
            precedente.texteBrut
        },
        remarques = (remarques + precedente.remarques).distinct(),
        // Les doutes se cumulent et ne s'effacent jamais : une lecture
        // complétée par une autre hérite des hésitations des deux.
        doutes = (doutes + precedente.doutes).distinct(),
    )
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

/**
 * Vitesse moyenne à laquelle se parcourt normalement un trajet de cette
 * longueur, en km/h.
 *
 * Une course longue va plus vite qu'une course courte : elle emprunte des
 * axes, quand la course courte reste dans les rues et les carrefours. C'est
 * ce qui interdit d'extrapoler la durée d'un trajet depuis la vitesse de
 * l'approche — 2,5 km de rues à 17 km/h ne disent rien de 12 km de
 * départementale.
 */
fun vitesseTypique(km: Double): Double = when {
    km < 3.0 -> 18.0
    km < 10.0 -> 26.0
    km < 25.0 -> 38.0
    else -> 55.0
}

/**
 * Ce que l'approche apprend du trafic, et rien de plus.
 *
 * Elle est mesurée : distance et durée sont toutes deux annoncées. Comparée
 * à ce qu'on attendrait normalement sur cette distance, elle donne un
 * coefficient — 0,8 si ça roule mal aujourd'hui, 1,2 si c'est dégagé — qu'on
 * applique ensuite à la vitesse typique du trajet. La borne évite qu'une
 * approche de 300 m dans un parking ne condamne une course de 40 km.
 */
fun facteurTrafic(kmApproche: Double?, minutesApproche: Double?): Double? {
    val mesuree = vitesse(kmApproche, minutesApproche) ?: return null
    val attendue = vitesseTypique(kmApproche ?: return null)
    return (mesuree / attendue).coerceIn(0.6, 1.5)
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
