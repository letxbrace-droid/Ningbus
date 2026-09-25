package fr.ningbus.arbitre.moteur

import java.util.Locale

/** Le verdict, en trois couleurs. */
enum class Decision(val libelle: String) {
    PRENDS("PRENDS"),
    LIMITE("LIMITE"),
    LAISSE("LAISSE"),
    INCOMPLET("INCOMPLET"),
}

/**
 * Le résultat de l'arbitrage. Tout est déjà calculé et mis en forme : la
 * couche Android n'a plus qu'à peindre, ce qui garde l'affichage instantané.
 */
data class Verdict(
    val decision: Decision,
    val course: Course,
    /** Le chiffre roi : revenu net par heure réellement mobilisée. */
    val euroHeure: Double? = null,
    /** Revenu net par kilomètre parcouru, approche et retour à vide compris. */
    val euroKm: Double? = null,

    /**
     * L'euro par kilomètre tel qu'un chauffeur le calcule : le prix annoncé
     * divisé par les kilomètres qu'il faut vraiment faire — approche comprise,
     * retour à vide exclu, puisque le retour n'appartient à aucune course en
     * particulier.
     *
     * Distinct de [euroKm], qui est net de tous les coûts et porte aussi le
     * retour. Sur la même course, l'un dit 2,06 et l'autre 0,36 : les deux
     * sont justes, et les confondre ferait régler un plancher sur un chiffre
     * pour le comparer à l'autre.
     */
    val euroKmRoule: Double? = null,

    // --- Le coût de roulage, poste par poste --------------------------------
    //
    // Un total de 3,30 € se croit ou ne se croit pas. « Carburant 1,30,
    // usure 1,10, fixes 0,90 » se vérifie, et se corrige là où il est faux.
    val coutCarburant: Double? = null,
    val coutUsure: Double? = null,
    val coutFixes: Double? = null,

    /** Ce qui reste en poche, coût de roulage déduit. */
    val revenuNet: Double? = null,
    val minutesTotal: Double? = null,
    val kmTotal: Double? = null,
    /** Part du temps total qui n'est pas payée (approche, attente, retour). */
    val partMorte: Double? = null,
    val trafic: Trafic = Trafic.INCONNU,
    val vitesseTrajet: Double? = null,
    /** euroHeure rapporté à l'objectif : 1,0 = pile l'objectif. */
    val ratio: Double? = null,

    /**
     * Ce que la course rapporterait s'il fallait rentrer à vide sur toute la
     * distance, au lieu de la fraction habituelle.
     *
     * Le barème suppose un repositionnement moyen — 35 % du trajet par défaut.
     * C'est juste sur une journée entière et faux sur une course en
     * particulier : une dépose au cœur d'une zone qui ne redemande rien coûte
     * le retour **complet**, et c'est très exactement la course que le chiffre
     * moyen fait accepter à tort. Une dépose à 47 km qui paie 38 €/h en
     * moyenne n'en paie plus que 22 si personne ne rappelle là-bas.
     *
     * Le moteur ne sait pas quelles zones sont mortes — aucune donnée de
     * demande ne lui parvient. Il ne décide donc rien avec ce chiffre : il le
     * pose à côté du verdict quand il le contredit, et laisse le chauffeur,
     * qui connaît son secteur, trancher.
     */
    val euroHeureRetourPlein: Double? = null,
    val alertes: List<String> = emptyList(),
    /** Une ligne, lisible d'un coup d'œil au volant. */
    val resume: String = "",
    /**
     * Ce que vaut le verdict, séparément de ce qu'il dit.
     *
     * Deux questions se confondaient en une : « cette course est-elle
     * rentable ? » et « ai-je assez lu pour le dire ? ». Un LAISSE sur
     * données complètes et un LAISSE calculé sur une approche inventée se
     * ressemblent à l'écran et ne se valent pas.
     */
    val confiance: Confiance = Confiance(0.0, emptyList()),
    /** Km parcourus sans être payés : approche et repositionnement. */
    val kmAVide: Double? = null,
    /** Minutes mobilisées sans être payées. */
    val minutesAVide: Double? = null,

    // --- Les trois kilométrages, séparés -----------------------------------
    //
    // Les additionner en un seul total fait disparaître ce qui explique le
    // verdict. « 28,1 km » ne dit rien ; « 16,4 de course, 1,6 d'approche,
    // 5,7 de retour à vide » dit tout, et dans cet ordre.
    /** Distance payée, lue ou reconstituée. */
    val kmCourse: Double? = null,
    /** Distance à vide avant la prise en charge. */
    val kmApproche: Double? = null,
    /** Distance à vide supposée après la dépose. */
    val kmRetour: Double? = null,

    /**
     * La contrainte qui pèse le plus sur ce verdict, en quelques mots.
     *
     * Purement destinée à l'affichage : elle ne change aucune décision, elle
     * nomme celle qui vient d'être prise. « LAISSE — 14 €/h » laisse le
     * chauffeur deviner ; « LAISSE — approche de 9,8 km avant la prise en
     * charge » lui dit s'il doit s'étonner ou non.
     */
    val motif: String? = null,
)

