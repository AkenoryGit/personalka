// Временный модуль для отладки шага 1.1: печатает, какие типы KSP не видит у базы. Удаляется после.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:${libs.versions.ksp.get()}")
}
