import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Экраны Compose и дизайн-система (ТЗ 2.2). Тема — из шага 0.2 без изменений.
// JVM-цель (шаг 1.5) — только для тестов экранов на компьютере: runComposeUiTest без телефона и эмулятора;
// база в тестах экранов — та же Room в памяти, что в тестах shared/data.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

// Отрисовка Compose на компьютере (Skia) — своя для системы и процессора, на которых идут тесты.
val composeDesktopNatives: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }
    val target = when {
        os.contains("mac") -> if (arm) "macos-arm64" else "macos-x64"
        os.contains("win") -> "windows-x64"
        else -> if (arm) "linux-arm64" else "linux-x64"
    }
    "org.jetbrains.compose.desktop:desktop-jvm-$target:${libs.versions.compose.multiplatform.get()}"
}

kotlin {
    jvmToolchain(21)

    jvm()

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
            // Экраны читают и пишут базу через репозитории слоя данных (с шага 1.6).
            api(project(":shared:data"))
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(libs.compose.material3)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        // Тесты оболочки и сообщений — только на JVM: Compose там рисуется без Android.
        jvmTest.dependencies {
            implementation(libs.compose.ui.test)
            implementation(composeDesktopNatives)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}