/**
 * Le moteur de décision.
 *
 * Il ne regarde pas le prix affiché mais ce que la course laisse par heure
 * mobilisée, une fois payés les kilomètres à vide : l'approche, le
 * repositionnement après la dépose, et le carburant du trajet lui-même.
 * C'est la seule mesure qui permette de comparer une course de 8 € à 2 min
 * et une course de 40 € à 25 min d'approche.
 */
object Arbitre {

    /** Au-delà, la part de temps non payé mérite d'être signalée. */
    private const val SEUIL_TEMPS_MORT = 0.45

    /**
     * Le prix au-delà duquel une offre courte n'est plus une offre.
     *
     * Aucune course urbaine ne dépasse ce montant, majorations comprises. Ce
     * n'est pas un réglage du chauffeur mais un garde-fou de lecture : il ne
     * juge pas la rentabilité, il constate qu'un chiffre ne peut pas être un
     * prix.
     */
    private const val PRIX_PLAFOND = 80.0

    /**
     * Et pour les courses longues, un plafond au kilomètre.
     *
     * Un Paris–Deauville à 250 € existe ; six euros du kilomètre payé, non.
     * Combiner les deux laisse passer toute course réelle, courte comme
     * longue, et n'arrête que ce qui n'est pas un prix.
     */
    private const val PRIX_PLAFOND_EURO_KM = 6.0

    /** En dessous, une somme ne peut pas être le prix d'une course non plus. */
    private const val PRIX_MINIMAL = 5.0

