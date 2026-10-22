import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Экраны Compose и дизайн-система (ТЗ 2.2). Тема — из шага 0.2 без изменений.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    android {
        namespace = "com.akenory.personalka.ui"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        // Тесты общего кода темы идут на компьютере, без телефона.
        withHostTestBuilder {}
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:domain"))
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(libs.compose.material3)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
