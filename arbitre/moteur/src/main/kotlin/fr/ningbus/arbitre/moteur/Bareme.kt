package fr.ningbus.arbitre.moteur

/**
 * Les paramètres économiques du chauffeur. C'est ce qui transforme « 14,20 € »
 * en « rentable ou pas » : sans ces chiffres-là, un prix ne veut rien dire.
 *
 * Les valeurs par défaut sont un ordre de grandeur VTC français 2026, à
 * ajuster dans les réglages de l'application.
 */
data class Bareme(
    /** Objectif de revenu net par heure travaillée, charges de roulage déduites. */
    val objectifHeure: Double = 25.0,

    /**
     * Coût de roulage par kilomètre : carburant ou électricité, pneus,
     * entretien, amortissement du véhicule. ~0,22 €/km en thermique,
     * ~0,10 €/km en électrique rechargé à domicile.
     */
    val coutKm: Double = 0.22,

    /**
     * Part prélevée par la plateforme, si le montant de la notification est
     * le prix client et non ta part. Chez Uber la notification affiche déjà
     * ta part : laisser 0.
     */
    val commission: Double = 0.0,

    /** Temps mort systématique au point de prise en charge (le client descend). */
    val minutesAttente: Double = 2.0,

    /**
     * Fraction du trajet qu'il faudra refaire à vide pour se repositionner.
     * 0 si tu enchaînes toujours sur place, 1 si tu reviens systématiquement
     * à ton point de départ. Une dépose en grande banlieue coûte le retour.
     */
    val partRetour: Double = 0.35,

    /** Au-delà, l'approche mange trop la course : veto. */
    val approcheMaxMinutes: Double = 12.0,

    /** En dessous, l'usure mange la recette : veto. */
    val prixPlancher: Double = 6.0,

    /** Largeur de la zone « LIMITE » autour de l'objectif (0,15 = ±15 %). */
    val marge: Double = 0.15,

    /** Majorer les durées annoncées quand la circulation est déjà chargée. */
    val prudenceTrafic: Boolean = true,

    /** Vitesse retenue pour estimer une donnée manquante, en km/h. */
    val vitesseParDefaut: Double = 22.0,
)
