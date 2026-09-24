plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.civcraft"
version = "2.0.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation("com.zaxxer:HikariCP:7.1.0") {
        exclude(group = "org.slf4j")
    }

}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked", "-proc:none"))
    }
    processResources {
        filteringCharset = "UTF-8"
        filesMatching("paper-plugin.yml") {
            expand("version" to project.version)
        }
    }
    shadowJar {
        archiveClassifier.set("")
        relocate("com.zaxxer.hikari", "com.civcraft.lib.hikari")
        mergeServiceFiles()
    }
    build {
        dependsOn(shadowJar)
    }
    jar {
        enabled = false
    }
}
