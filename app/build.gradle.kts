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
        // Evdeki telefon ve tabletlerin işlemcileri; emülatör kütüphaneleri APK'yı boşuna büyütüyordu
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Her derleme aynı anahtarla imzalansın ki yeni sürüm eskinin üstüne kurulabilsin
    signingConfigs {
        create("ev") {
            storeFile = rootProject.file("ev-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("ev")
        }
        debug {
            signingConfig = signingConfigs.getByName("ev")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // Yerel kütüphaneler sıkıştırılsın: indirme boyutu yarıya iner
    packaging {
        jniLibs.useLegacyPackaging = true
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
