package io.vidocq.mpserver.core.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EnvConfigSourceTest {

    @Test
    void ordinalIs300() {
        assertEquals(300, new EnvConfigSource().getOrdinal());
    }

    @Test
    void readsFirstExistingEnvVariable() {
        Map<String, String> env = System.getenv();
        if (env.isEmpty()) return;
        String anyKey = env.keySet().iterator().next();
        assertEquals(env.get(anyKey), new EnvConfigSource().getValue(anyKey));
    }

    @Test
    void sanitizeReplacesNonAlphanumericWithUnderscore() {
        assertEquals("server_port", EnvConfigSource.sanitize("server.port"));
        assertEquals("a_b_c", EnvConfigSource.sanitize("a-b/c"));
        assertEquals("KEY_NAME", EnvConfigSource.sanitize("KEY.NAME"));
        assertEquals("simple", EnvConfigSource.sanitize("simple"));
    }
}
