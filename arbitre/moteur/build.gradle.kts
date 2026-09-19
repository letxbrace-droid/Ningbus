plugins {
    kotlin("jvm") version "2.0.21"
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    compilerOptions {
        // Le moteur est partagé avec le module Android : on reste sur un
        // niveau de bytecode que tous les outils Android acceptent.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
