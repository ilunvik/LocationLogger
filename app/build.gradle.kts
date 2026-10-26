plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.locationlogger"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.example.locationlogger"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.5.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.7.0")
    implementation("com.google.android.gms:play-services-location:21.0.1")
}
