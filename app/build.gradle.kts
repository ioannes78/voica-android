import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.ioannes78.voica"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "io.github.ioannes78.voica"
        minSdk = 26
        targetSdk = 37
        versionCode = 59
        versionName = "0.13.1-stage13b-ai-summary-qa1"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("qa") {
            // Public, test-only QA signing identity. Never use this key for production.
            storeFile = rootProject.file("ci/voica-qa.jks")
            storePassword = "voica-qa-test"
            keyAlias = "voica-qa"
            keyPassword = "voica-qa-test"
        }
    }

    buildTypes {
        create("qa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            signingConfig = signingConfigs.getByName("qa")
            matchingFallbacks += listOf("debug")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:protocol"))
    implementation(project(":core:ble"))
    implementation(project(":core:database"))
    implementation(project(":core:ai"))
    implementation(project(":core:audio"))
    implementation(project(":core:model"))
    implementation(project(":core:transcript"))
    implementation(project(":engine:opus"))
    implementation(project(":engine:media"))
    implementation(project(":engine:playback"))
    implementation(project(":engine:sherpa"))
    implementation(project(":engine:llm"))
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.room:room-runtime:2.8.5")
    testImplementation("androidx.room:room-ktx:2.8.5")
    testImplementation("org.robolectric:robolectric:4.17")
}