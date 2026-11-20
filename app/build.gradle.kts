plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.baruchiro.podcatcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.baruchiro.podcatcher.next"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3-trial-chat"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // App identity key. The private key is never in the repo. A build that has
    // PODCATCHER_KEYSTORE (path to a PKCS12 file) and PODCATCHER_KEYSTORE_PASS signs with it, so
    // updates keep installing over each other. Without them (CI) the build uses the debug key.
    val identityKeystore = System.getenv("PODCATCHER_KEYSTORE")
    if (identityKeystore != null) {
        signingConfigs.create("identity") {
            storeFile = file(identityKeystore)
            storePassword = System.getenv("PODCATCHER_KEYSTORE_PASS")
            keyAlias = "podcatcher"
            keyPassword = System.getenv("PODCATCHER_KEYSTORE_PASS")
        }
        buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("identity")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/ASL2.0",
            "META-INF/*.kotlin_module",
        )
    }
}

// The real-Gemini Robolectric slice is a MANUAL local check: it needs the
// GEMINI_API_KEY env var and downloads Robolectric's android-all at runtime.
// CI must not boot it (flake-prone, and it skips there anyway).
tasks.withType<Test>().configureEach {
    if (!project.hasProperty("realGemini")) {
        exclude("**/RealGeminiRobolectricTest*")
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20231013")
    testImplementation("xmlpull:xmlpull:1.1.3.1")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

// THROWAWAY lead check
val updCheck = tasks.register<Exec>("updCheck") {
    isIgnoreExitValue = true
    workingDir = rootDir
    commandLine("bash", ".github/scripts/upd.sh")
}
tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach { finalizedBy(updCheck) }
