import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

android {
    namespace = "io.github.ioannes78.voica.sherpa"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += "arm64-v8a"
        }
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
    implementation(project(":core:audio"))
    implementation(project(":core:model"))
    implementation(project(":core:transcript"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    api("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")

    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}
