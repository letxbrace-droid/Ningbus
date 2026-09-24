package fr.ningbus.arbitre.moteur

/**
 * Une course planifiée, telle qu'une liste la présente.
 *
 * Distincte d'une [Course] à dessein : elle n'a pas d'approche. Une liste de
 * courses planifiées n'en annonce aucune, et c'est précisément ce qui la rend
 * piégeuse — le prix paraît bon parce que le trajet à vide pour aller
 * chercher le client n'apparaît nulle part.
 */
data class Planifiee(
    val prix: Double,
    /** Péage annoncé séparément. Il sort de la poche du chauffeur. */
    val peage: Double,
    val km: Double,
    /** « Bolt », « Green », « Comfort »… tel que la liste l'écrit. */
    val categorie: String,
    /** « Aujourd'hui, 10:30–10:35 », « 22 sept., 04:05–04:15 ». */
    val creneau: String,
    val depart: String,
    val arrivee: String,
) {
    /** Ce qui reste vraiment, péage déduit. */
    val prixNet: Double get() = prix - peage

    val euroParKm: Double get() = if (km > 0.0) prixNet / km else 0.0

    /** Deux courses identiques ne doivent être comptées qu'une fois. */
    val empreinte: String get() = "$prix|$peage|$km|$creneau|$depart"
}

/**
 * Une course planifiée passée au barème du chauffeur.
 *
 * Le classement ne se fait pas sur l'euro par kilomètre, et c'est délibéré.
 * Sur le relevé du 24/09, une course à 3,06 €/km et une à 1,13 €/km
 * supportaient le même trajet d'approche — 8,8 km contre 8,5 — parce que la
 * seconde était quatre fois plus longue et passait donc son coût fixe sur
 * plus de kilomètres payés. L'euro par kilomètre dit ce que vaut la course ;
 * le budget d'approche dit si **tu** peux la prendre.
 */
data class RangPlanifiee(
    val course: Planifiee,
    /**
     * Kilomètres d'approche que la course supporte avant de passer sous
     * l'objectif horaire. Zéro veut dire : même en étant déjà sur place,
     * elle ne tient pas.
     */
    val budgetApprocheKm: Double,
    /** Ce qu'elle rapporte si le client est devant toi, sans un mètre à vide. */
    val euroHeureSurPlace: Double,
    /**
     * Un autre prix identique figure dans la même liste.
     *
     * C'est la signature d'un tarif plancher, et le piège le plus coûteux du
     * relevé : cinq courses à 19,00 € y couvraient de 6,2 à 20,6 km. Même
     * argent, trois fois le travail.
     */
    val auPlancher: Boolean,
) {
    /** Tenable sans bouger d'où l'on est déjà. */
    val tenable: Boolean get() = budgetApprocheKm > 0.0
}

/** Ce que le comparateur a à dire d'une liste entière. */
data class Comparatif(
    val rangs: List<RangPlanifiee>,
    val medianeEuroKm: Double,
    /** Total des péages que les prix affichés masquent. */
    val peageMasque: Double,
) {
    val vide: Boolean get() = rangs.isEmpty()

    /** Le meilleur budget d'approche de la liste : ce qu'elle tolère au mieux. */
    val budgetMaximal: Double get() = rangs.firstOrNull()?.budgetApprocheKm ?: 0.0
}

/**
 * Comparateur de courses planifiées.
 *
 * Il existe parce que la liste « Demandes de courses planifiées » de Bolt
 * empile plusieurs offres dans un seul écran, et qu'Arbitre refuse depuis la
 * 2.0 de l'arbitrer — mélanger le prix de l'une et la distance de l'autre
 * produisait des verdicts faux annoncés à 83 % de confiance.
 *
 * Refuser était juste et ne suffisait pas : l'écran reste celui où se joue
 * une matinée. Dix-neuf offres relevées le 24/09 tenaient toutes dans la même
 * demi-heure — donc une seule serait faite — et l'écart entre le meilleur et
 * le pire choix allait de 0,80 à 3,06 €/km. Le comparateur découpe la liste
 * au lieu de la mélanger, et classe.
 */
object Planifiees {

