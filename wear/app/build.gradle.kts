plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val backendUrl = providers.gradleProperty("backendUrl").orElse("https://example.invalid").get().trim().trimEnd('/')
require(backendUrl.matches(Regex("https?://[a-zA-Z0-9.-]+(?::[0-9]+)?"))) {
    "backendUrl must be an HTTP(S) origin without a path, for example http://192.168.10.6:3000 (debug) or https://pulse.example.com (release)."
}

android {
    namespace = "dev.pulsestage.wear"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "dev.pulsestage.wear"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        check(backendUrl.startsWith("https://")) {
            "Release builds require HTTPS. For local HTTP testing, run ./gradlew assembleDebug -PbackendUrl=$backendUrl instead."
        }
        check(backendUrl != "https://example.invalid") { "Supply -PbackendUrl=https://your-production-domain before a release build." }
    }
}
