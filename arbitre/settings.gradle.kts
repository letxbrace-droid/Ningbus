pluginManagement {
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
} else {
    gradle.rootProject {
        logger.lifecycle("SDK Android introuvable — module :app ignoré, seul :moteur est construit.")
    }
}
