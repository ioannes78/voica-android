import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

// Production FunASR revision-2 runtime. CI independently verifies the immutable release asset
// before building, and final APK/AAB verification fingerprints the packaged JNI implementation.
val productionSherpaRuntimeVersion = "v1.13.8-p5-ctc-r2"
val productionSherpaRuntimeSha256 =
    "dc8772134c93fff4b5ee68d18f085d73b78a1891cf6b7056d8d8a893dfa4370e"

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

    api(
        "com.github.k2-fsa.sherpa-onnx:sherpa-onnx:" +
            "$productionSherpaRuntimeVersion@aar",
    )

    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}
