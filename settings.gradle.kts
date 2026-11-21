pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Production Sherpa runtime for FunASR revision-2 native CTC. This repository is
        // intentionally first and artifact-only: all normal Debug/QA/Release builds resolve the
        // exact immutable AAR published after Stage 16B P5 real-device acceptance.
        ivy {
            name = "voicaProductionSherpaRuntime"
            url =
                uri(
                    "https://github.com/ioannes78/voica-android/releases/download/" +
                        "runtime-sherpa-funasr-ctc-r2",
                )
            patternLayout {
                artifact("[artifact]-[revision].[ext]")
            }
            metadataSources {
                artifact()
            }
            content {
                includeGroup("com.github.k2-fsa.sherpa-onnx")
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
include(":engine:local-llm")
include(":core:ai")
