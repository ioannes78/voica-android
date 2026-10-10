import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildGitSha = providers.environmentVariable("GITHUB_SHA").getOrElse("unknown")

fun releaseSecret(environmentName: String, propertyName: String): String? =
    providers.environmentVariable(environmentName)
        .orElse(providers.gradleProperty(propertyName))
        .orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

val releaseStoreFile = releaseSecret("VOICA_RELEASE_STORE_FILE", "voica.release.storeFile")
val releaseStorePassword = releaseSecret("VOICA_RELEASE_STORE_PASSWORD", "voica.release.storePassword")
val releaseKeyAlias = releaseSecret("VOICA_RELEASE_KEY_ALIAS", "voica.release.keyAlias")
val releaseKeyPassword = releaseSecret("VOICA_RELEASE_KEY_PASSWORD", "voica.release.keyPassword")
val productionSigningValueCount =
    listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).count { it != null }
val productionSigningConfigured = productionSigningValueCount == 4

if (productionSigningValueCount in 1..3) {
    throw GradleException(
        "Production signing is partially configured. Provide all VOICA_RELEASE_* signing inputs or none of them.",
    )
}

val qaSigningStoreFile = rootProject.file("ci/voica-qa.jks").canonicalFile
val configuredProductionStoreFile =
    releaseStoreFile?.let { rootProject.file(it).canonicalFile }

if (configuredProductionStoreFile == qaSigningStoreFile) {
    throw GradleException("The public QA keystore must never be used for a production release.")
}
if (releaseKeyAlias == "voica-qa") {
    throw GradleException("The public QA key alias must never be used for a production release.")
}

android {
    namespace = "io.github.ioannes78.voica"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "io.github.ioannes78.voica"
        minSdk = 26
        targetSdk = 37
        versionCode = 87
        versionName = "1.0.0-rc3-r2"
        buildConfigField("String", "GIT_SHA", "\"$buildGitSha\"")
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

    val productionSigningConfig =
        if (productionSigningConfigured) {
            signingConfigs.create("production") {
                storeFile = configuredProductionStoreFile
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        } else {
            null
        }

    signingConfigs {
        create("qa") {
            // Public, test-only QA signing identity. Never use this key for production.
            storeFile = qaSigningStoreFile
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
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            productionSigningConfig?.let { signingConfig = it }
        }
        create("releaseQa") {
            // Production-like, minified/shrunk candidate signed only with the public QA identity.
            // Same QA application ID allows an in-place upgrade from earlier Stage 14 QA builds.
            initWith(getByName("release"))
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-export-qa"
            signingConfig = signingConfigs.getByName("qa")
            matchingFallbacks += listOf("release")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // sherpa-onnx's Android artifact also ships standalone C/C++ API libraries.
            // Voica uses the Java/Kotlin JNI bridge only; libsherpa-onnx-jni.so does not
            // DT_NEEDED either standalone API library. Keep the pair excluded together.
            excludes += setOf(
                "**/libsherpa-onnx-c-api.so",
                "**/libsherpa-onnx-cxx-api.so",
            )
        }
    }
}

tasks.register("verifyProductionSigningConfiguration") {
    group = "verification"
    description = "Fails unless the complete non-QA production signing configuration is present."
    doLast {
        if (!productionSigningConfigured) {
            throw GradleException(
                "Production signing is not configured. Provide VOICA_RELEASE_STORE_FILE, " +
                    "VOICA_RELEASE_STORE_PASSWORD, VOICA_RELEASE_KEY_ALIAS, and " +
                    "VOICA_RELEASE_KEY_PASSWORD (or the matching Gradle properties).",
            )
        }
        val store = requireNotNull(configuredProductionStoreFile)
        if (!store.isFile) {
            throw GradleException("Configured production keystore file does not exist.")
        }
        if (store == qaSigningStoreFile || releaseKeyAlias == "voica-qa") {
            throw GradleException("QA signing identity cannot be used for production.")
        }
        logger.lifecycle("Production signing configuration is present and isolated from the QA signer.")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
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
