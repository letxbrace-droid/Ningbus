package fr.ningbus.arbitre.moteur

/**
 * Extraction des chiffres d'une notification de course.
 *
 * Le texte est du langage naturel, différent d'une plateforme à l'autre et
 * d'une version d'app à l'autre. La méthode est donc volontairement tolérante :
 *
 *  1. on repère tous les montants, toutes les durées, toutes les distances,
 *     avec leur position dans le texte ;
 *  2. on classe chaque nombre en « approche » ou « trajet » selon les mots
 *     qui l'entourent ("de vous", "de trajet"…) ;
 *  3. ce qui reste non classé est attribué dans l'ordre de lecture, car
 *     toutes les plateformes annoncent l'approche avant le trajet ;
 *  4. on vérifie la cohérence du résultat (une vitesse implicite absurde
 *     trahit une mauvaise attribution).
 *
 * Rien n'est deviné en silence : tout ce qui est estimé ou incertain ressort
 * dans [Course.remarques], et l'arbitre refuse le feu vert sur données
 * incomplètes.
 */
object Analyseur {

    // --- Motifs ------------------------------------------------------------

    private val RE_MONTANT = Regex(
        """€\s*(\d{1,4}(?:[.,]\d{1,2})?)|(\d{1,4}(?:[.,]\d{1,2})?)\s*(?:€|euros?\b|eur\b)"""
    )
    private val RE_MINUTES = Regex("""(\d{1,3}(?:[.,]\d)?)\s*(?:min(?:ute)?s?\b|mn\b)""")
    private val RE_HEURES = Regex("""(\d)\s*h\s*(\d{1,2})?\b""")
    private val RE_KM = Regex("""(\d{1,4}(?:[.,]\d{1,3})?)\s*(?:kms?\b|kilom[èe]tres?\b)""")
    private val RE_METRES = Regex("""(\d{2,4})\s*(?:m\b|m[èe]tres?\b)""")

    /** Mots qui désignent le trajet à vide vers le client. */
    private val MOTS_APPROCHE = listOf(
        "de vous", "de toi", "à vous", "a vous", "away", "approche",
        "prise en charge", "pickup", "récupér", "recuper", "aller chercher",
        "du client", "vers le client", "ramassage", "pour venir", "à proximité",
    )

    /** Mots qui désignent le trajet payé, client à bord. */
    private val MOTS_TRAJET = listOf(
        "trajet", "voyage", "dépose", "depose", "destination", "jusqu'à",
        "jusqu'a", "trip", "à bord", "a bord", "course de", "durée de la course",
    )

    /** Fenêtre de contexte, en caractères, autour d'un nombre. */
    private const val FENETRE = 35

    /** Au-delà, l'attribution durée/distance est forcément fausse. */
    private const val VITESSE_ABSURDE = 130.0

    // --- API ---------------------------------------------------------------

    /**
     * Analyse le texte d'une notification (titre et corps concaténés).
     *
     * @param texte le contenu brut de la notification
     * @param plateforme nom lisible de l'app émettrice
     */
    fun analyser(texte: String, plateforme: String = ""): Course {
        val t = normaliser(texte)
        val remarques = mutableListOf<String>()

        val prix = montant(t, remarques)
        val durees = durees(t)
        val distances = distances(t)

        val (minApp, minTraj) = attribuer(t, durees, "durée", remarques)
        val (kmApp, kmTraj) = attribuer(t, distances, "distance", remarques)

        return coherence(
            Course(
                plateforme = plateforme,
                prix = prix,
                minutesApproche = minApp,
                kmApproche = kmApp,
                minutesTrajet = minTraj,
                kmTrajet = kmTraj,
                texteBrut = texte.trim(),
                remarques = remarques,
            )
        )
    }

    // --- Étapes ------------------------------------------------------------

    /**
     * Met le texte à plat : espaces insécables (les apps en mettent entre le
     * nombre et le symbole euro), minuscules, lignes réduites à des espaces.
     */
    internal fun normaliser(texte: String): String = texte
        .replace(' ', ' ')  // espace insécable
        .replace(' ', ' ')  // espace fine insécable
        .replace(' ', ' ')  // espace fine
        .replace('\n', ' ')
        .replace('\t', ' ')
        .replace("·", " · ")
        .lowercase()
        .replace(Regex(" {2,}"), " ")