    fun arbitrer(course: Course, bareme: Bareme = Bareme()): Verdict {
        val alertes = mutableListOf<String>()
        alertes += course.remarques

        val prix = course.prix
        if (prix == null) {
            return Verdict(
                decision = Decision.INCOMPLET,
                course = course,
                alertes = alertes + "aucun montant lu dans l'offre",
                resume = "Montant illisible — décide à la main",
                confiance = Confiance.de(course),
            )
        }

        // --- Trajet payé : compléter la donnée manquante si besoin ----------
        //
        // Uber n'annonce que « Course de 12,1 km » : la durée du trajet
        // manque. On part de la vitesse à laquelle se parcourt normalement un
        // trajet de cette longueur, corrigée par ce que l'approche apprend du
        // trafic du moment.
        //
        // Extrapoler directement depuis la vitesse de l'approche serait une
        // faute : une approche de 2,5 km de rues à 17 km/h donnerait 45 min
        // pour 12,6 km de départementale, soit le double du vrai. Une course
        // longue emprunte des axes, une course courte reste aux carrefours.
        val vitesseApproche = vitesse(course.kmApproche, course.minutesApproche)
        val facteur = facteurTrafic(course.kmApproche, course.minutesApproche)

        var kmTrajet = course.kmTrajet
        var minutesTrajet = course.minutesTrajet
        if (kmTrajet == null && minutesTrajet != null) {
            val vitesse = (facteur ?: 1.0) * bareme.vitesseParDefaut
            kmTrajet = minutesTrajet / 60.0 * vitesse
            alertes += "distance estimée à ${fmt1(kmTrajet)} km"
        } else if (minutesTrajet == null && kmTrajet != null) {
            val vitesse = vitesseTypique(kmTrajet) * (facteur ?: 1.0)
            minutesTrajet = kmTrajet / vitesse * 60.0
            val origine = if (facteur != null) {
                "trafic mesuré sur l'approche"
            } else {
                "trafic supposé normal"
            }
            alertes += "durée estimée à ${fmt0(minutesTrajet)} min " +
                "(${fmt0(vitesse)} km/h, $origine)"
        }
        if (kmTrajet == null || minutesTrajet == null) {
            return Verdict(
                decision = Decision.INCOMPLET,
                course = course,
                alertes = alertes + "trajet illisible dans l'offre",
                resume = "Trajet illisible — décide à la main",
                confiance = Confiance.de(course),
            )
        }
        val estime = course.kmTrajet == null || course.minutesTrajet == null

        // --- Le prix est-il seulement un prix ? -----------------------------
        //
        // Cette question arrive ici, et non plus tard, parce qu'un montant
        // aberrant contamine tout ce qui vient après : il passe les vetos, il
        // pulvérise l'objectif horaire, et il ressort en PRENDS vert avec
        // 100 % de confiance — puisque tous les champs sont remplis. Le
        // terrain l'a montré sans appel : 4 768,00 € pour 47,7 km, soit
        // 90,47 €/km et 3 815 €/h, sous un feu vert.
        //
        // Le remède n'est pas de deviner le bon prix — on ne le connaît pas —
        // mais de refuser de trancher. Un verdict qui dit « je n'ai pas su
        // lire » vaut infiniment mieux qu'un verdict qui dit « prends ».
        val douteux = prixDouteux(prix, kmTrajet)
        val confiance = Confiance.de(
            course,
            kmTrajetEstime = course.kmTrajet == null,
            minutesTrajetEstime = course.minutesTrajet == null,
            prixDouteux = douteux != null,
        )
        if (douteux != null) {
            return Verdict(
                decision = Decision.INCOMPLET,
                course = course,
                alertes = alertes + "montant invraisemblable : $douteux",
                resume = "Prix invraisemblable — lis l'offre toi-même",
                confiance = confiance,
                kmCourse = kmTrajet,
                motif = "Montant lu : $douteux",
            )
        }

        // --- Trafic : déduit de la vitesse implicite ------------------------
        val vitesseTrajet = vitesse(kmTrajet, minutesTrajet)
        val traficTrajet = trafic(vitesseTrajet)
        val coefTrajet = if (bareme.prudenceTrafic) prudence(traficTrajet) else 1.0

        // --- Approche : à vide, donc entièrement à notre charge -------------
        val approcheInconnue = course.minutesApproche == null && course.kmApproche == null
        if (approcheInconnue) alertes += "approche inconnue — verdict optimiste"
        val minutesApproche = course.minutesApproche ?: 0.0
        val kmApproche = course.kmApproche
            ?: (minutesApproche / 60.0 * bareme.vitesseParDefaut)
        val traficApproche = trafic(vitesseApproche)
        val coefApproche = if (bareme.prudenceTrafic) {
            prudence(if (traficApproche == Trafic.INCONNU) traficTrajet else traficApproche)
        } else 1.0

        // --- Retour à vide : la dépose se paie au retour --------------------
        val kmRetour = kmTrajet * bareme.partRetour
        val vitesseRetour = vitesseTrajet ?: bareme.vitesseParDefaut
        val minutesRetour = if (vitesseRetour > 0) kmRetour / vitesseRetour * 60.0 else 0.0

        // --- Bilan ----------------------------------------------------------
        val minutesApprocheAj = minutesApproche * coefApproche
        val minutesTrajetAj = minutesTrajet * coefTrajet
        val minutesMortes = minutesApprocheAj + bareme.minutesAttente + minutesRetour
        val minutesTotal = minutesMortes + minutesTrajetAj
        val kmTotal = kmApproche + kmTrajet + kmRetour

        val recette = prix * (1.0 - bareme.commission)
        val cout = kmTotal * bareme.coutKm
        val revenuNet = recette - cout

        val euroHeure = if (minutesTotal > 0) revenuNet / (minutesTotal / 60.0) else null
        val euroKm = if (kmTotal > 0) revenuNet / kmTotal else null

        // L'euro par kilomètre tel qu'un chauffeur le calcule : le prix annoncé
        // divisé par les kilomètres qu'il faut vraiment faire pour cette
        // course — approche comprise, retour à vide exclu, puisque le retour
        // n'appartient à aucune course en particulier.
        //
        // Distinct de [euroKm] ci-dessus, qui est net de tous les coûts et
        // porte aussi le retour : sur la même course, l'un dit 2,06 et l'autre
        // 0,36. Les deux sont justes, et les confondre ferait régler un
        // plancher sur un chiffre et le comparer à l'autre.
        val kmRoules = kmApproche + kmTrajet
        val euroKmRoule = if (kmRoules > 0) recette / kmRoules else null
        val partMorte = if (minutesTotal > 0) minutesMortes / minutesTotal else null
        val ratio = euroHeure?.let { it / bareme.objectifHeure }

        // --- La même course, mais sans rien au retour -----------------------
        //
        // Le barème suppose un repositionnement moyen. Sur une dépose en zone
        // qui ne redemande rien, le retour se fait entier et à vide : on
        // refait le calcul avec cette hypothèse-là, sans toucher au verdict.
        // C'est le chauffeur qui sait si la zone est morte, pas le moteur.
        val kmTotalPlein = kmApproche + kmTrajet * 2.0
        val minutesRetourPlein = if (vitesseRetour > 0) kmTrajet / vitesseRetour * 60.0 else 0.0
        val minutesTotalPlein =
            minutesApprocheAj + bareme.minutesAttente + minutesTrajetAj + minutesRetourPlein
        val euroHeureRetourPlein = if (minutesTotalPlein > 0) {
            (recette - kmTotalPlein * bareme.coutKm) / (minutesTotalPlein / 60.0)
        } else null

        // --- Signaux --------------------------------------------------------
        if (traficTrajet == Trafic.BOUCHONS && bareme.prudenceTrafic) {
            alertes += "bouchons (${fmt0(vitesseTrajet)} km/h) — durée majorée de " +
                "${fmt0((coefTrajet - 1) * 100)} %"
        }
        if (course.kmApproche != null && course.kmApproche > kmTrajet) {
            alertes += "plus de vide que de charge (${fmt1(course.kmApproche)} km d'approche)"
        }
        if (partMorte != null && partMorte > SEUIL_TEMPS_MORT) {
            alertes += "${fmt0(partMorte * 100)} % du temps n'est pas payé"
        }
        if (bareme.partRetour > 0) {
            alertes += "retour à vide compté à ${fmt0(bareme.partRetour * 100)} %"
        }

        // --- Vetos : ils écrasent le calcul ---------------------------------
        val veto = when {
            prix < bareme.prixPlancher ->
                "sous ton plancher de ${fmt2(bareme.prixPlancher)} €"
            course.minutesApproche != null && course.minutesApproche > bareme.approcheMaxMinutes ->
                "approche de ${fmt0(course.minutesApproche)} min, au-dessus de ta limite"
            revenuNet <= 0 ->
                "le roulage coûte plus que la course ne rapporte"

            // Le plancher d'euro/kilomètre, s'il est armé. Il n'intervient
            // qu'ici, parmi les vetos : il refuse, il n'autorise jamais. Une
            // course qui le franchit doit encore convaincre l'objectif
            // horaire, exactement comme avant qu'il n'existe.
            bareme.plancherEuroKm > 0.0 && euroKmRoule != null &&
                euroKmRoule < bareme.plancherEuroKm ->
                "${fmt2(euroKmRoule)} €/km, sous ton plancher de " +
                    "${fmt2(bareme.plancherEuroKm)} €"

            else -> null
        }

        val decision = when {
            veto != null -> Decision.LAISSE
            ratio == null -> Decision.INCOMPLET
            ratio >= 1.0 + bareme.marge ->
                // Jamais de feu vert sur des données trouées : l'approche
                // manquante gonfle mécaniquement l'euro/heure. La confiance
                // ne fait que resserrer cette règle, jamais la desserrer —
                // elle s'ajoute aux deux conditions d'origine au lieu de les
                // remplacer, pour qu'aucun cas déjà couvert ne se mette à
                // passer au vert.
                if (approcheInconnue || estime || !confiance.fiable) {
                    Decision.LIMITE
                } else {
                    Decision.PRENDS
                }
            ratio >= 1.0 - bareme.marge -> Decision.LIMITE
            else -> Decision.LAISSE
        }

        val resume = when {
            veto != null -> veto.replaceFirstChar { it.uppercase() }
            euroHeure == null -> "Calcul impossible"
            decision == Decision.PRENDS ->
                "${fmt0(euroHeure)} €/h pour ${fmt0(bareme.objectifHeure)} visés"
            decision == Decision.LIMITE && approcheInconnue ->
                "${fmt0(euroHeure)} €/h mais approche non lue"
            decision == Decision.LIMITE ->
                "${fmt0(euroHeure)} €/h, juste autour de ton objectif"
            else ->
                "${fmt0(euroHeure)} €/h, sous les ${fmt0(bareme.objectifHeure)} visés"
        }

        return Verdict(
            decision = decision,
            course = course,
            euroHeure = euroHeure,
            euroKm = euroKm,
            euroKmRoule = euroKmRoule,
            coutCarburant = kmTotal * bareme.coutCarburant,
            coutUsure = kmTotal * bareme.coutUsure,
            coutFixes = kmTotal * bareme.coutFixes,
            revenuNet = revenuNet,
            minutesTotal = minutesTotal,
            kmTotal = kmTotal,
            partMorte = partMorte,
            trafic = traficTrajet,
            vitesseTrajet = vitesseTrajet,
            ratio = ratio,
            euroHeureRetourPlein = euroHeureRetourPlein,
            alertes = alertes.distinct(),
            resume = resume,
            confiance = confiance,
            kmAVide = kmApproche + kmRetour,
            minutesAVide = minutesMortes,
            kmCourse = kmTrajet,
            kmApproche = kmApproche,
            kmRetour = kmRetour,
            motif = motif(
                veto, course, kmTrajet, kmApproche, kmRetour, partMorte, confiance,
                euroHeure, bareme,
            ),
        )
    }