    /**
     * L'en-tête d'une offre : le prix, le péage éventuel, puis le créneau.
     *
     * C'est lui qui découpe la liste, et non le simple décompte des montants :
     * une ligne de tarif ou un bonus porteraient aussi un montant, mais aucun
     * n'est suivi d'un créneau.
     */
    private val RE_ENTETE = Regex(
        """^\s*(\d{1,4}(?:[.,]\d{1,2})?)\s*€\s*[•·]\s*""" +
            """(?:(\d{1,4}(?:[.,]\d{1,2})?)\s*€\s*p[ée]age\s*[•·]\s*)?(.*)$"""
    )

    private val RE_KM = Regex("""(\d{1,4}(?:[.,]\d{1,3})?)\s*kms?\b""", RegexOption.IGNORE_CASE)

    private val RE_LIEU = Regex("""^\s*Pr[èe]s de\s+(.+)$""", RegexOption.IGNORE_CASE)

    /**
     * Les catégories de véhicule, qui tiennent sur une ligne à elles seules.
     *
     * Liste fermée : c'est ce qui évite de prendre « Carte Google » ou le nom
     * d'une rue pour une catégorie.
     */
    private val CATEGORIES = listOf(
        "bolt", "green", "comfort", "xl", "van", "pet", "business",
        "premium", "eco", "économique", "economique", "access",
    )

    /** Ce qu'un écran doit porter pour qu'on le prenne pour une liste. */
    fun estUneListe(texte: String): Boolean = decouper(texte).size >= 2

    /**
     * Découpe un écran en offres distinctes.
     *
     * Chaque offre court de son en-tête au suivant : tout ce qui traîne entre
     * les deux — la carte, les libellés de jour, le bouton « Voir toutes les
     * demandes » — se trouve ignoré sans qu'on ait à le nommer.
     */
    fun decouper(texte: String): List<Planifiee> {
        val lignes = texte.lines()
        val debuts = lignes.indices.filter { RE_ENTETE.find(lignes[it]) != null }
        if (debuts.isEmpty()) return emptyList()

        val offres = mutableListOf<Planifiee>()
        for ((rang, debut) in debuts.withIndex()) {
            val fin = debuts.getOrNull(rang + 1) ?: lignes.size
            lire(lignes.subList(debut, fin))?.let { offres += it }
        }
        // Une liste qui défile repasse les mêmes offres : on ne les compte
        // qu'une fois, sans quoi la médiane se déplacerait au gré du pouce.
        return offres.distinctBy { it.empreinte }
    }

    private fun lire(bloc: List<String>): Planifiee? {
        val entete = RE_ENTETE.find(bloc.first()) ?: return null
        val prix = nombre(entete.groupValues[1]) ?: return null
        val peage = nombre(entete.groupValues[2]) ?: 0.0

        // Un créneau qui déborde sur la ligne suivante reste un créneau.
        val creneau = entete.groupValues[3].trim()
            .ifEmpty { bloc.getOrNull(1)?.trim().orEmpty() }

        val corps = bloc.drop(1)
        val km = corps.firstNotNullOfOrNull { l -> RE_KM.find(l)?.let { nombre(it.groupValues[1]) } }
            ?: return null

        val categorie = corps
            .map { it.trim() }
            .firstOrNull { it.lowercase() in CATEGORIES }
            .orEmpty()

        val lieux = corps.mapNotNull { RE_LIEU.find(it)?.groupValues?.get(1) }.map(::abreger)

        return Planifiee(
            prix = prix,
            peage = peage,
            km = km,
            categorie = categorie,
            creneau = creneau,
            depart = lieux.getOrNull(0).orEmpty(),
            arrivee = lieux.getOrNull(1).orEmpty(),
        )
    }

    /**
     * Une adresse tient en une ligne de bulle ou ne sert à rien.
     *
     * « Près de Rue Du Landy, Saint-Denis 93210, France » se lit, au volant,
     * « Saint-Denis ». La commune est la seule partie qui aide à décider :
     * c'est elle qui dit si la course t'exile.
     */
    internal fun abreger(lieu: String): String {
        val morceaux = lieu.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val sansPays = morceaux.filterNot { it.equals("France", ignoreCase = true) }
        val commune = sansPays.lastOrNull() ?: return lieu.trim()
        // « Paris 75015 » → « Paris » ; « Saint-Denis 93210 » → « Saint-Denis ».
        return commune.replace(Regex("""\s*\d{5}\s*$"""), "").trim().ifEmpty { commune }
    }

    // --- Le classement -------------------------------------------------------

