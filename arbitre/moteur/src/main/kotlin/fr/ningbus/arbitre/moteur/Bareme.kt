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
     * Carburant ou électricité, au kilomètre.
     *
     * Le seul des trois postes qu'un chauffeur connaisse au centime : c'est le
     * ticket de la station divisé par les kilomètres du plein.
     */
    val coutCarburant: Double = 0.13,

    /**
     * Usure et entretien, au kilomètre : pneus, freins, révisions, embrayage.
     *
     * Invisible au quotidien et parfaitement réel. Un jeu de pneus tous les
     * 40 000 km, c'est déjà deux centimes du kilomètre.
     */
    val coutUsure: Double = 0.05,

    /**
     * Coûts fixes ramenés au kilomètre : assurance, licence, amortissement.
     *
     * Ils tombent que la voiture roule ou non, et c'est pourquoi les ramener
     * au kilomètre est un choix et non une évidence : plus on roule, moins ils
     * pèsent par kilomètre. La valeur d'origine est volontairement basse — un
     * chauffeur qui veut les compter au prorata réel les relève lui-même.
     */
    val coutFixes: Double = 0.04,

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

    /**
     * Plancher d'euro par kilomètre roulé. **Zéro = désactivé.**
     *
     * Un veto, et rien d'autre : il peut refuser une course, jamais en
     * autoriser une. C'est ce qui permet de l'ajouter sans qu'aucun verdict
     * déjà rendu ne se desserre — d'où la valeur d'origine à zéro, qui laisse
     * l'objectif horaire décider seul tant que le chauffeur n'a pas choisi son
     * plancher.
     *
     * Il complète l'euro/heure plutôt qu'il ne le remplace, et les deux ne
     * disent pas la même chose. L'euro/heure dépend d'une durée, parfois
     * estimée ; l'euro/kilomètre est immédiat et ne suppose rien. Mais il
     * défavorise mécaniquement les courses longues, qui étalent leur temps
     * mort sur plus de kilomètres payés : sur un relevé réel, un plancher à
     * 1,70 € refusait quinze courses sur dix-neuf, dont la troisième du
     * classement — 32,50 € et 43 €/h client devant la porte.
     */
    val plancherEuroKm: Double = 0.0,

    /** Largeur de la zone « LIMITE » autour de l'objectif (0,15 = ±15 %). */
    val marge: Double = 0.15,

    /** Majorer les durées annoncées quand la circulation est déjà chargée. */
    val prudenceTrafic: Boolean = true,

    /**
     * Coefficient **mesuré** sur les courses déjà faites. Null = pas encore de
     * quoi le dire, et c'est alors [prudenceTrafic] qui décide.
     *
     * Quand il existe, il remplace le coefficient supposé au lieu de s'y
     * ajouter : les deux répondent à la même question — de combien la durée
     * annoncée se trompe-t-elle — et les empiler compterait deux fois la même
     * majoration. Une mesure vaut mieux qu'une supposition ; elle ne s'ajoute
     * pas à elle.
     *
     * Il ne porte que sur le trajet payé, jamais sur l'approche. C'est le seul
     * endroit où la comparaison est possible : les plateformes ne facturant
     * pas l'approche, elles ne l'archivent pas, et il n'existe donc aucune
     * durée réelle d'approche à confronter à l'annonce.
     */
    val facteurDureeMesure: Double? = null,

    /** Vitesse retenue pour estimer une donnée manquante, en km/h. */
    val vitesseParDefaut: Double = 22.0,
) {
    /**
     * Le coût de roulage total, au kilomètre.
     *
     * Décomposé en trois postes plutôt que réglé d'un seul chiffre, parce
     * qu'un chauffeur sait ce que lui coûte son carburant et n'a aucune idée
     * de ce que lui coûtent ses pneus. Voir le détail, c'est pouvoir corriger
     * la ligne qu'on connaît sans toucher aux deux autres — et découvrir, le
     * cas échéant, que l'entretien pèse autant que le gazole.
     */
    val coutKm: Double get() = coutCarburant + coutUsure + coutFixes
}
