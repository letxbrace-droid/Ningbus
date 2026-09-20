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
        val confiance = Confiance.de(
            course,
            kmTrajetEstime = course.kmTrajet == null,
            minutesTrajetEstime = course.minutesTrajet == null,
        )

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
        val partMorte = if (minutesTotal > 0) minutesMortes / minutesTotal else null
        val ratio = euroHeure?.let { it / bareme.objectifHeure }

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
            revenuNet = revenuNet,
            minutesTotal = minutesTotal,
            kmTotal = kmTotal,
            partMorte = partMorte,
            trafic = traficTrajet,
            vitesseTrajet = vitesseTrajet,
            ratio = ratio,
            alertes = alertes.distinct(),
            resume = resume,
            confiance = confiance,
            kmAVide = kmApproche + kmRetour,
            minutesAVide = minutesMortes,
        )
    }

    /** Raccourci : du texte brut au verdict, en un appel. */
    fun arbitrer(texte: String, plateforme: String, bareme: Bareme = Bareme()): Verdict =
        arbitrer(Analyseur.analyser(texte, plateforme), bareme)
}

// --- Mise en forme française (virgule décimale) ----------------------------

fun fmt0(v: Double?): String = if (v == null) "—" else String.format(Locale.FRANCE, "%.0f", v)
fun fmt1(v: Double?): String = if (v == null) "—" else String.format(Locale.FRANCE, "%.1f", v)
fun fmt2(v: Double?): String = if (v == null) "—" else String.format(Locale.FRANCE, "%.2f", v)
