import java.time.Instant

plugins {
    id("java-library")
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

abstract class GenerateBuildIdentity : DefaultTask() {
    @get:Input abstract val buildVersion: Property<String>
    @get:Input abstract val commit: Property<String>
    @get:OutputFile abstract val destination: RegularFileProperty

    @TaskAction
    fun generate() {
        val file = destination.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            "version=${buildVersion.get()}\n" +
                "commit=${commit.get()}\n" +
                "built-at=${Instant.now()}\n"
        )
    }
}

val gitCommit = providers.exec {
    commandLine("git", "rev-parse", "--short=12", "--verify", "HEAD")
}.standardOutput.asText.map { it.trim() }

val generateBuildIdentity = tasks.register<GenerateBuildIdentity>("generateBuildIdentity") {
    buildVersion.set(version.toString())
    commit.set(gitCommit)
    destination.set(layout.buildDirectory.file("generated/omega-build.properties"))
    outputs.upToDateWhen { false }
}

repositories {
    mavenCentral()
}

dependencies {
    paperweight.paperDevBundle("26.3.build.+")

    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("org.flywaydb:flyway-core:13.8.0")
    implementation("org.flywaydb:flyway-database-postgresql:13.8.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
}

val integrationTest = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
    resources.srcDir("src/test/resources")
}

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    add(integrationTest.implementationConfigurationName, "io.zonky.test:embedded-postgres:2.2.2")
    add(integrationTest.implementationConfigurationName,
        enforcedPlatform("io.zonky.test.postgres:embedded-postgres-binaries-bom:18.6.0"))
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    description = "Runs bootstrap tests against isolated native PostgreSQL databases."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    check {
        dependsOn(integrationTestTask)
    }

    shadowJar {
        filesMatching("META-INF/services/**") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
        mergeServiceFiles()
        relocate("com.zaxxer.hikari", "com.omegarealm.core.internal.hikari")
        relocate("org.postgresql", "com.omegarealm.core.internal.postgresql")
        relocate("org.flywaydb", "com.omegarealm.core.internal.flyway")
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
        // Paper supplies SLF4J. Do not ship a competing API or logging backend.
        dependencies {
            exclude(dependency("org.slf4j:slf4j-api:.*"))
        }
    }

    test {
        useJUnitPlatform()
    }

    build {
        dependsOn(shadowJar)
    }

    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("26.3")
        jvmArgs("-Xms8G", "-Xmx8G", "-Dcom.mojang.eula.agree=true")
    }

    processResources {
        from(generateBuildIdentity)
        val props = mapOf("version" to version, "description" to project.description)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