    /**
     * Le montant de la course. Quand plusieurs sommes apparaissent (prix +
     * bonus, prix + pourboire estimé), on retient la plus grosse et on le
     * signale : additionner à l'aveugle ferait accepter des courses à perte.
     */
    private fun montant(t: String, remarques: MutableList<String>): Double? {
        val valeurs = RE_MONTANT.findAll(t)
            .mapNotNull { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).let(::nombre) }
            .toList()
        if (valeurs.isEmpty()) return null
        if (valeurs.size > 1) remarques += "plusieurs montants lus, le plus élevé retenu"
        return valeurs.max()
    }

    private fun durees(t: String): List<Jeton> {
        val jetons = mutableListOf<Jeton>()
        RE_MINUTES.findAll(t).forEach { m ->
            nombre(m.groupValues[1])?.let { jetons += Jeton(it, m.range.first, m.range.last) }
        }
        RE_HEURES.findAll(t).forEach { m ->
            val h = nombre(m.groupValues[1]) ?: return@forEach
            // Une durée de course dépasse rarement 5 h ; au-delà c'est une
            // heure de la journée ("18h30"), pas une durée.
            if (h > 5.0) return@forEach
            val min = nombre(m.groupValues[2]) ?: 0.0
            jetons += Jeton(h * 60.0 + min, m.range.first, m.range.last)
        }
        return jetons.sortedBy { it.debut }
    }

    private fun distances(t: String): List<Jeton> {
        val jetons = mutableListOf<Jeton>()
        RE_KM.findAll(t).forEach { m ->
            nombre(m.groupValues[1])?.let { jetons += Jeton(it, m.range.first, m.range.last) }
        }
        RE_METRES.findAll(t).forEach { m ->
            val v = nombre(m.groupValues[1]) ?: return@forEach
            // En dessous de 50 m ce n'est pas une distance de course ;
            // au-delà de 5 km la plateforme aurait écrit des kilomètres.
            if (v < 50.0 || v > 5000.0) return@forEach
            jetons += Jeton(v / 1000.0, m.range.first, m.range.last)
        }
        return jetons.sortedBy { it.debut }
    }

    /**
     * Range les nombres d'un même type en (approche, trajet).
     *
     * D'abord par les mots du contexte, puis, pour le reste, par l'ordre de
     * lecture : l'approche est toujours annoncée avant le trajet.
     */
    private fun attribuer(
        t: String,
        jetons: List<Jeton>,
        quoi: String,
        remarques: MutableList<String>,
    ): Pair<Double?, Double?> {
        if (jetons.isEmpty()) return null to null

        var approche: Double? = null
        var trajet: Double? = null
        val reste = mutableListOf<Jeton>()

        for (j in jetons) {
            when (classer(t, j)) {
                Role.APPROCHE -> if (approche == null) approche = j.valeur else reste += j
                Role.TRAJET -> if (trajet == null) trajet = j.valeur else reste += j
                Role.INCONNU -> reste += j
            }
        }

        if (approche == null && trajet == null) {
            when {
                reste.size >= 2 -> {
                    approche = reste.first().valeur
                    trajet = reste[1].valeur
                }
                reste.size == 1 -> {
                    // Un seul nombre et aucun indice : c'est le trajet payé qui
                    // est annoncé partout, l'approche est parfois omise.
                    trajet = reste.first().valeur
                    remarques += "approche ($quoi) non détectée — non comptée"
                }
            }
        } else if (approche == null && reste.isNotEmpty()) {
            approche = reste.first().valeur
        } else if (trajet == null && reste.isNotEmpty()) {
            trajet = reste.last().valeur
        }

        return approche to trajet
    }

    /** Rôle d'un nombre d'après les mots qui l'entourent. */
    private fun classer(t: String, j: Jeton): Role {
        val debut = (j.debut - FENETRE).coerceAtLeast(0)
        val fin = (j.fin + FENETRE).coerceAtMost(t.length)
        val fenetre = t.substring(debut, fin)
        val app = MOTS_APPROCHE.count { fenetre.contains(it) }
        val traj = MOTS_TRAJET.count { fenetre.contains(it) }
        return when {
            app > traj -> Role.APPROCHE
            traj > app -> Role.TRAJET
            else -> Role.INCONNU
        }
    }

    /**
     * Rattrapage des attributions absurdes.
     *
     * Certaines plateformes annoncent « 12,50 € · 3 min · 8 km » : les trois
     * nombres se suivent, mais les 3 min sont l'approche et les 8 km le
     * trajet. L'attribution naïve donne 8 km en 3 min, soit 160 km/h. Une
     * vitesse impossible est la signature de cette erreur-là.
     */
    private fun coherence(c: Course): Course {
        val kmh = vitesse(c.kmTrajet, c.minutesTrajet) ?: return c
        if (kmh <= VITESSE_ABSURDE) return c
        if (c.minutesApproche != null) return c
        return c.copy(
            minutesApproche = c.minutesTrajet,
            minutesTrajet = null,
            remarques = c.remarques + "durée relue comme temps d'approche (vitesse impossible)",
        )
    }

    // --- Outils ------------------------------------------------------------

    private data class Jeton(val valeur: Double, val debut: Int, val fin: Int)

    private enum class Role { APPROCHE, TRAJET, INCONNU }

    private fun nombre(s: String): Double? =
        if (s.isEmpty()) null else s.replace(',', '.').toDoubleOrNull()
}
