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

    /**
     * Ce qui, autour d'un nombre, dit qu'il ne décrit pas la course.
     *
     * Le journal d'une vraie journée l'a imposé. La carte d'une course
     * planifiée Bolt porte, sous le trajet, « Temps d'attente supplémentaire —
     * 5 min gratuites incluses dans le tarif, 0,78 €/min après 5 min ». Ces
     * « 5 min » sont un barème d'attente ; l'analyseur les lisait comme la
     * durée d'approche, et la confiance montait à 100 % **parce que** le champ
     * était rempli. Une donnée fausse et complète vaut moins qu'une donnée
     * manquante : celle-ci se voit, l'autre non.
     *
     * Même raison pour les montants : un péage, un pourboire, un bonus de
     * prise en charge ou un tarif à la minute sont des euros qui ne sont pas
     * le prix de la course.
     */
    private val MOTS_PARASITES = listOf(
        "gratuit", "inclus", "attente", "supplément", "supplement",
        "annulation", "péage", "peage", "pourboire", "bonus", "prime",
        "promo", "après", "apres",
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
        val doutes = mutableListOf<Doute>()

        val prix = montant(t, remarques, doutes)
        val durees = durees(t)
            .filterNot { parasite(t, it.second, it.third) }
            .map { Nombre(it.first, it.second, it.third, classer(t, it.second, it.third)) }
            .let { dedoublonner(it, "durée", remarques, doutes) }
        val distances = distances(t)
            .filterNot { parasite(t, it.second, it.third) }
            .map { Nombre(it.first, it.second, it.third, classer(t, it.second, it.third)) }
            .let { dedoublonner(it, "distance", remarques, doutes) }
            .let { ecarterIntruses(t, durees, it, remarques, doutes) }

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
                doutes = doutes.distinct(),
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
    private fun montant(
        t: String,
        remarques: MutableList<String>,
        doutes: MutableList<Doute>,
    ): Double? {
        val valeurs = montants(t)
        if (valeurs.isEmpty()) return null
        if (valeurs.size > 1) {
            remarques += "plusieurs montants lus, le plus élevé retenu"
            doutes += Doute.PLUSIEURS_MONTANTS
        }
        return valeurs.max()
    }

    /**
     * Les sommes d'un texte déjà normalisé, péages et bonus ôtés.
     *
     * Exposée pour la lecture des écrans de bilan, qui ont besoin du même
     * filtrage — un pourboire et un péage n'y sont pas le prix de la course
     * non plus — sans repasser par l'analyse complète d'une offre.
     */
    fun montantsLisibles(texteNormalise: String): List<Double> = montants(texteNormalise)

    /** Les sommes qui peuvent être le prix d'une course, péages et bonus ôtés. */
    private fun montants(t: String): List<Double> = RE_MONTANT.findAll(t)
        .filterNot { parasite(t, it.range.first, it.range.last) }
        .mapNotNull { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).let(::nombre) }
        .toList()

    /**
     * Combien d'offres distinctes ce texte semble porter.
     *
     * Un écran de liste — « Demandes de courses planifiées » chez Bolt — empile
     * plusieurs courses, chacune avec son prix et sa distance. L'analyseur, qui
     * ne voit qu'un texte, retenait alors le prix de l'une et la distance de
     * l'autre : le journal du 21/09 montre quatorze verdicts bâtis sur des
     * chiffres appartenant à deux courses différentes, et rendus avec 83 % de
     * confiance.
     *
     * Le décompte est structurel et n'a besoin d'aucun libellé de plateforme :
     * une carte d'offre annonce **un** prix. Deux sommes qui ne sont ni un
     * péage, ni un bonus, ni un tarif à la minute, ce sont deux courses.
     */
    fun compterOffres(texte: String): Int = montants(normaliser(texte)).size

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

    /**
     * Ce nombre décrit-il autre chose que la course ?
     *
     * La question se règle sur le **mot immédiatement voisin**, et non sur une
     * fenêtre de contexte. « 46,7 € • 11,7 € péage » : le mot qui suit la
     * seconde somme est « péage », celui qui suit la première est un chiffre —
     * une fenêtre large aurait condamné les deux. « 13.6km • 21 min » suivi,
     * à la ligne, de « Temps d'attente supplémentaire » : le mot qui suit est
     * « temps », et la durée de la course est sauve.
     *
     * Une barre oblique collée au montant suffit à elle seule : « 0,78 €/min »
     * est un tarif, jamais un prix de course.
     */
    private fun parasite(t: String, debut: Int, fin: Int): Boolean {
        if (fin + 1 < t.length && t[fin + 1] == '/') return true
        val apres = motApres(t, fin)
        val avant = motAvant(t, debut)
        return MOTS_PARASITES.any { mot ->
            apres?.startsWith(mot) == true || avant?.startsWith(mot) == true
        }
    }

    /**
     * Le premier mot après le nombre, s'il n'y a pas d'autre nombre avant lui.
     *
     * Un chiffre rencontré en chemin arrête la recherche : ce qui suit ne
     * qualifie plus notre nombre, mais le sien.
     */
    private fun motApres(t: String, fin: Int): String? {
        var i = fin + 1
        while (i < t.length && !t[i].isLetter()) {
            if (t[i].isDigit()) return null
            i++
        }
        val debutMot = i
        while (i < t.length && t[i].isLetter()) i++
        return if (i > debutMot) t.substring(debutMot, i) else null
    }

    /** Le dernier mot avant le nombre, même règle en sens inverse. */
    private fun motAvant(t: String, debut: Int): String? {
        var i = debut - 1
        while (i >= 0 && !t[i].isLetter()) {
            if (t[i].isDigit()) return null
            i--
        }
        val finMot = i
        while (i >= 0 && t[i].isLetter()) i--
        return if (finMot > i) t.substring(i + 1, finMot + 1) else null
    }

    /**
     * Un même nombre écrit deux fois reste un seul nombre.
     *
     * La carte d'une course planifiée Bolt affiche « 13.6km », puis plus bas
     * « 13.6km • 21 min ». L'analyseur y voyait deux distances, et comme
     * toutes les plateformes annoncent l'approche avant le trajet, il faisait
     * de la première une approche de 13,6 km — treize kilomètres à vide
     * purement inventés, sur une carte qui n'annonce aucune approche. Le
     * verdict qui en sortait était faux et se présentait avec 100 % de
     * confiance, puisque tous les champs étaient remplis.
     *
     * On garde la dernière occurrence : c'est celle qui porte le plus souvent
     * la durée collée à elle, dont le rôle se transmettra ensuite.
     */
    private fun dedoublonner(
        nombres: List<Nombre>,
        quoi: String,
        remarques: MutableList<String>,
        doutes: MutableList<Doute>,
    ): List<Nombre> {
        if (nombres.size < 2) return nombres
        val garde = nombres.filterIndexed { i, n ->
            n.role != Role.INCONNU ||
                nombres.drop(i + 1).none { it.role == Role.INCONNU && it.valeur == n.valeur }
        }
        if (garde.size < nombres.size) {
            remarques += "$quoi répétée sur l'écran, comptée une seule fois"
            doutes += Doute.REPETITION
        }
        return garde
    }

    /**
     * Écarte les distances qui n'appartiennent pas à la carte d'offre.
     *
     * Une carte Bolt relevée à Antony l'a imposé. Elle annonce deux étapes,
     * chacune sous forme de **couple collé** : « 3 min • 1,7 km » pour
     * l'approche, « 12 min • 8,6 km » pour le trajet vers Orly. Le service
     * lit toutes les fenêtres visibles, et le bandeau du GPS posé par-dessus
     * portait sa propre distance — celle du prochain embranchement, qui
     * décompte pendant qu'on roule. Trois distances pour deux étapes : les
     * 8,6 km du trajet ont été chassés, la course est devenue 1,7 km,
     * l'approche 3,8 km, et le verdict est passé de LAISSE à LIMITE.
     *
     * La règle qui en sort est structurelle plutôt que cosmétique : sur une
     * carte d'offre, **une étape s'annonce en couple durée-distance**. Une
     * distance solitaire, sans durée collée à elle, ne décrit pas une étape —
     * c'est un panneau, un bandeau, une autre application.
     *
     * Deux garde-fous l'empêchent de nuire ailleurs. Elle ne se déclenche
     * qu'à partir de trois distances, donc jamais sur une carte normale. Et
     * elle exige au moins deux couples complets avant d'écarter quoi que ce
     * soit : les formats qui annoncent une distance seule — « Course de
     * 12,1 km », sans durée — n'en forment aucun et restent intacts.
     */
    private fun ecarterIntruses(
        t: String,
        durees: List<Nombre>,
        distances: List<Nombre>,
        remarques: MutableList<String>,
        doutes: MutableList<Doute>,
    ): List<Nombre> {
        if (distances.size < 3) return distances
        val enCouple = distances.filter { d ->
            durees.any { ecart(d, it) <= ADJACENCE && collees(t, d, it) }
        }
        if (enCouple.size < 2 || enCouple.size == distances.size) return distances
        remarques += "${distances.size - enCouple.size} distance hors carte écartée"
        doutes += Doute.INTRUS
        return enCouple
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
            doutes = c.doutes + Doute.ROLES_RELUS,
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
