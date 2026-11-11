import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

// Signing: CI provides the key through repository secrets (see README). A local
// signing.properties also works. With neither, release falls back to the debug key.
val signing = Properties().apply {
    val f = file("signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
    System.getenv("RELAY_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }?.let { setProperty("storeFile", it) }
    System.getenv("RELAY_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }?.let {
        setProperty("storePassword", it)
        setProperty("keyPassword", it)
    }
}
val hasReleaseKey = signing.getProperty("storeFile")?.let { file(it).exists() } == true &&
    !signing.getProperty("storePassword").isNullOrBlank()

android {
    namespace = "dev.relay.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.relay.tv"
        minSdk = 24
        targetSdk = 35
        versionCode = runNumber
        versionName = "1.0.$runNumber"

        // In-app updates read releases from the private repo with a read-only token
        // supplied by CI (secret RELAY_UPDATE_TOKEN). Empty token = updater disabled.
        val updateToken = (System.getenv("RELAY_UPDATE_TOKEN") ?: "").trim()
        buildConfigField("String", "UPDATE_TOKEN", "\"$updateToken\"")
        buildConfigField("String", "UPDATE_REPO", "\"ghoshs89/TV-Relay\"")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias", "relay")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Shrinks library code (Compose, Media3, OkHttp) for a smaller, faster app. Relay's own
            // classes are kept whole and nothing is renamed: see proguard-rules.pro. CI starts the
            // shrunk APK on an emulator before publishing to catch anything removed by mistake.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // CI runs lint as its own step and fails only on the crash-class issues listed in
        // .github/lint-gate.py; the full report is kept with each build.
        checkReleaseBuilds = false
        // Checks left off on purpose:
        //  GradleDependency, OldTargetApi: library and target versions are raised deliberately, after
        //    testing on the TVs, not because a newer one exists.
        //  SetTextI18n: Relay is English only and builds some views in code.
        //  ConstantLocale: date formats are created once for list speed; they follow the language
        //    the app started with.
        disable += setOf("GradleDependency", "OldTargetApi", "SetTextI18n", "ConstantLocale")
        abortOnError = false
        xmlReport = true
        xmlOutput = file("build/reports/lint/lint.xml")
        htmlReport = true
        htmlOutput = file("build/reports/lint/lint.html")
    }
    testOptions {
        // Plain JVM tests: Android framework calls (logging) return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3") // QR code for phone sign-in
    // Installs the startup profiles shipped with Compose and Relay's own, so sideloaded builds
    // start and scroll faster on TVs (the Play Store would otherwise do this).
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // Continue watching on the Google TV / Android TV home screen.
    implementation("androidx.tvprovider:tvprovider:1.0.0")

    val media3 = "1.6.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    // FFmpeg audio decoders (AC3, E-AC3, DTS, TrueHD) for devices without hardware support.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.6.1+1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
