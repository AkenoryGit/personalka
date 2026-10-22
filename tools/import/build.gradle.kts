// Утилита разового импорта таблиц (ТЗ 2.1, раздел 9): JVM + Apache POI, в приложении не видна.
// Пока только каркас — чтение таблиц на шаге 1.17, файл копии на 1.18.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.akenory.personalka.importer.MainKt")
}

dependencies {
    implementation(project(":shared:data"))
}
