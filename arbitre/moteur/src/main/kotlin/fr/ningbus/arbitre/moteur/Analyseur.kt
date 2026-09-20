package fr.ningbus.arbitre.moteur

/**
 * Extraction des chiffres d'une offre de course.
 *
 * La matière est du langage naturel — texte d'une notification ou contenu de
 * la carte d'offre lue à l'écran — différent d'une plateforme à l'autre et
 * d'une version d'app à l'autre. La méthode est donc volontairement tolérante :
 *
 *  1. on repère tous les montants, toutes les durées, toutes les distances,
 *     avec leur position dans le texte ;
 *  2. on classe chaque nombre en « approche » ou « trajet » selon les mots
 *     qui l'entourent ("de vous", "course de", "prise en charge"…) ;
 *  3. une durée et une distance collées décrivent la même étape — « 16 min
 *     (à 10,9 km) » — donc le rôle de l'une se transmet à l'autre ;
 *  4. ce qui reste non classé est attribué dans l'ordre de lecture, car
 *     toutes les plateformes annoncent l'approche avant le trajet ;
 *  5. on vérifie la cohérence du résultat (une vitesse implicite absurde
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

    /** Un vrai mot, par opposition à une préposition d'une ou deux lettres. */
    private val RE_MOT = Regex("""\p{L}{3,}""")

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

    /**
     * Écart maximal, en caractères, entre une durée et une distance pour
     * qu'elles décrivent la même étape. « 16 min (à 10,9 km) » : quatre
     * caractères les séparent.
     */
    private const val ADJACENCE = 12

    /** Au-delà, l'attribution durée/distance est forcément fausse. */
    private const val VITESSE_ABSURDE = 130.0

    // --- API ---------------------------------------------------------------

    /**
     * Analyse le texte d'une offre de course.
     *
     * @param texte contenu brut — notification concaténée, ou textes lus sur
     *   la carte d'offre dans l'ordre de lecture
     * @param plateforme nom lisible de l'app émettrice
     */
    fun analyser(texte: String, plateforme: String = ""): Course {
        val t = normaliser(texte)
        val remarques = mutableListOf<String>()

        val prix = montant(t, remarques)
        val durees = durees(t).map { Nombre(it.first, it.second, it.third, classer(t, it.second, it.third)) }
        val distances = distances(t).map { Nombre(it.first, it.second, it.third, classer(t, it.second, it.third)) }

        // Première passe : les rôles posés par les mots-clés se transmettent
        // entre voisines immédiates.
        propager(t, durees, distances)

        // Les distances se laissent mieux classer que les durées : « course de
        // 12,1 km » nomme son étape, « 16 min » non. On les attribue donc
        // d'abord, puis on laisse leurs rôles redescendre sur les durées.
        val (kmApp, kmTraj) = attribuer(distances, "distance", remarques)
        propager(t, durees, distances)
        val (minApp, minTraj) = attribuer(durees, "durée", remarques)

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
        .replace('\u00A0', ' ')  // espace insécable
        .replace('\u202F', ' ')  // espace fine insécable
        .replace('\u2009', ' ')  // espace fine
        .replace('\n', ' ')
        .replace('\t', ' ')
        .replace("·", " · ")
        .lowercase()
        .replace(Regex(" {2,}"), " ")

    /**
     * Le montant de la course. Quand plusieurs sommes apparaissent (prix +
     * bonus de prise en charge, prix + pourboire estimé), on retient la plus
     * grosse et on le signale : additionner à l'aveugle ferait accepter des
     * courses à perte, d'autant que les plateformes annoncent souvent le
     * bonus comme « inclus » dans le total.
     */
    private fun montant(t: String, remarques: MutableList<String>): Double? {
        val valeurs = RE_MONTANT.findAll(t)
            .mapNotNull { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).let(::nombre) }
            .toList()
        if (valeurs.isEmpty()) return null
        if (valeurs.size > 1) remarques += "plusieurs montants lus, le plus élevé retenu"
        return valeurs.max()
    }

    private fun durees(t: String): List<Triple<Double, Int, Int>> {
        val jetons = mutableListOf<Triple<Double, Int, Int>>()
        RE_MINUTES.findAll(t).forEach { m ->
            nombre(m.groupValues[1])?.let { jetons += Triple(it, m.range.first, m.range.last) }
        }
        RE_HEURES.findAll(t).forEach { m ->
            val h = nombre(m.groupValues[1]) ?: return@forEach
            // Une durée de course dépasse rarement 5 h ; au-delà c'est une
            // heure de la journée ("18h30"), pas une durée.
            if (h > 5.0) return@forEach
            val min = nombre(m.groupValues[2]) ?: 0.0
            jetons += Triple(h * 60.0 + min, m.range.first, m.range.last)
        }
        return jetons.sortedBy { it.second }
    }

    private fun distances(t: String): List<Triple<Double, Int, Int>> {
        val jetons = mutableListOf<Triple<Double, Int, Int>>()
        RE_KM.findAll(t).forEach { m ->
            nombre(m.groupValues[1])?.let { jetons += Triple(it, m.range.first, m.range.last) }
        }
        RE_METRES.findAll(t).forEach { m ->
            val v = nombre(m.groupValues[1]) ?: return@forEach
            // En dessous de 50 m ce n'est pas une distance de course ;
            // au-delà de 5 km la plateforme aurait écrit des kilomètres.
            if (v < 50.0 || v > 5000.0) return@forEach
            jetons += Triple(v / 1000.0, m.range.first, m.range.last)
        }
        return jetons.sortedBy { it.second }
    }

    /**
     * Transmet les rôles entre une durée et une distance qui se touchent.
     *
     * C'est ce qui fait tenir le format d'Uber : « 16 min (à 10,9 km) » ne
     * contient aucun mot qui désigne l'approche, mais « course de 12,1 km »
     * nomme le trajet. Une fois la distance d'approche identifiée par
     * élimination, la durée collée à elle en hérite.
     */
    private fun propager(t: String, durees: List<Nombre>, distances: List<Nombre>) {
        transmettre(t, durees, distances)
        transmettre(t, distances, durees)
    }

    private fun transmettre(t: String, vers: List<Nombre>, depuis: List<Nombre>) {
        val sources = depuis.filter { it.role != Role.INCONNU }
        if (sources.isEmpty()) return
        for (cible in vers) {
            if (cible.role != Role.INCONNU) continue
            val voisine = sources
                .filter { ecart(cible, it) <= ADJACENCE && collees(t, cible, it) }
                .minByOrNull { ecart(cible, it) } ?: continue
            cible.role = voisine.role
        }
    }

    /**
     * Deux nombres sont collés si rien d'autre que de la ponctuation les
     * sépare. Un mot entre eux change le sens : dans « (1,3 km) de vous
     * 22 min », les neuf caractères qui séparent la distance de la durée
     * disent précisément qu'elles décrivent deux étapes différentes.
     */
    private fun collees(t: String, a: Nombre, b: Nombre): Boolean {
        val premier = if (a.debut <= b.debut) a else b
        val second = if (a.debut <= b.debut) b else a
        val debut = (premier.fin + 1).coerceIn(0, t.length)
        val fin = second.debut.coerceIn(debut, t.length)
        return !RE_MOT.containsMatchIn(t.substring(debut, fin))
    }

    private fun ecart(a: Nombre, b: Nombre): Int = when {
        a.debut > b.fin -> a.debut - b.fin
        b.debut > a.fin -> b.debut - a.fin
        else -> 0
    }

    /**
     * Range les nombres d'un même type en (approche, trajet) : d'abord ceux
     * que les mots-clés ont désignés, puis le reste dans l'ordre de lecture.
     */
    private fun attribuer(
        nombres: List<Nombre>,
        quoi: String,
        remarques: MutableList<String>,
    ): Pair<Double?, Double?> {
        if (nombres.isEmpty()) return null to null

        var approche = nombres.firstOrNull { it.role == Role.APPROCHE }
        var trajet = nombres.firstOrNull { it.role == Role.TRAJET }
        val reste = nombres.filter { it.role == Role.INCONNU }

        if (approche == null && trajet == null) {
            when {
                reste.size >= 2 -> {
                    approche = reste[0]
                    trajet = reste[1]
                }
                reste.size == 1 -> {
                    // Un seul nombre et aucun indice : c'est le trajet payé qui
                    // est annoncé partout, l'approche est parfois omise.
                    trajet = reste[0]
                    remarques += "approche ($quoi) non détectée — non comptée"
                }
            }
        } else if (approche == null && reste.isNotEmpty()) {
            approche = reste.first()
        } else if (trajet == null && reste.isNotEmpty()) {
            trajet = reste.last()
        }

        approche?.role = Role.APPROCHE
        trajet?.role = Role.TRAJET
        return approche?.valeur to trajet?.valeur
    }

    /** Rôle d'un nombre d'après les mots qui l'entourent. */
    private fun classer(t: String, debutJeton: Int, finJeton: Int): Role {
        val debut = (debutJeton - FENETRE).coerceAtLeast(0)
        val fin = (finJeton + FENETRE).coerceAtMost(t.length)
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

    private class Nombre(
        val valeur: Double,
        val debut: Int,
        val fin: Int,
        var role: Role,
    )

    private enum class Role { APPROCHE, TRAJET, INCONNU }

    private fun nombre(s: String): Double? =
        if (s.isEmpty()) null else s.replace(',', '.').toDoubleOrNull()
}
