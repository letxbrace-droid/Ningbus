plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.ningbus.arbitre"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.ningbus.arbitre"
        // TYPE_APPLICATION_OVERLAY, indispensable pour dessiner par-dessus
        // les autres applications, existe depuis Android 8.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Clé de débogage figée dans le dépôt, et non celle que chaque machine
    // engendre dans son coin. Sans cela, deux APK construits sur deux runners
    // différents portent deux signatures différentes : Android refuse alors
    // d'installer la nouvelle version par-dessus l'ancienne, et il faut
    // désinstaller — donc perdre ses réglages — à chaque mise à jour.
    // Ce sont les identifiants publics et conventionnels d'une clé de débogage
    // Android : ils ne protègent rien et n'ont rien d'un secret.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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
    implementation(project(":moteur"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Le banc d'essai vit dans :simulateur, et non ici : Arbitre y est une
    // application installée comme les autres, ce qu'il doit être pour que le
    // banc prouve quoi que ce soit.
}
