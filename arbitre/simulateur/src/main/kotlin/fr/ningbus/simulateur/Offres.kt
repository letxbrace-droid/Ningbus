package fr.ningbus.simulateur

/**
 * Les cartes d'offre à rejouer, relevées sur de vraies captures d'écran.
 *
 * Elles sont reproduites **ligne par ligne**, ordre compris : l'analyseur se
 * sert de la position des nombres quand aucun mot ne désigne l'approche, si
 * bien qu'un simulateur approximatif validerait un code qui échoue en vrai.
 */
data class Offre(
    val cle: String,
    val lignes: List<String>,
    /** Ce que l'arbitrage doit trouver, pour que l'essai puisse conclure. */
    val prixAttendu: Double,
    val kmApprocheAttendu: Double,
    val kmTrajetAttendu: Double,
)

object Offres {

    /** Capture du 20/09 : offre reçue aux Ulis, dépose à Limours. */
    val ULIS = Offre(
        cle = "ULIS",
        lignes = listOf(
            "UberX Priority",
            "12,51 €",
            "Paiement en espèces",
            "4,55",
            "Montant net de frais",
            "+2,43 € inclus pour la prise en charge",
            "9 min (à 2.5 km)",
            "Rue d'Argonne & Avenue de Champagne, 91940 Les Ulis, France",
            "Course de 12.6 km",
            "Rue du Saut-du-Loup, 91470 Limours, France",
            "Mise en relation",
        ),
        prixAttendu = 12.51,
        kmApprocheAttendu = 2.5,
        kmTrajetAttendu = 12.6,
    )

    /** Capture du 19/09 : offre reçue à Briis-sous-Forges. */
    val BRIIS = Offre(
        cle = "BRIIS",
        lignes = listOf(
            "UberX Priority",
            "17,08 €",
            "4,80",
            "Montant net de frais",
            "+2,34 € inclus pour la prise en charge",
            "16 min (à 10.9 km)",
            "125 Rue Lieutenant André Lemoal, 91640 Briis-sous-Forges, France",
            "Course de 12.1 km",
            "121 Chem. du Vieux Pavé de Bruyères le Châtel, 91310 Saint-Germain-lès-Arpajon, France",
            "Mise en relation",
        ),
        prixAttendu = 17.08,
        kmApprocheAttendu = 10.9,
        kmTrajetAttendu = 12.1,
    )

    /**
     * Un écran de navigation : il porte un montant et des kilomètres, mais
     * n'est pas une offre. Il sert à vérifier qu'aucune bulle ne surgit en
     * pleine conduite.
     */
    val NAVIGATION = Offre(
        cle = "NAVIGATION",
        lignes = listOf(
            "En route vers la dépose",
            "12,51 €",
            "8.2 km restants",
            "Arrivée estimée 12:14",
            "Terminer la course",
        ),
        prixAttendu = 12.51,
        kmApprocheAttendu = 0.0,
        kmTrajetAttendu = 8.2,
    )

    val TOUTES = listOf(ULIS, BRIIS, NAVIGATION)

    fun parCle(cle: String?): Offre = TOUTES.firstOrNull { it.cle == cle } ?: ULIS
}
