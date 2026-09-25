package fr.ningbus.arbitre.moteur

/**
 * Une course terminée, telle que la plateforme l'archive après coup.
 *
 * Distincte d'une [Course], qui est une *proposition* : celle-ci ne contient
 * que du constaté. Et il lui manque toujours la même chose — l'approche.
 *
 * Ce n'est pas un oubli des plateformes, c'est leur modèle : **elles ne
 * facturent pas le temps d'approche, donc elles ne l'archivent pas.** La carte
 * d'offre est le seul instant où ce chiffre existe, et le journal d'Arbitre le
 * seul endroit où il survit. C'est aussi pourquoi le rapprochement fait ici ne
 * porte que sur le trajet payé : c'est la seule partie dont on possède les
 * deux versions, l'annoncée et la réalisée.
 */
data class Realise(
    val prix: Double,
    val minutes: Double,
    val km: Double,
)

/**
 * Lecture de l'écran « Détails de la course ».
 *
 * Cet écran-là ne propose rien, donc il ne doit déclencher aucune bulle. Il
 * sert à une seule chose, et elle vaut le détour : il porte la **durée
 * réellement passée**, mesurée par la plateforme elle-même. Confrontée à la
 * durée annoncée sur la carte d'offre, elle dit si les estimations de cette
 * plateforme tiennent — et de combien elles se trompent.
 */
object Details {

    /**
     * Ce qui distingue un écran d'historique d'une carte d'offre.
     *
     * Les deux portent un prix, une distance et une durée : il faut donc autre
     * chose que des chiffres pour les séparer, exactement comme pour les
     * offres elles-mêmes. Ici la marque est le vocabulaire du passé — des
     * revenus, un total, un tarif déjà appliqué.
     */
    private val MARQUEURS = listOf(
        "détails de la course", "details de la course",
        "détails du trajet", "details du trajet",
        "vos revenus", "prix total du trajet",
        "trip details", "your earnings",
    )

    /**
     * Ce qui interdit de prendre un écran pour un bilan, quoi qu'il dise
     * d'autre.
     *
     * « Tarification affichée à l'avance » figurait d'abord parmi les
     * marqueurs, et c'était dangereux : Uber emploie le même vocabulaire sur
     * ses **cartes d'offre**, puisque le prix garanti est un argument de
     * vente. Une offre classée en bilan ne serait jamais arbitrée — la panne
     * la plus grave possible ici, et la plus silencieuse.
     *
     * La marque d'une offre est donc décisive et prime sur tout le reste :
     * un écran qu'on peut accepter ou refuser est une proposition, pas une
     * archive. C'est la même règle que celle qui gouverne la détection des
     * offres, prise dans l'autre sens.
     */
    private val MARQUEURS_OFFRE = listOf("accepter", "refuser", "accept", "decline")

    /**
     * « Durée 7 min 42 s ».
     *
     * L'étiquette ancre la lecture : sur cet écran plusieurs durées peuvent
     * cohabiter — temps d'attente, temps facturé — et seule celle qui suit le
     * mot « durée » est le temps de la course. Les secondes sont facultatives
     * et comptent : 7 min 42 s, c'est 7,7 min, et arrondir à 7 fausserait le
     * rapport qu'on cherche à mesurer.
     */
    private val RE_DUREE = Regex(
        """dur[ée]e\D{0,24}?(\d{1,3})\s*(?:min(?:ute)?s?|mn)\b(?:\D{0,6}?(\d{1,2})\s*s\b)?"""
    )

    /** « Distance 3.10 km », même principe d'ancrage. */
    private val RE_DISTANCE = Regex("""distance\D{0,24}?(\d{1,4}(?:[.,]\d{1,3})?)\s*kms?\b""")

    /** Cet écran est-il celui d'une course déjà faite ? */
    fun estEcran(texte: String): Boolean = estEcranNormalise(Analyseur.normaliser(texte))

    private fun estEcranNormalise(t: String): Boolean =
        MARQUEURS.any { t.contains(it) } && MARQUEURS_OFFRE.none { t.contains(it) }

    /**
     * Ce que l'écran constate, ou null s'il y manque quoi que ce soit.
     *
     * Rien n'est reconstitué : une mesure incomplète ne sert à rien, et une
     * mesure inventée abîmerait la calibration qu'elle est censée nourrir.
     */
    fun lire(texte: String): Realise? {
        val t = Analyseur.normaliser(texte)
        if (!estEcranNormalise(t)) return null

        val duree = RE_DUREE.find(t) ?: return null
        val minutes = (duree.groupValues[1].toDoubleOrNull() ?: return null) +
            (duree.groupValues[2].toDoubleOrNull() ?: 0.0) / 60.0

        val km = RE_DISTANCE.find(t)
            ?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: return null

        // Le prix : le plus élevé des montants de l'écran. Un écran de bilan
        // décompose souvent le total en postes — le total est le plus gros.
        val prix = Analyseur.montantsLisibles(t).maxOrNull() ?: return null

        if (minutes <= 0.0 || km <= 0.0 || prix <= 0.0) return null
        return Realise(prix = prix, minutes = minutes, km = km)
    }
}
