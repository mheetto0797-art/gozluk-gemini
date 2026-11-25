plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.metin.gozlukgemini"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.metin.gozlukgemini"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // Geliştirici Modu için 0
        manifestPlaceholders["mwdat_application_id"] = "0"
        manifestPlaceholders["mwdat_client_token"] = "0"
    }

    signingConfigs {
        getByName("debug")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val mwdat = "1.0.1"
    implementation("com.meta.wearable:mwdat-core:$mwdat")
    implementation("com.meta.wearable:mwdat-camera:$mwdat")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
