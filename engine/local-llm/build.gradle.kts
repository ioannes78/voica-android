import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

val liteRtLmVersion = "0.18.1"

android {
    namespace = "io.github.ioannes78.voica.localllm"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        minSdk = 26
        buildConfigField("String", "LITERT_LM_VERSION", "\"$liteRtLmVersion\"")
    }

    buildFeatures {
        buildConfig = true
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

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:$liteRtLmVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}
