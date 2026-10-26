plugins {
    id("com.android.application")
}

android {
    compileSdk = 30

    defaultConfig {
        applicationId = "com.example.locationlogger"
        minSdk = 26
        targetSdk = 30
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.3.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.1")
    implementation("com.google.android.material:material:1.4.0")
    implementation("com.google.android.gms:play-services-location:18.0.0")
}
