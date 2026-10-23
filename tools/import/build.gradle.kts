// Утилита разового импорта таблиц (ТЗ 2.1, раздел 9): JVM + Apache POI, в приложении не видна.
// Шаг 1.17 — чтение таблиц, отчёт и файл сопоставления; шаг 1.18 — файл копии.
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
    implementation(libs.apache.poi.ooxml)
    testImplementation(libs.kotlin.test)
}

// Один файл для компьютера тренера: `java -jar personalka-import.jar <папка>`. Выкладывается в релиз рядом с APK.
val importJar by tasks.registering(Jar::class) {
    archiveFileName.set("personalka-import.jar")
    manifest { attributes("Main-Class" to application.mainClass.get()) }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class", "module-info.class")
    }
}

// Имена таблиц — по-русски. Без UTF-8 в локали (контейнеры сборки) Java видит их знаками «?».
tasks.test {
    environment("LC_ALL", "C.UTF-8")
}
