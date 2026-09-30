import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

android {
    namespace = "io.github.ioannes78.voica.opus"
    compileSdk = 37
    compileSdkMinor = 1
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DOPUS_BUILD_PROGRAMS=OFF",
                    "-DOPUS_BUILD_TESTING=OFF",
                    "-DOPUS_INSTALL_PKG_CONFIG_MODULE=OFF",
                    "-DOPUS_INSTALL_CMAKE_CONFIG_MODULE=OFF",
                    "-DOPUS_DRED=OFF",
                    "-DOPUS_OSCE=OFF",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.5"
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
}
