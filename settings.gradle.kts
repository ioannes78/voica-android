pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val patchedSherpaRepository =
    providers.gradleProperty("voica.patchedSherpaRepository")
        .orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let(::file)
        ?.canonicalFile

if (patchedSherpaRepository != null && !patchedSherpaRepository.isDirectory) {
    error("Configured patched sherpa Maven repository does not exist: $patchedSherpaRepository")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        patchedSherpaRepository?.let { repository ->
            maven {
                name = "voicaP5PatchedSherpa"
                url = uri(repository)
                content {
                    includeGroup("com.github.k2-fsa.sherpa-onnx")
                }
            }
        }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Voica"
include(":app")
include(":core:protocol")
include(":core:ble")
include(":core:database")
include(":core:audio")
include(":core:model")
include(":core:transcript")
include(":engine:opus")
include(":engine:media")
include(":engine:playback")
include(":engine:sherpa")
include(":engine:fsmn")
include(":core:ai")
include(":engine:llm")