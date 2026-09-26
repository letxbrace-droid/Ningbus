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
    /** Ce que l'analyseur a dû trancher, deviner ou écarter pour en arriver là. */
    val doutes: List<Doute> = emptyList(),
) {
    // Arrondi, et non troncature : 92,5 % affiché « 92 % » ferait douter
    // d'un calcul par ailleurs juste.
    val pourcent: Int get() = Math.round(score * 100).toInt().coerceIn(0, 100)

    /** Au-dessous, le verdict ne mérite pas de feu vert. */
    val fiable: Boolean get() = score >= SEUIL_FIABLE

    /** Les champs qui manquent — ce qu'il faudrait lire pour mieux conclure. */
    val manquants: List<Lecture> get() = lectures.filter { it.etat == EtatLecture.ABSENT }

    /**
     * Une ligne pour la bulle : « confiance 87 % », et « lecture troublée »
     * quand l'analyseur a dû trancher.
     *
     * Le mot compte autant que le chiffre. « 70 % » se lit comme un petit
     * manque ; « lecture troublée » dit que ce qui est affiché peut être faux,
     * ce qui n'est pas la même invitation à vérifier.
     */
    val resume: String get() =
        if (doutes.isEmpty()) "confiance $pourcent %"
        else "confiance $pourcent % — lecture troublée"

    companion object {
        const val SEUIL_FIABLE = 0.75

        /**
         * Ce qu'une hésitation de lecture coûte, en proportion.
         *
         * Multiplicatif et non soustractif : deux hésitations se composent
         * au lieu de s'additionner, et le score ne peut pas devenir négatif.
         *
         * La valeur n'est pas arbitraire. Elle est choisie pour qu'**un seul
         * doute fasse passer une lecture parfaite sous [SEUIL_FIABLE]** :
         * 1,00 × 0,70 = 0,70, sous les 0,75 requis. C'est tout l'objet de la
         * manœuvre — une carte dont tous les champs sont remplis mais dont
         * l'un a peut-être été mal choisi ne doit plus pouvoir obtenir de feu
         * vert. Elle peut encore être refusée, ce qui est sans danger ; elle
         * ne peut plus être recommandée.
         *
         * La répétition d'un nombre coûte moins cher : c'est une gêne de
         * lecture courante sur des cartes qui affichent deux fois la même
         * distance, et le moteur la traite correctement depuis le 21/09. Elle
         * pèse sans condamner.
         */
        private const val COUT_DOUTE = 0.70
        private const val COUT_REPETITION = 0.88

        /** Poids de chaque champ dans le calcul final. Leur somme fait 1. */
        private const val POIDS_PRIX = 0.40
        private const val POIDS_KM_TRAJET = 0.20
        private const val POIDS_KM_APPROCHE = 0.15
        private const val POIDS_MINUTES_TRAJET = 0.15
        private const val POIDS_MINUTES_APPROCHE = 0.10

        /**
         * @param trajetEstime la durée ou la distance du trajet a été
         *   reconstituée par le moteur plutôt que lue.
         * @param prixDouteux un montant a bien été lu, mais il ne peut pas
         *   être le prix de cette course. Il compte alors pour rien : un champ
         *   rempli de travers vaut moins qu'un champ vide, puisqu'il se
         *   présente avec l'assurance de celui qui est lu. C'est le défaut que
         *   cette classe existe pour corriger, et il serait absurde qu'elle le
         *   reproduise sur le champ qui pèse à lui seul quarante pour cent.
         */
        fun de(
            course: Course,
            kmTrajetEstime: Boolean = false,
            minutesTrajetEstime: Boolean = false,
            prixDouteux: Boolean = false,
        ): Confiance {
            val lectures = listOf(
                ligne("prix", course.prix?.takeIf { !prixDouteux }?.let { "${fmt2(it)} €" }, false),
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
            val rempli = lectures.zip(poids).sumOf { (lecture, poids) ->
                when (lecture.etat) {
                    EtatLecture.LU -> poids
                    EtatLecture.ESTIME -> poids / 2.0
                    EtatLecture.ABSENT -> 0.0
                }
            }

            // Et maintenant ce que les champs remplis valent vraiment. Un
            // champ vide se voit et fait déjà baisser le score ci-dessus ; un
            // champ rempli de travers ne se voit pas et le faisait monter.
            // C'est ce renversement-là que les doutes corrigent.
            val score = course.doutes.fold(rempli) { acc, doute ->
                acc * if (doute == Doute.REPETITION) COUT_REPETITION else COUT_DOUTE
            }
            return Confiance(score, lectures, course.doutes)
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
