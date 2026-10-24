import java.util.zip.ZipFile

// Сервер (ТЗ 2.1, 2.2, 7.4): Ktor 3.1.3, Java 21, PostgreSQL 16 + Flyway. Шаг 2.7 — каркас: настройки
// из окружения, миграции при запуске, /health. Счётчики и статусы кабинета считает тот же shared/domain, что и приложение.
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

// Номер версии — номер сборки Actions, как у APK (0.1.N): /health показывает, какая версия сейчас на сервере.
val buildNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1
version = "0.1.$buildNumber"

dependencies {
    implementation(project(":shared:domain"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.logback.classic)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
}

// Списки служб (META-INF/services) в библиотеках совпадают по имени: у Flyway поддержка PostgreSQL — отдельной
// библиотекой со своим списком. В один файл они складываются строками, иначе остался бы только первый.
val mergedServices = tasks.register("mergedServices") {
    val jars = configurations.runtimeClasspath
    val out = layout.buildDirectory.dir("merged-services")
    inputs.files(jars)
    outputs.dir(out)
    doLast {
        val lines = sortedMapOf<String, LinkedHashSet<String>>()
        for (jar in jars.get().filter { it.name.endsWith(".jar") }) {
            ZipFile(jar).use { zip ->
                for (entry in zip.entries().toList()) {
                    if (entry.isDirectory || !entry.name.startsWith("META-INF/services/")) continue
                    val text = zip.getInputStream(entry).bufferedReader().readText()
                    lines.getOrPut(entry.name) { LinkedHashSet() } += text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                }
            }
        }
        val root = out.get().asFile
        root.deleteRecursively()
        lines.forEach { (name, services) -> root.resolve(name).apply { parentFile.mkdirs() }.writeText(services.joinToString("\n", postfix = "\n")) }
    }
}

// Один файл для VPS: `java -jar personalka-server.jar`. Выкладывается в релиз рядом с APK, оттуда его берёт выкладка.
tasks.register<Jar>("serverJar") {
    archiveFileName.set("personalka-server.jar")
    manifest {
        attributes(
            "Main-Class" to application.mainClass.get(),
            "Implementation-Version" to project.version,
        )
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from(mergedServices)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude("META-INF/services/**")
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class", "module-info.class")
    }
}

// Тесты базы идут на настоящем PostgreSQL 16: в Actions — служба сборки, у себя — адрес в TRENER_TEST_DB_URL.
tasks.test {
    useJUnit()
    environment("LC_ALL", "C.UTF-8")
}
