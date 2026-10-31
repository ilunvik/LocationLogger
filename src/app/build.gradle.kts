plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "no.grokbot.stedslogger"
    compileSdk = 35
    defaultConfig {
        applicationId = "no.grokbot.stedslogger"
        minSdk = 26
        targetSdk = 34
        versionCode = 34
        versionName = "1.32"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}
dependencies {
    // Shizuku API: only used to ping the Shizuku binder (is Shizuku running?), see ShizukuWatch
    // Fused location (cached / Wi-Fi-cell / GPS tiers); falls back to LocationManager without Play services
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
}