    /**
     * Ce montant peut-il être le prix de cette course ?
     *
     * Deux plafonds valent mieux qu'un : l'un absolu, pour les courses
     * courtes, l'autre au kilomètre, pour les longues. Une course de 3 km ne
     * paie pas 80 € ; une course de 200 km ne paie pas 6 € du kilomètre. Entre
     * les deux, tout ce qui existe passe.
     *
     * Quand le montant est refusé, on propose la lecture la plus probable
     * **sans jamais l'appliquer** : un séparateur décimal perdu multiplie le
     * prix par dix ou par cent, et c'est de très loin la panne la plus
     * fréquente — « 47,68 € » devenu « 4768 € ». Le dire aide à comprendre
     * l'écran ; le corriger d'office ferait décider le moteur sur une
     * hypothèse, ce qui est exactement ce qu'on lui reproche ici.
     *
     * @return la description du montant refusé, ou null s'il est plausible
     */
    internal fun prixDouteux(prix: Double, kmTrajet: Double): String? {
        val plafond = maxOf(PRIX_PLAFOND, kmTrajet * PRIX_PLAFOND_EURO_KM)
        if (prix <= plafond) return null
        // La plus petite correction d'abord : « 476,80 » est bien plus souvent
        // un « 47,68 » amputé d'une virgule qu'un « 4,77 » amputé de deux.
        // Et le candidat doit rester une course : en dessous de cinq euros,
        // aucune plateforme n'envoie quoi que ce soit.
        val probable = listOf(10.0, 100.0)
            .map { prix / it }
            .firstOrNull { it in PRIX_MINIMAL..plafond }
        return "${fmt2(prix)} € pour ${fmt1(kmTrajet)} km" +
            (probable?.let { " — sans doute ${fmt2(it)} €" } ?: "")
    }

