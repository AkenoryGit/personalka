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

    // iPhone (шаг 5.1): платформенное — iosMain (база в Application Support, Keychain, NSUserDefaults, CommonCrypto и zlib).
    iosArm64()
    iosSimulatorArm64()

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
            // Файл копии — JSON (ТЗ 7.6): только разбор и сборка дерева, без плагина сериализации.
            implementation(libs.kotlinx.serialization.json)
            // Вход на сервер (шаг 2.8а, ТЗ 2.1): JSON — тем же деревом, движок даёт платформа.
            api(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.android)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        // Сервер входа в памяти (шаг 2.8а) — для тестов слоя данных и экранов.
        jvmTest {
            kotlin.srcDir("src/fakeServer/kotlin")
            dependencies { implementation(libs.ktor.client.mock) }
        }
        // Шифр, хеш и zip копии на стандартной библиотеке Java — один файл для телефона и компьютера (шаг 1.15).
        androidMain { kotlin.srcDir("src/javaShared/kotlin") }
        jvmMain { kotlin.srcDir("src/javaShared/kotlin") }
    }
}

// Room пишет код для каждой цели отдельно.
dependencies {
    add("kspAndroid", libs.androidx.room3.compiler)
    add("kspJvm", libs.androidx.room3.compiler)
    add("kspIosArm64", libs.androidx.room3.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room3.compiler)
}

// Схемы версий базы хранятся в репозитории: по ним Room проверяет и строит миграции.
room3 {
    schemaDirectory("$projectDir/schemas")
}
