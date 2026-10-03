package fr.ningbus.arbitre.moteur

/**
 * Répare les nombres qu'une reconnaissance de texte a abîmés.
 *
 * Une course de Grigny l'a imposé. La carte annonçait « 5 min (à 1.6 km) » ;
 * la bulle a affiché **6,0 km** d'approche au lieu de 1,6 — quatre fois trop,
 * avec toute la cascade derrière : une vitesse d'approche de 72 km/h, un
 * facteur de trafic au plafond, et une durée de course estimée deux fois trop
 * courte.
 *
 * La capture brute du journal a ensuite tranché la cause, et ce n'était pas
 * celle qu'on croyait : la reconnaissance avait rendu `5 min (à l.6 km)`. Le
 * chiffre **1** était devenu la lettre **l**. Le motif de distance, qui ne
 * cherche que des chiffres, n'a donc vu que le `6` — d'où 6,0 km. Recoudre une
 * décimale coupée n'y pouvait rien : il n'y avait pas de coupure.
 *
 * D'où trois coutures et une correction de lettres, et rien de plus, parce que
 * ce sont les seules qu'on puisse faire sans rien inventer :
 *
 *  - **un séparateur décimal suivi d'une espace** — « 1. 6 » — n'existe dans
 *    aucune écriture légitime. C'est toujours une coupure ;
 *  - **deux groupes de chiffres séparés par une espace, juste devant une
 *    unité** — « 1 6 km » — où le second groupe est d'un seul chiffre. Un
 *    séparateur de milliers français groupe par trois, jamais par un : « 2 400
 *    km » ne peut donc pas être pris pour une décimale coupée ;
 *  - **une lettre au milieu d'un nombre, juste devant une unité** — « l.6 km »,
 *    « 1O,5 € », « 2l min ». Bornée à ce qui précède immédiatement km, € ou
 *    min, et au seul jeton qui porte déjà un chiffre : « rue O » ou « l » seul
 *    ne deviennent jamais un nombre.
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
    .let(::redresserLettres)

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

/**
 * Un jeton fait de chiffres et de sosies de chiffres, posé devant une unité.
 *
 * Le jeton doit contenir au moins un vrai chiffre : c'est ce qui distingue
 * « l.6 km » d'un « L km » qui ne veut rien dire, et interdit de fabriquer un
 * nombre à partir d'un mot.
 */
private val RE_JETON_DEVANT_UNITE =
    Regex("""([0-9lIO|!oS]{1,4}(?:[.,][0-9lIO|!oS]{1,3})?)(\s*)(km|kms|€|min|mn)\b""")

/** Ce qu'une reconnaissance de texte confond avec un chiffre, et avec lequel. */
private val SOSIES = mapOf(
    'l' to '1', 'I' to '1', '|' to '1', '!' to '1',
    'O' to '0', 'o' to '0',
    'S' to '5',
)

private fun redresserLettres(texte: String): String =
    RE_JETON_DEVANT_UNITE.replace(texte) { m ->
        val jeton = m.groupValues[1]
        if (jeton.none { it.isDigit() } || jeton.none { it in SOSIES }) {
            m.value
        } else {
            jeton.map { SOSIES[it] ?: it }.joinToString("") + m.groupValues[2] + m.groupValues[3]
        }
    }
