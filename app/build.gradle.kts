plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.devtools.ksp)
}

android {
  namespace = "com.example"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.aistudio.aircheck.vqxkrm"
    minSdk = 26
    targetSdk = 36
    versionCode = 2
    versionName = "2.0.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      val ksFile = file(keystorePath)
      if (ksFile.exists()) {
        storeFile = ksFile
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
      }
    }
    create("debugConfig") {
      val repoDebugKs = file("${rootDir}/debug.keystore")
      val homeDebugKs = file("${System.getProperty("user.home")}/.android/debug.keystore")
      val chosenKs = if (repoDebugKs.exists()) repoDebugKs else homeDebugKs
      if (chosenKs.exists()) {
        storeFile = chosenKs
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
  }

  buildTypes {
    release {
      ndk {
        abiFilters += "arm64-v8a"
      }
      isCrunchPngs = true
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      val releaseKs = signingConfigs.getByName("release").storeFile
      if (releaseKs != null && releaseKs.exists()) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
    debug {
      ndk {
        abiFilters += listOf("arm64-v8a", "x86_64")
      }
      val debugKs = signingConfigs.getByName("debugConfig").storeFile
      if (debugKs != null && debugKs.exists()) {
        signingConfig = signingConfigs.getByName("debugConfig")
      }
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  testOptions {
    unitTests {
      isIncludeAndroidResources = true
    }
  }

  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }

  packaging {
    jniLibs {
      useLegacyPackaging = false
    }
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

// Aircheck + VoiceStudio Local-First Modules (GGUF+LoRA, LiteRT DAC, 4-Gate ONNX, Oboe EDL, Hairline Clipper, Mac :8443 Bridge)
dependencies {
  implementation(project(":audio-edl"))
  implementation(project(":core-engine"))
  implementation(project(":core-verifier"))
  implementation(project(":ui-clipper"))
  implementation(project(":sync-trainer:android-worker"))

  implementation(platform(libs.androidx.compose.bom))

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)

  // Jetpack Navigation 3 + Predictive Back NavigationEvent
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigationevent)
  implementation(libs.androidx.navigationevent.compose)

  // Android 16/17 Jetpack AppFunctions (KSP-generated service + metadata)
  implementation(libs.androidx.appfunctions)
  ksp(libs.androidx.appfunctions.compiler)

  // Room WAL Storage + Proto DataStore (binary Protocol Buffers via kotlinx.serialization.protobuf)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)
  implementation(libs.androidx.datastore)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.serialization.protobuf)

  // Media3 Singleton ExoPlayer + SimpleCache + OkHttp progressive streaming
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.ui)
  implementation(libs.androidx.media3.datasource.okhttp)
  implementation(libs.androidx.media3.database)

  // Coil 3 Hardware Bitmaps + OkHttp networking
  implementation(libs.coil3.compose)
  implementation(libs.coil3.network.okhttp)
  implementation(libs.okhttp)
  implementation(libs.okhttp.logging)

  // Startup speed multiplier
  implementation(libs.androidx.profileinstaller)

  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.android)

  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)

  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
