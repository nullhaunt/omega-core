package com.omegarealm.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    private static final String VALID = """
            server:
              id: "dev-01"
              environment: "development"
            database:
              host: "localhost"
              port: 5432
              database: "omega"
              username: "omega"
              password-env: "OMEGA_DB_PASSWORD"
              pool-size: 5
            debug: false
            """;

    private final ConfigLoader loader = new ConfigLoader();

    @TempDir
    Path directory;

    static Stream<Arguments> invalidValues() {
        return Stream.of(
                Arguments.of("server.id", "id: \"dev-01\"", "id: \"\""),
                Arguments.of("server.id", "id: \"dev-01\"", "id: 123"),
                Arguments.of("server.id", "id: \"dev-01\"", "id: \"bad id\""),
                Arguments.of("server.id", "id: \"dev-01\"", "id: \"" + "a".repeat(65) + "\""),
                Arguments.of("server.environment", "environment: \"development\"", "environment: null"),
                Arguments.of("server.environment", "environment: \"development\"", "environment: \"Production\""),
                Arguments.of("database.host", "host: \"localhost\"", "host: \" localhost \""),
                Arguments.of("database.host", "host: \"localhost\"", "host: [localhost]"),
                Arguments.of("database.database", "database: \"omega\"", "database: \"\""),
                Arguments.of("database.username", "username: \"omega\"", "username: false"),
                Arguments.of("database.password-env", "password-env: \"OMEGA_DB_PASSWORD\"", "password-env: \"\""),
                Arguments.of("database.password-env", "password-env: \"OMEGA_DB_PASSWORD\"", "password-env: \"9BAD-NAME\""),
                Arguments.of("database.port", "port: 5432", "port: 0"),
                Arguments.of("database.port", "port: 5432", "port: 65536"),
                Arguments.of("database.port", "port: 5432", "port: \"5432\""),
                Arguments.of("database.port", "port: 5432", "port: 5432.5"),
                Arguments.of("database.port", "port: 5432", "port: 9223372036854775807"),
                Arguments.of("database.pool-size", "pool-size: 5", "pool-size: 0"),
                Arguments.of("database.pool-size", "pool-size: 5", "pool-size: -1"),
                Arguments.of("database.pool-size", "pool-size: 5", "pool-size: \"5\""),
                Arguments.of("debug", "debug: false", "debug: \"false\""),
                Arguments.of("debug", "debug: false", "")
        );
    }

    @Test
    void loadsTypedSettingsWithoutResolvingSecrets() throws Exception {
        OmegaConfig config = loader.parse(VALID);

        assertEquals(new ServerConfig("dev-01", "development"), config.server());
        assertEquals(new DatabaseConfig("localhost", 5432, "omega", "omega", "OMEGA_DB_PASSWORD", 5),
                config.database());
        assertFalse(config.debug());
    }

    @Test
    void createsDefaultFileInANewDataDirectory() throws Exception {
        Path dataDirectory = directory.resolve("OmegaCore");
        OmegaConfig config = loader.load(dataDirectory);

        try (InputStream resource = ConfigLoader.class.getResourceAsStream("/config.yml")) {
            assertNotNull(resource);
            assertEquals(new String(resource.readAllBytes(), StandardCharsets.UTF_8),
                    Files.readString(dataDirectory.resolve("config.yml")));
        }

        assertEquals("dev-01", config.server().id());
    }

    @Test
    void preservesAnExistingConfiguration() throws Exception {
        String custom = VALID.replace("dev-01", "production-02").replace("development", "production");
        Path file = directory.resolve("config.yml");
        Files.writeString(file, custom);

        assertEquals("production-02", loader.load(directory).server().id());
        assertEquals(custom, Files.readString(file));
    }

    @Test
    void doesNotRepairOrMergeAnIncompleteFile() throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "server: {}\n");

        ConfigException failure = assertThrows(ConfigException.class, () -> loader.load(directory));

        assertTrue(failure.getMessage().contains("server.id"));
        assertEquals("server: {}\n", Files.readString(file));
    }

    @Test
    void reportsAnUnreadableConfigurationPath() throws Exception {
        Files.createDirectory(directory.resolve("config.yml"));

        ConfigException failure = assertThrows(ConfigException.class, () -> loader.load(directory));

        assertTrue(failure.getMessage().contains("unable to create or read"));
        assertNull(failure.getCause());
    }

    @ParameterizedTest(name = "{0}: rejects {2}")
    @MethodSource("invalidValues")
    void rejectsInvalidSettingsWithTheKeyButWithoutTheValue(String key, String line, String replacement) {
        String yaml = VALID.replace(line, replacement);

        ConfigException failure = assertThrows(ConfigException.class, () -> loader.parse(yaml));

        assertTrue(failure.getMessage().contains(key), failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void acceptsPortBoundariesAndCustomEnvironmentLabels() throws Exception {
        assertEquals(1, loader.parse(VALID.replace("port: 5432", "port: 1")).database().port());
        assertEquals(65535, loader.parse(VALID.replace("port: 5432", "port: 65535")).database().port());
        assertEquals("preview-2", loader.parse(VALID.replace("development", "preview-2")).server().environment());
    }

    @Test
    void rejectsMalformedYamlWithoutSourceExcerpts() {
        ConfigException failure = assertThrows(ConfigException.class,
                () -> loader.parse(VALID + "password: [DO_NOT_LOG_THIS\n"));

        assertTrue(failure.getMessage().contains("invalid YAML"));
        assertFalse(failure.getMessage().contains("DO_NOT_LOG_THIS"));
        assertNull(failure.getCause());
    }

    @Test
    void rejectsUnknownSettingsWithoutEchoingTheirNamesOrValues() {
        ConfigException failure = assertThrows(ConfigException.class,
                () -> loader.parse(VALID + "DO_NOT_LOG_THIS: SECRET_VALUE\n"));

        assertTrue(failure.getMessage().contains("unknown setting"));
        assertFalse(failure.getMessage().contains("DO_NOT_LOG_THIS"));
        assertFalse(failure.getMessage().contains("SECRET_VALUE"));
    }

    @Test
    void rejectsInvalidRequiredValuesWithoutEchoingThem() {
        ConfigException failure = assertThrows(ConfigException.class,
                () -> loader.parse(VALID.replace("OMEGA_DB_PASSWORD", "DO-NOT-LOG-THIS")));

        assertTrue(failure.getMessage().contains("database.password-env"));
        assertFalse(failure.getMessage().contains("DO-NOT-LOG-THIS"));
    }

    @Test
    void rejectsSerializedYamlObjectsWithoutLeakingParserErrors() {
        String yaml = VALID.replace("host: \"localhost\"", "host: {==: DO_NOT_LOG_THIS}");

        ConfigException failure = assertThrows(ConfigException.class, () -> loader.parse(yaml));

        assertTrue(failure.getMessage().contains("invalid YAML"));
        assertFalse(failure.getMessage().contains("DO_NOT_LOG_THIS"));
        assertNull(failure.getCause());
    }
}
