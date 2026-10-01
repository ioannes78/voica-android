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
    api("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")

    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}
