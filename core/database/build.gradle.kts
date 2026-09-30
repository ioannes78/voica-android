import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
}

android {
    namespace = "io.github.ioannes78.voica.database"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", file("schemas").absolutePath)
}

dependencies {
    val roomVersion = "2.8.5"

    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    ksp("androidx.room:room-compiler:$roomVersion")

    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}
