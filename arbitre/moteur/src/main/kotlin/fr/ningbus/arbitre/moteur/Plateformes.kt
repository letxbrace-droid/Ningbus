package fr.ningbus.arbitre.moteur

/**
 * Les applications chauffeur écoutées par défaut.
 *
 * Les noms de paquets changent avec les versions et les pays : cette liste
 * n'est qu'un point de départ, l'écran de réglages laisse cocher n'importe
 * quelle application installée sur le téléphone.
 */
object Plateformes {

    val PAR_DEFAUT: Map<String, String> = mapOf(
        "com.ubercab.driver" to "Uber",
        "com.ubercab.carbon" to "Uber",
        "ee.mtakso.driver" to "Bolt",
        "com.heetch.driver" to "Heetch",
        "com.heetch" to "Heetch",
        "taxi.android.client" to "FreeNow",
        "com.mytaxi.driverapp" to "FreeNow",
        "com.marcel.driver" to "Marcel",
        "com.caocao.driver" to "Caocao",
    )

    /** Nom lisible d'une app, ou le dernier segment du paquet à défaut. */
    fun nom(paquet: String): String =
        PAR_DEFAUT[paquet] ?: paquet.substringAfterLast('.').replaceFirstChar { it.uppercase() }
}
