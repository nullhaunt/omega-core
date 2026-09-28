package com.omegarealm.core.diagnostics;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Objects;
import java.util.Properties;

/**
 * Provenance embedded in the shaded artifact at build time.
 */
public record BuildIdentity(String version, String commit, Instant builtAt) {

    public BuildIdentity {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(commit, "commit");
        Objects.requireNonNull(builtAt, "builtAt");
        if (version.isBlank() || !commit.matches("[0-9a-fA-F]{12,40}")) {
            throw new IllegalArgumentException("Invalid Omega build identity.");
        }
    }

    public static BuildIdentity load(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        Properties properties = new Properties();
        try (InputStream input = loader.getResourceAsStream("omega-build.properties")) {
            if (input == null) {
                throw new IllegalStateException("Omega build identity is missing.");
            }
            properties.load(input);
        } catch (IOException failure) {
            throw new IllegalStateException("Omega build identity could not be read.");
        }

        try {
            return new BuildIdentity(properties.getProperty("version"),
                    properties.getProperty("commit"),
                    Instant.parse(properties.getProperty("built-at")));
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Omega build identity is invalid.");
        }
    }
}
