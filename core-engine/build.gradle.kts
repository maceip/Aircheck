plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.voicestudio.engine"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-O3")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                )
            }
        }
    }

    buildTypes {
        debug {
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        release {
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            val prebuiltLlamaDir = providers.gradleProperty("voicestudioAndroidLibsDir").orNull
                ?: System.getenv("VOICESTUDIO_ANDROID_LIBS_DIR")
                ?: "${System.getProperty("user.home")}/Downloads/voicestudio-models/android-libs"
            val dirFile = file(prebuiltLlamaDir)
            if (dirFile.isDirectory) {
                jniLibs.srcDir(dirFile)
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        prefab = true
    }
}

dependencies {
    api(project(":audio-edl"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // LiteRT 2.3.0 CompiledModel runtime + GPU accelerator for 44.1 kHz DAC vocoder
    api(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.google.oboe)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
