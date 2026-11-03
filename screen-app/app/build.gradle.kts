plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.bayt.screen"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.bayt.screen"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }
    signingConfigs {
        create("bayt") {
            storeFile = file("bayt.keystore")
            storePassword = "baytscreen"
            keyAlias = "bayt"
            keyPassword = "baytscreen"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("bayt") }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("bayt") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}
dependencies {
    implementation("io.getstream:stream-webrtc-android:1.+")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
