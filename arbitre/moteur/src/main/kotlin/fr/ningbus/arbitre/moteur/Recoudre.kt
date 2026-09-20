package fr.ningbus.arbitre.moteur

/**
 * Recoud les nombres qu'une reconnaissance de texte a coupés en deux.
 *
 * Une course de Grigny l'a imposé. La carte annonçait « 5 min (à 1.6 km) » ;
 * la reconnaissance a rendu la décimale en deux morceaux, et l'analyseur a
 * retenu **6,0 km** d'approche au lieu de 1,6 — quatre fois trop, avec toute
 * la cascade derrière : une vitesse d'approche de 72 km/h, un facteur de
 * trafic au plafond, et une durée de course estimée deux fois trop courte.
 *
 * Deux coutures, et deux seulement, parce qu'elles sont les seules qu'on
 * puisse faire sans rien inventer :
 *
 *  - **un séparateur décimal suivi d'une espace** — « 1. 6 » — n'existe dans
 *    aucune écriture légitime. C'est toujours une coupure ;
 *  - **deux groupes de chiffres séparés par une espace, juste devant une
 *    unité** — « 1 6 km » — où le second groupe est d'un seul chiffre. Un
 *    séparateur de milliers français groupe par trois, jamais par un : « 2 400
 *    km » ne peut donc pas être pris pour une décimale coupée.
 *
 * Ce travail ne s'applique **qu'au texte reconnu sur une image**. Celui de
 * l'arbre d'accessibilité est exact par construction : le recoudre reviendrait
 * à corriger ce qui n'est pas cassé, et à prendre le risque d'abîmer un texte
 * juste.
 */
fun recoudreNombres(texte: String): String = texte
    .replace(RE_DECIMALE_COUPEE, "$1.$2")
    .replace(RE_SEPARATEUR_ORPHELIN, "$1.$2")
    .replace(RE_GROUPES_DEVANT_UNITE, "$1.$2 ")

/** « 1. 6 » ou « 1, 6 » : le séparateur est resté collé au premier morceau. */
private val RE_DECIMALE_COUPEE = Regex("""(\d)[.,][ \t]+(\d)""")

/** « 1 .6 » : le séparateur est parti avec le second. */
private val RE_SEPARATEUR_ORPHELIN = Regex("""(\d)[ \t]+[.,](\d)""")

/**
 * « 1 6 km » : plus de séparateur du tout.
 *
 * Borné à ce qui précède immédiatement une unité de distance, et à un second
 * groupe d'un seul chiffre — au-delà, on ne recoud plus, on devine.
 */
private val RE_GROUPES_DEVANT_UNITE =
    Regex("""\b(\d{1,3})[ \t]+(\d)[ \t]*(?=(?:km|kms)\b)""")
