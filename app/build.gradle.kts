plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.baruchiro.podcatcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.baruchiro.podcatcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-trial"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
