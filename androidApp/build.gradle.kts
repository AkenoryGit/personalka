// Приложение для Android (ТЗ 2.2, 2.3): Android 10 и новее, основной телефон — Galaxy Z Fold 8.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Номер сборки GitHub Actions: каждая новая сборка ставится поверх предыдущей.
val buildNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1

android {
    namespace = "com.akenory.personalka"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // Имя пакета не меняется никогда (шаг 0.1).
        applicationId = "com.akenory.personalka"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        // Один ключ для всех отладочных сборок: APK из любой сборки ставится поверх прежнего без удаления.
        // Ключ для RuStore будет отдельный и в репозиторий не попадёт (шаг 5.6).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":shared:ui"))
    implementation(project(":shared:data"))
    implementation(libs.androidx.activity.compose)
}
