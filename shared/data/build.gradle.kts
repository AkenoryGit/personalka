import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Room, репозитории, очередь изменений и синхронизация, резервные копии (ТЗ 2.2).
// База — Room 3 (шаг 1.1). JVM-сборка — для утилиты импорта, которая пишет файл копии (1.18), и для тестов базы.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room3)
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

    compilerOptions {
        // expect object PersonalkaDatabaseConstructor — его actual пишет Room.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:domain"))
            api(libs.androidx.room3.runtime)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.androidx.sqlite.bundled)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// Room пишет код для каждой цели отдельно.
dependencies {
    add("kspAndroid", libs.androidx.room3.compiler)
    add("kspJvm", libs.androidx.room3.compiler)
}

// Схемы версий базы хранятся в репозитории: по ним Room проверяет и строит миграции.
room3 {
    schemaDirectory("$projectDir/schemas")
}
