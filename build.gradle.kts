plugins {
    kotlin("jvm") version "2.1.0"
    id("com.gradleup.shadow") version "9.0.0-beta4"
}

group = "com.nullswan"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation(kotlin("stdlib"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation("io.mockk:mockk:1.13.13")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("kotlin", "com.nullswan.autopickup.kotlin")
    relocate("org.jetbrains", "com.nullswan.autopickup.jetbrains")
    relocate("org.intellij", "com.nullswan.autopickup.intellij")
    minimize {
        exclude(dependency("org.jetbrains.kotlin:.*"))
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
