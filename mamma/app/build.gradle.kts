plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.fiyorfl.mamma"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.fiyorfl.mamma"
        minSdk = 24
        targetSdk = 34
        versionCode = (System.getenv("MAMMA_CODE") ?: "1").toInt()
        versionName = System.getenv("MAMMA_VERSION") ?: "1.0.0"
    }
    // Llave fija: cada version nueva se instala ENCIMA de la anterior
    signingConfigs {
        create("mamma") {
            storeFile = file("mamma.jks")
            storePassword = "mammamusica"
            keyAlias = "mamma"
            keyPassword = "mammamusica"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("mamma") }
        getByName("release") {
            signingConfig = signingConfigs.getByName("mamma")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("com.google.guava:guava:33.2.1-android")
}
