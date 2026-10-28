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
        versionCode = 25
        versionName = "1.24"
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
    testImplementation("junit:junit:4.13.2")
}
