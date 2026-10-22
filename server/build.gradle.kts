// Сервер (ТЗ 2.1, 2.2): Ktor 3.1.3, Java 21. Пока только каркас — Ktor, база и развёртывание на шаге 2.7.
// Счётчики и статусы кабинета считает тот же shared/domain, что и приложение.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.akenory.personalka.server.MainKt")
}

dependencies {
    implementation(project(":shared:domain"))
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnit()
}
