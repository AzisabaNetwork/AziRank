plugins {
    kotlin("jvm") version  "2.5.0-Beta2"
    id("com.gradleup.shadow") version "9.6.1"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("net.luckperms:api:5.4")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.3")
}

kotlin {
    jvmToolchain(21)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
        relocate("com.zaxxer.hikari", "net.azisaba.azirank.libs.hikari")
        relocate("org.mariadb.jdbc", "net.azisaba.azirank.libs.mariadb")
        exclude("org/slf4j/**")
    }

    processResources {
        val props = mapOf("version" to version , "description" to project.description )
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
