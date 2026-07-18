plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.areacalc.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.areacalc.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // OpenCV с Maven Central (нативные библиотеки уже внутри пакета)
    implementation("org.opencv:opencv:4.11.0")
    // FileProvider для съёмки фото на полном разрешении
    implementation("androidx.core:core:1.13.1")
    // AppCompat — тема и базовый класс активити
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Material 3 — современный дизайн (кнопки, карточки, переключатели, bottom sheet)
    implementation("com.google.android.material:material:1.12.0")
}
