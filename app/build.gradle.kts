plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ev.iletisim"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ev.iletisim"
        minSdk = 26
        targetSdk = 35
        // GitHub'daki her derleme bir öncekinden büyük numara alır; güncelleme bunu karşılaştırır
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.$build"
    }

    // Her derleme aynı anahtarla imzalansın ki yeni sürüm eskinin üstüne kurulabilsin
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("ev-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // QR okuma (kamera ekranı) ve QR üretme
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
    // Görüntülü görüşme (Google WebRTC'nin Maven Central derlemesi)
    implementation("io.github.webrtc-sdk:android:144.7559.15")
    // QR okuyucunun izin isteği için gerekli
    implementation("androidx.core:core:1.13.1")

    testImplementation("junit:junit:4.13.2")
    // Android'deki org.json birim testlerinde boş taklittir; gerçeği kullanılır
    testImplementation("org.json:json:20240303")
}
