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
include(":engine:playback")
include(":engine:sherpa")
include(":core:ai")
