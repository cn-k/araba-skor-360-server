import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.10"
    application
}

group = "com.arabaskor360"
version = "0.1.0"

repositories {
    mavenCentral()
}

val javalinVersion = "6.7.0"
val exposedVersion = "1.4.0"
val flywayVersion = "13.2.0"

dependencies {
    implementation("io.javalin:javalin:$javalinVersion")
    implementation("org.slf4j:slf4j-simple:2.0.16")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.1")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.22.1")

    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")

    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.postgresql:postgresql:42.7.13")

    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")

    // Swagger UI for the hand-written OpenAPI spec in resources/openapi.yaml.
    // Version pinned to match javalinVersion (this plugin line tracks Javalin releases 1:1).
    implementation("io.javalin.community.openapi:javalin-swagger-plugin:6.7.0-5")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.arabaskor360.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}