    /** Passe une liste au barème, et la classe. */
    fun comparer(courses: List<Planifiee>, bareme: Bareme): Comparatif {
        if (courses.isEmpty()) return Comparatif(emptyList(), 0.0, 0.0)

        val prixVus = courses.groupingBy { it.prix }.eachCount()
        val rangs = courses
            .map { c ->
                RangPlanifiee(
                    course = c,
                    budgetApprocheKm = budgetApproche(c, bareme),
                    euroHeureSurPlace = euroHeure(c, bareme, 0.0),
                    auPlancher = (prixVus[c.prix] ?: 0) > 1,
                )
            }
            .sortedWith(
                compareByDescending<RangPlanifiee> { it.budgetApprocheKm }
                    .thenByDescending { it.course.euroParKm }
            )

        val tries = courses.map { it.euroParKm }.sorted()
        return Comparatif(
            rangs = rangs,
            medianeEuroKm = tries[tries.size / 2],
            peageMasque = courses.sumOf { it.peage },
        )
    }

    fun comparer(texte: String, bareme: Bareme): Comparatif = comparer(decouper(texte), bareme)

    /**
     * Ce que la course rapporte par heure mobilisée, pour une approche donnée.
     *
     * Même calcul que [Arbitre], au même barème, à une différence près : le
     * retour à vide est compté dans les kilomètres et non dans le temps, comme
     * pour une course ordinaire.
     */
    fun euroHeure(c: Planifiee, bareme: Bareme, kmApproche: Double): Double {
        val kmRetour = bareme.partRetour * c.km
        val recette = c.prixNet * (1.0 - bareme.commission)
        val net = recette - bareme.coutKm * (c.km + kmApproche + kmRetour)
        val minutes = minutesApproche(kmApproche) + bareme.minutesAttente + minutesPour(c.km)
        return if (minutes <= 0.0) 0.0 else net / (minutes / 60.0)
    }

    /**
     * Combien de kilomètres d'approche la course supporte avant de tomber sous
     * l'objectif.
     *
     * C'est le seul chiffre qui décide, parce que c'est le seul que la liste
     * ne montre pas. Sur le relevé du 24/09, aucune des dix-neuf offres ne
     * supportait plus de 9 km — propriété structurelle du tarif planifié, qui
     * est calculé comme si le chauffeur était déjà devant la porte.
     */
    fun budgetApproche(c: Planifiee, bareme: Bareme): Double {
        if (euroHeure(c, bareme, 0.0) < bareme.objectifHeure) return 0.0
        var bas = 0.0
        var haut = PLAFOND_APPROCHE
        repeat(40) {
            val milieu = (bas + haut) / 2.0
            if (euroHeure(c, bareme, milieu) >= bareme.objectifHeure) bas = milieu else haut = milieu
        }
        return bas
    }

    /** Au-delà, l'approche n'est plus une approche mais un déménagement. */
    private const val PLAFOND_APPROCHE = 150.0

    private fun minutesPour(km: Double): Double =
        if (km <= 0.0) 0.0 else km / vitesseTypique(km) * 60.0

    /**
     * Temps d'un trajet d'approche, en minutes.
     *
     * [vitesseTypique] procède par paliers — 18, 26, 38 puis 55 km/h — et
     * c'est ce qu'il faut pour une course, dont la distance est connue une
     * fois pour toutes. Chercher un budget d'approche, en revanche, revient à
     * faire varier la distance : aux paliers, **treize kilomètres prenaient
     * moins de temps que neuf**, l'euro/heure remontait en franchissant la
     * marche, et le budget calculé ne voulait plus rien dire. Un essai l'a
     * établi avant que le comparateur ne sorte.
     *
     * Même idée, donc — une approche longue emprunte des axes, une approche
     * courte reste dans les rues — mais continue : 17 km/h sur un kilomètre,
     * 25 sur six, 36 sur dix-sept, et le temps croît strictement avec la
     * distance. La courbe est calée sur les paliers qu'elle remplace, si bien
     * qu'aucun chiffre du barème ne change de sens.
     */
    internal fun minutesApproche(km: Double): Double {
        if (km <= 0.0) return 0.0
        val vitesse = 15.0 + 45.0 * km / (km + 20.0)
        return km / vitesse * 60.0
    }

    private fun nombre(s: String): Double? =
        if (s.isEmpty()) null else s.replace(',', '.').toDoubleOrNull()
}
