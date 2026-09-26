package com.omegarealm.core;

import com.omegarealm.core.config.DatabaseConfig;
import com.omegarealm.core.config.OmegaConfig;
import com.omegarealm.core.config.ServerConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class OmegaRuntimeTest {

    @Test
    void retainsSettingsAndStopsOnlyOnce() {
        OmegaConfig config = new OmegaConfig(
                new ServerConfig("dev-01", "development"),
                new DatabaseConfig("localhost", 5432, "omega", "omega", "OMEGA_DB_PASSWORD", 5),
                false
        );
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        List<LogRecord> records = new ArrayList<>();
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        OmegaRuntime runtime = new OmegaRuntime(config, logger);
        runtime.stop();
        runtime.stop();

        assertSame(config, runtime.config());
        assertEquals(1, records.size());
        assertEquals("Omega Core foundation stopped.", records.getFirst().getMessage());
    }
}
