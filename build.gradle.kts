import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.gradle.api.logging.Logging
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

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

abstract class GeyserProcessService : BuildService<BuildServiceParameters.None>, AutoCloseable {
    private val logger = Logging.getLogger(GeyserProcessService::class.java)
    private var process: Process? = null

    @Volatile
    private var stopping = false

    @Synchronized
    fun start(javaExecutable: File, directory: File) {
        check(process == null) { "This runServer invocation already owns a Geyser process." }

        val log = directory.resolve("gradle-geyser.log")
        val started = try {
            ProcessBuilder(
                javaExecutable.absolutePath,
                "-Xmx1G",
                "-jar",
                "Geyser-Standalone.jar",
                "--nogui"
            )
                .directory(directory)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(log))
                .start()
        } catch (exception: IOException) {
            throw GradleException("Could not start Geyser Standalone. Check the Java installation and run/geyser-standalone directory.", exception)
        }

        process = started
        val exitedDuringStartup = try {
            started.waitFor(3, TimeUnit.SECONDS)
        } catch (exception: InterruptedException) {
            process = null
            started.destroyForcibly()
            Thread.currentThread().interrupt()
            throw GradleException("Interrupted while starting Geyser Standalone.", exception)
        }

        if (exitedDuringStartup) {
            process = null
            throw GradleException("Geyser Standalone exited during startup. Check $log.")
        }

        started.onExit().thenAccept {
            if (!stopping) {
                logger.error("Geyser Standalone exited while runServer was active. Check {}.", log)
            }
        }

        logger.lifecycle("Started Geyser Standalone; output is in {}.", log)
    }

    @Synchronized
    fun stop() {
        val running = process ?: return
        process = null
        stopping = true

        if (!running.isAlive) {
            return
        }

        try {
            running.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { input ->
                input.write("geyser stop")
                input.newLine()
            }
        } catch (_: IOException) {
            // Geyser may have exited before its console accepted the stop command.
        }

        try {
            if (!running.waitFor(5, TimeUnit.SECONDS)) {
                running.destroy()
                if (!running.waitFor(2, TimeUnit.SECONDS)) {
                    running.destroyForcibly().waitFor(2, TimeUnit.SECONDS)
                }
            }
        } catch (_: InterruptedException) {
            running.destroyForcibly()
            Thread.currentThread().interrupt()
        }

        if (running.isAlive) {
            logger.error("Geyser Standalone did not exit after its stop deadline; check the process before restarting.")
        } else {
            logger.lifecycle("Stopped Geyser Standalone.")
        }
    }

    override fun close() {
        stop()
    }
}

val geyserProcess = gradle.sharedServices.registerIfAbsent("omegaGeyser", GeyserProcessService::class) {}

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
    maven {
        url = uri("https://repo.opencollab.dev/main/")
        content {
            includeGroup("org.geysermc.floodgate")
        }
    }
}

dependencies {
    paperweight.paperDevBundle("26.3.build.+")
    compileOnly("org.geysermc.floodgate:api:2.2.5-SNAPSHOT") {
        isTransitive = false
    }

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

val java25Launcher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
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
        val service = geyserProcess

        minecraftVersion("26.3")
        jvmArgs("-Xms8G", "-Xmx8G", "-Dcom.mojang.eula.agree=true")
        javaLauncher.set(java25Launcher)
        // Gradle closes the service if Paper fails before the final task action.
        usesService(geyserProcess)

        doFirst {
            val serverTask = this as xyz.jpenilla.runpaper.task.RunServer
            val serverDirectory = serverTask.runDirectory.get().asFile
            val pluginsDirectory = serverDirectory.resolve("plugins")

            for (prefix in listOf("floodgate-spigot-", "ViaVersion-", "ViaBackwards-")) {
                val matches = pluginsDirectory.listFiles { file ->
                    file.isFile && file.name.startsWith(prefix) && file.extension == "jar"
                }.orEmpty()

                if (matches.size != 1) {
                    throw GradleException("Expected one ${prefix}*.jar in $pluginsDirectory; found ${matches.size}.")
                }
            }

            val geyserDirectory = serverDirectory.resolve("geyser-standalone")
            for (name in listOf("Geyser-Standalone.jar", "config.yml", "key.pem")) {
                if (!geyserDirectory.resolve(name).isFile) {
                    throw GradleException("Missing $name in $geyserDirectory. Install and configure Geyser Standalone before running Paper.")
                }
            }

            if (!pluginsDirectory.resolve("floodgate/key.pem").isFile) {
                throw GradleException("Missing Floodgate key in $pluginsDirectory/floodgate. Restore the matching key before running Paper.")
            }

            service.get().start(serverTask.javaLauncher.get().executablePath.asFile, geyserDirectory)
        }

        doLast {
            service.get().stop()
        }
    }

    processResources {
        from(generateBuildIdentity)
        val props = mapOf("version" to version, "description" to project.description)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
