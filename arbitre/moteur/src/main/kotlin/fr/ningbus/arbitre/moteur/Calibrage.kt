package fr.ningbus.arbitre.moteur

/**
 * Une course dont on possède les deux durées : celle qui a été promise, et
 * celle qui a été vécue.
 */
data class Mesure(
    val plateforme: String,
    /** Durée du trajet annoncée sur la carte d'offre, en minutes. */
    val minutesAnnoncees: Double,
    /** Durée du trajet constatée par la plateforme elle-même, en minutes. */
    val minutesReelles: Double,
) {
    /** 1,0 = l'annonce était juste. 1,20 = il a fallu vingt pour cent de plus. */
    val rapport: Double get() =
        if (minutesAnnoncees <= 0.0) 1.0 else minutesReelles / minutesAnnoncees
}

/**
 * Ce que les estimations d'une plateforme valent, mesuré sur ses propres
 * chiffres.
 *
 * Le moteur majore les durées annoncées quand la circulation est chargée, et
 * il le fait avec un coefficient **supposé** : 1,25 dans les bouchons, 1,0 sur
 * voie rapide. C'était le mieux qu'on pouvait faire sans rien mesurer, et ça
 * laissait passer le cas le plus fréquent — une longue course annoncée 45 min
 * qui en prend 52, sur des axes dégagés où le coefficient vaut exactement 1.
 *
 * Il y a mieux à faire, parce que les deux chiffres existent déjà sur le
 * téléphone. La carte d'offre annonce une durée ; l'écran « Détails de la
 * course » constate celle qui a été passée. Les rapprocher course après course
 * donne un coefficient **mesuré**, propre à ce chauffeur, à son secteur et à
 * ses heures — ce qu'aucune donnée de trafic générale ne saurait produire.
 *
 * Trois précautions gouvernent le calcul, et chacune répond à une façon de se
 * tromper :
 *
 *  - **la médiane, et non la moyenne.** Un appariement raté, une course
 *    interrompue, un client qui fait attendre vingt minutes : une seule valeur
 *    aberrante déplace une moyenne et ne déplace pas une médiane ;
 *  - **un plancher de mesures.** En dessous de [MESURES_MINIMALES], on ne rend
 *    rien du tout. Trois courses ne décrivent pas une plateforme, elles
 *    décrivent trois trajets ;
 *  - **des bornes.** Le coefficient est ramené entre [PLANCHER] et [PLAFOND].
 *    Si la mesure sort de là, c'est l'appariement qui est faux, pas la
 *    plateforme qui ment d'un facteur trois.
 */
object Calibrage {

    /** En dessous, on ne conclut rien : trois courses ne font pas une loi. */
    const val MESURES_MINIMALES = 5

    /** Bornes du coefficient rendu. Au-delà, c'est la mesure qui est fausse. */
    const val PLANCHER = 0.75
    const val PLAFOND = 1.75

    /**
     * Le coefficient à appliquer aux durées annoncées par cette plateforme,
     * ou null tant qu'on n'a pas de quoi le dire.
     */
    fun facteur(mesures: List<Mesure>, plateforme: String? = null): Double? {
        val retenues = mesures
            .filter { plateforme == null || it.plateforme.equals(plateforme, true) }
            .filter { it.minutesAnnoncees > 0.0 && it.minutesReelles > 0.0 }
        if (retenues.size < MESURES_MINIMALES) return null
        return mediane(retenues.map { it.rapport }).coerceIn(PLANCHER, PLAFOND)
    }

    /**
     * La même chose en une phrase, pour l'écran.
     *
     * Le nombre de mesures y figure toujours : un écart de 18 % sur cinq
     * courses et le même sur cinquante ne se lisent pas de la même façon, et
     * c'est au chauffeur d'en juger.
     */
    fun resume(mesures: List<Mesure>, plateforme: String? = null): String? {
        val f = facteur(mesures, plateforme) ?: return null
        val n = mesures.count { plateforme == null || it.plateforme.equals(plateforme, true) }
        val ecart = (f - 1.0) * 100.0
        val nom = plateforme ?: "les plateformes"
        return when {
            ecart >= 3.0 ->
                "$nom sous-estime tes trajets de ${fmt0(ecart)} % ($n courses)"
            ecart <= -3.0 ->
                "$nom surestime tes trajets de ${fmt0(-ecart)} % ($n courses)"
            else ->
                "$nom annonce juste, à ${fmt0(kotlin.math.abs(ecart))} % près ($n courses)"
        }
    }

    /**
     * La médiane, publique parce qu'elle sert aussi à mesurer la densité des
     * offres, et pour la même raison : une pause déjeuner déplace une moyenne
     * et laisse une médiane où elle est.
     */
    fun mediane(valeurs: List<Double>): Double {
        val tri = valeurs.sorted()
        val milieu = tri.size / 2
        return if (tri.size % 2 == 1) tri[milieu] else (tri[milieu - 1] + tri[milieu]) / 2.0
    }
}
