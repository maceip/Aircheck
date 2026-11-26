import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.devtools.ksp)
  id("jacoco")
}

jacoco {
  toolVersion = "0.8.12"
}

android {
  namespace = "com.example"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.aistudio.aircheck.vqxkrm"
    minSdk = providers.gradleProperty("minSdkOverride").orNull?.toIntOrNull() ?: 35
    targetSdk = 37
    versionCode = 2
    versionName = "2.0.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Deepgram key is read at build time from ~/.config/aircheck/deepgram_api_key (never committed).
    val deepgramKeyFile = File(System.getProperty("user.home"), ".config/aircheck/deepgram_api_key")
    val deepgramKey = if (deepgramKeyFile.isFile) deepgramKeyFile.readText().trim() else ""
    buildConfigField("String", "DEEPGRAM_API_KEY", "\"$deepgramKey\"")
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
      enableUnitTestCoverage = true
      enableAndroidTestCoverage = true
      ndk {
        val singleAbi = findProperty("limrunAbiOnly")?.toString()?.trim()
        if (!singleAbi.isNullOrEmpty()) {
          abiFilters += listOf(singleAbi)
        } else {
          abiFilters += listOf("arm64-v8a", "x86_64")
        }
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
      all { testTask ->
        testTask.extensions.configure(JacocoTaskExtension::class.java) {
          isIncludeNoLocationClasses = true
          excludes = listOf("jdk.internal.*")
        }
      }
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

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)

  implementation(libs.androidx.navigationevent)
  implementation(libs.androidx.navigationevent.compose)

  // Stock Jetpack Agent-to-UI (A2UI Basic Catalog V1 + Compose Runtime)
  implementation(libs.androidx.a2ui.model)
  implementation(libs.androidx.a2ui.compose.runtime)
  implementation(libs.androidx.a2ui.compose.ui)
  implementation(libs.androidx.compose.material3.a2ui)

  // Android 17 Multi-Module Jetpack AppFunctions + Platform AppSearch + ML Kit GenAI Agent & Prompt
  implementation(libs.androidx.appfunctions)
  ksp(libs.androidx.appfunctions.compiler)
  implementation(libs.androidx.appsearch)
  implementation(libs.androidx.appsearch.local.storage)
  implementation(libs.androidx.appsearch.platform.storage)
  implementation(libs.mlkit.genai.agent)
  implementation(libs.mlkit.genai.prompt)
  implementation(libs.mlkit.genai.schema)
  ksp(libs.mlkit.genai.schema.compiler)

  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)
  implementation(libs.androidx.datastore)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.serialization.protobuf)

  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.ui)
  implementation(libs.androidx.media3.datasource.okhttp)
  implementation(libs.androidx.media3.database)
  implementation(libs.androidx.media3.transformer)
  implementation(libs.androidx.media3.effect)

  implementation(libs.coil3.compose)
  implementation(libs.coil3.network.okhttp)
  implementation(libs.okhttp)
  implementation(libs.okhttp.logging)

  implementation(libs.androidx.profileinstaller)

  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.android)

  testImplementation(platform(libs.androidx.compose.bom))
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.work.testing)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.a2ui.compose.ui.testing)

  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.a2ui.compose.ui.testing)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)

  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}

val jacocoExcludes = listOf(
  "**/R.class",
  "**/R$*.class",
  "**/BuildConfig.*",
  "**/Manifest*.*",
  "**/*Test*.*",
  "android/**/*.*",
  "**/*_Impl.class",
  "**/*_Impl$*.class",
  "**/*\$\$serializer.class",
  "**/*\$\$serializer$*.class",
  "**/ComposableSingletons$*.class"
)

tasks.register<JacocoReport>("jacocoFullCoverageReport") {
  group = "verification"
  description = "Generate merged JaCoCo XML & HTML coverage report from unit tests and connected Android tests."
  dependsOn("testDebugUnitTest")

  reports {
    xml.required.set(true)
    html.required.set(true)
    csv.required.set(false)
  }

  val kotlinClasses = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
    exclude(jacocoExcludes)
  }
  val asmClasses = fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) {
    exclude(jacocoExcludes)
  }
  val javaClasses = fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
    exclude(jacocoExcludes)
  }

  classDirectories.setFrom(files(kotlinClasses, asmClasses, javaClasses))
  sourceDirectories.setFrom(files("${project.projectDir}/src/main/java", "${project.projectDir}/src/main/kotlin"))
  executionData.setFrom(
    fileTree(layout.buildDirectory) {
      include(
        "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
        "jacoco/testDebugUnitTest.exec",
        "outputs/code_coverage/debugAndroidTest/connected/**/*.ec",
        "outputs/code_coverage/manual/*.ec"
      )
    }
  )
}
