plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Fausse application chauffeur, installée à côté d'Arbitre pour les essais.
//
// Elle doit porter son propre identifiant : la lecture d'écran ignore
// délibérément les fenêtres d'Arbitre lui-même, un simulateur logé dans la
// même application ne serait donc jamais lu — et le banc d'essai validerait
// un chemin que personne n'emprunte.
android {
    namespace = "fr.ningbus.simulateur"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.ningbus.simulateur"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