    /**
     * Ce qui explique le verdict, en une ligne, du plus décisif au plus
     * accessoire.
     *
     * L'ordre n'est pas esthétique : un veto est la seule raison qui compte
     * quand il existe, et il ne sert à rien de parler de retour à vide à
     * quelqu'un dont la course est refusée pour le prix. En dessous, on
     * nomme la contrainte dominante — celle qui, si elle disparaissait,
     * changerait le plus le résultat.
     */
    private fun motif(
        veto: String?,
        course: Course,
        kmTrajet: Double,
        kmApproche: Double,
        kmRetour: Double,
        partMorte: Double?,
        confiance: Confiance,
        euroHeure: Double?,
        bareme: Bareme,
    ): String? = when {
        veto != null -> veto.replaceFirstChar { it.uppercase() }

        !confiance.fiable -> "Données incomplètes — vérifie l'offre toi-même"

        // Rouler plus à vide que chargé est le cas que le prix seul cache le
        // mieux : la course paraît correcte, et le kilométrage la mange.
        kmApproche > kmTrajet ->
            "Approche plus longue que la course — ${fmt1(kmApproche)} km à vide"

        kmApproche > 0 && kmApproche > kmTrajet * 0.5 ->
            "Approche de ${fmt1(kmApproche)} km avant la prise en charge"

        // Le même seuil que l'alerte plus haut, et non un second à 50 %.
        // Une carte relevée à Brétigny l'a imposé : 47 % de temps non payé
        // déclenchait l'alerte et ne produisait aucun motif, si bien que le
        // verdict le plus fréquent de tous — un LAISSE sur une course trop
        // courte — arrivait muet. Un LAISSE sans motif à 1,02 €/km est
        // exactement celui qu'on croit injuste.
        partMorte != null && partMorte > SEUIL_TEMPS_MORT ->
            "${fmt0(partMorte * 100)} % du temps mobilisé n'est pas payé"

        kmRetour > kmTrajet * 0.5 ->
            "Retour à vide important — ${fmt1(kmRetour)} km supposés"

        course.minutesTrajet == null -> "Durée de course estimée, non annoncée"

        // Le dernier recours, et il ne doit jamais manquer : quand aucune
        // contrainte particulière ne ressort, c'est que le prix ne couvre
        // simplement pas le temps. Le dire vaut mieux que se taire — un
        // verdict qui n'explique rien finit par ne plus être cru.
        euroHeure != null && euroHeure < bareme.objectifHeure ->
            "${fmt0(euroHeure)} €/h pour ${fmt0(bareme.objectifHeure)} visés — " +
                "le prix ne couvre pas le temps"

        else -> null
    }

    /** Raccourci : du texte brut au verdict, en un appel. */
    fun arbitrer(texte: String, plateforme: String, bareme: Bareme = Bareme()): Verdict =
        arbitrer(Analyseur.analyser(texte, plateforme), bareme)
}

// --- Mise en forme française (virgule décimale) ----------------------------

fun fmt0(v: Double?): String = if (v == null) "—" else String.format(Locale.FRANCE, "%.0f", v)
fun fmt1(v: Double?): String = if (v == null) "—" else String.format(Locale.FRANCE, "%.1f", v)
fun fmt2(v: Double?): String = if (v == null) "—" else String.format(Locale.FRANCE, "%.2f", v)
