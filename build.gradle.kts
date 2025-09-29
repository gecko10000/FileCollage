plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

sourceSets {
    main {
        java {
            srcDir("src")
        }
        resources {
            srcDir("res")
        }
    }
}

val artifactName = "AppKt"
val groupName = "gecko10000.filecollage"
group = groupName
version = "0.1"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib", version = "2.2.20"))

    implementation("com.github.serceman:jnr-fuse:0.5.8")

    implementation("io.insert-koin:koin-core:4.1.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    compileOnly("org.apache.logging.log4j:log4j-api:2.23.1")
    compileOnly("org.apache.logging.log4j:log4j-core:2.23.1")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.23.1")

    implementation("dev.inmo:tgbotapi:28.0.3")
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = "$groupName.$artifactName"
    }
}

application {
    mainClass.set("$groupName.$artifactName")
}

tasks {
    build {
        dependsOn(shadowJar)
    }
}
