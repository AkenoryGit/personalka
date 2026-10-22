import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Room, репозитории, очередь изменений и синхронизация, резервные копии (ТЗ 2.2).
// Пока пустой: база появится на шаге 1.1. JVM-сборка — для утилиты импорта, которая пишет файл копии (1.18).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(21)

    jvm()

    android {
        namespace = "com.akenory.personalka.data"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:domain"))
        }
    }
}
