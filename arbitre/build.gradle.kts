// Les plugins Kotlin sont déclarés ici, sans être appliqués, pour n'être
// chargés qu'une seule fois dans la construction.
//
// Deux sous-projets qui les appliquent chacun de leur côté font avertir
// Gradle d'un chargement multiple du plugin Kotlin — avec un risque réel de
// rupture selon l'ordre de configuration. Les deux identifiants proviennent
// du même artefact et ne réclament aucun SDK Android : les déclarer ici reste
// donc sans effet sur la construction du seul moteur.
plugins {
    id("org.jetbrains.kotlin.jvm") apply false
    id("org.jetbrains.kotlin.android") apply false
}
