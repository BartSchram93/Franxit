import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: CI passes the keystore through env vars (empty when the repo has no key yet,
// then the release APK is built unsigned); locally it is read from
// ~/.android-keys/franxit-release.properties. The key itself is never in the repo.
// Updates must be signed with the same key, so back it up.
val releaseKey: Properties? = System.getenv("FRANXIT_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { path ->
    Properties().apply {
        setProperty("storeFile", path)
        setProperty("storePassword", System.getenv("FRANXIT_KEYSTORE_PASSWORD"))
        setProperty("keyAlias", System.getenv("FRANXIT_KEY_ALIAS") ?: "franxit")
        setProperty("keyPassword", System.getenv("FRANXIT_KEYSTORE_PASSWORD"))
    }
} ?: File(System.getProperty("user.home"), ".android-keys/franxit-release.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

// Each CI run gets a higher version so updaters see it as new.
val buildNumber = System.getenv("FRANXIT_BUILD")?.toIntOrNull() ?: 1

android {
    namespace = "com.bartschram.franxit"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // The app's identity on the phone: changing it would install a separate app without
        // the existing products and history.
        applicationId = "com.bartschram.franxit"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        // A mark the app sends to its search service (a Cloudflare Worker); from the CI secret APP_TOKEN, empty when unset.
        buildConfigField("String", "APP_TOKEN", "\"${System.getenv("APP_TOKEN").orEmpty().trim()}\"")
    }

    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = file(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
    }

    // Two builds of the same app: "web" is the APK for the download page (with Aptabase usage statistics),
    // "play" is the bundle for Google Play (no statistics, no Aptabase library). Each has its own Analytics.kt.
    flavorDimensions += "dist"
    productFlavors {
        create("web") { dimension = "dist" }
        create("play") { dimension = "dist" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.all {
            it.testLogging.showStandardStreams = true
            // Full failure messages, so the site check's issue says what broke.
            it.testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.work.runtime)
    "webImplementation"(libs.aptabase)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.mlkit.text.recognition)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
