package fr.ningbus.arbitre.moteur

/** Ce qu'on sait d'un champ de l'offre. */
enum class EtatLecture(val signe: String) {
    /** Lu tel quel dans l'offre. */
    LU("✓"),

    /** Absent de l'offre, reconstitué par le moteur. */
    ESTIME("≈"),

    /** Ni lu, ni reconstituable. */
    ABSENT("✗"),
}

/** Un champ, son état, et sa valeur quand il y en a une. */
data class Lecture(
    val champ: String,
    val etat: EtatLecture,
    val valeur: String? = null,
) {
    override fun toString(): String =
        "${etat.signe} $champ" + (valeur?.let { " : $it" } ?: "")
}

/**
 * Ce que vaut le verdict, séparément de ce qu'il dit.
 *
 * Deux questions se confondaient jusqu'ici en une seule. « Cette course
 * est-elle rentable ? » et « ai-je assez lu pour le dire ? » n'ont pourtant
 * rien à voir : un LAISSE sur données complètes et un LAISSE calculé sur une
 * approche inventée se ressemblent à l'écran et ne se valent pas du tout.
 *
 * La confiance répond à la seconde. Elle pondère chaque champ par ce que son
 * absence coûte au calcul : le prix vaut à lui seul le double du reste
 * réunis, parce que sans lui il n'y a rien à arbitrer ; la durée du trajet
 * pèse moins que sa distance, parce qu'elle s'estime honorablement quand la
 * distance est connue.
 *
 * Un champ reconstitué compte pour la moitié d'un champ lu. Ce n'est pas
 * arbitraire : une estimation de durée tirée de la distance et du trafic
 * mesuré vaut mieux que rien, et beaucoup moins qu'une donnée annoncée par
 * la plateforme.
 */
data class Confiance(
    val score: Double,
    val lectures: List<Lecture>,
) {
    // Arrondi, et non troncature : 92,5 % affiché « 92 % » ferait douter
    // d'un calcul par ailleurs juste.
    val pourcent: Int get() = Math.round(score * 100).toInt().coerceIn(0, 100)

    /** Au-dessous, le verdict ne mérite pas de feu vert. */
    val fiable: Boolean get() = score >= SEUIL_FIABLE

    /** Les champs qui manquent — ce qu'il faudrait lire pour mieux conclure. */
    val manquants: List<Lecture> get() = lectures.filter { it.etat == EtatLecture.ABSENT }

    /** Une ligne pour la bulle : « confiance 87 % ». */
    val resume: String get() = "confiance $pourcent %"

    companion object {
        const val SEUIL_FIABLE = 0.75

        /** Poids de chaque champ dans le calcul final. Leur somme fait 1. */
        private const val POIDS_PRIX = 0.40
        private const val POIDS_KM_TRAJET = 0.20
        private const val POIDS_KM_APPROCHE = 0.15
        private const val POIDS_MINUTES_TRAJET = 0.15
        private const val POIDS_MINUTES_APPROCHE = 0.10

        /**
         * @param trajetEstime la durée ou la distance du trajet a été
         *   reconstituée par le moteur plutôt que lue.
         */
        fun de(
            course: Course,
            kmTrajetEstime: Boolean = false,
            minutesTrajetEstime: Boolean = false,
        ): Confiance {
            val lectures = listOf(
                ligne("prix", course.prix?.let { "${fmt2(it)} €" }, false),
                ligne(
                    "distance de la course",
                    course.kmTrajet?.let { "${fmt1(it)} km" }
                        ?: if (kmTrajetEstime) "estimée" else null,
                    kmTrajetEstime && course.kmTrajet == null,
                ),
                ligne("distance d'approche", course.kmApproche?.let { "${fmt1(it)} km" }, false),
                ligne(
                    "durée de la course",
                    course.minutesTrajet?.let { "${fmt0(it)} min" }
                        ?: if (minutesTrajetEstime) "estimée" else null,
                    minutesTrajetEstime && course.minutesTrajet == null,
                ),
                ligne(
                    "durée d'approche",
                    course.minutesApproche?.let { "${fmt0(it)} min" },
                    false,
                ),
            )
            val poids = listOf(
                POIDS_PRIX,
                POIDS_KM_TRAJET,
                POIDS_KM_APPROCHE,
                POIDS_MINUTES_TRAJET,
                POIDS_MINUTES_APPROCHE,
            )
            val score = lectures.zip(poids).sumOf { (lecture, poids) ->
                when (lecture.etat) {
                    EtatLecture.LU -> poids
                    EtatLecture.ESTIME -> poids / 2.0
                    EtatLecture.ABSENT -> 0.0
                }
            }
            return Confiance(score, lectures)
        }

        private fun ligne(champ: String, valeur: String?, estime: Boolean): Lecture = Lecture(
            champ = champ,
            etat = when {
                valeur == null -> EtatLecture.ABSENT
                estime -> EtatLecture.ESTIME
                else -> EtatLecture.LU
            },
            valeur = valeur,
        )
    }
}
