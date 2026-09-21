pluginManagement {
    // Les versions vivent ici, pas dans chaque module : trois modules
    // appliquent les mêmes plugins, et des versions divergentes se
    // traduiraient par une erreur de classpath difficile à lire.
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
    }

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "arbitre"

// Le moteur de calcul est du Kotlin pur : il se compile et se teste partout.
include(":moteur")

// Le module Android, lui, ne peut même pas se configurer sans le SDK. On ne
// l'inclut que s'il est présent, pour que `gradle :moteur:test` tourne sur
// n'importe quelle machine (et en intégration continue avant l'étape Android).
val sdkAndroid = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").exists()

if (sdkAndroid) {
    include(":app")
    // Fausse application chauffeur du banc d'essai : elle doit être un
    // paquet distinct pour être lue comme n'importe quelle autre application.
    include(":simulateur")
} else {
    gradle.rootProject {
        logger.lifecycle("SDK Android introuvable — module :app ignoré, seul :moteur est construit.")
    }
}
