package io.vidocq.humboldt.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvConfigTest {

    @Test
    void env_var_wins_over_system_property() {
        EnvConfig cfg = EnvConfig.of(
                Map.of("OTEL_SERVICE_NAME", "from-env"),
                Map.of("otel.service.name", "from-prop"));
        assertEquals("from-env", cfg.getOrDefault("OTEL_SERVICE_NAME", "default"));
    }

    @Test
    void system_property_used_when_env_absent() {
        EnvConfig cfg = EnvConfig.of(
                Map.of(),
                Map.of("otel.service.name", "from-prop"));
        assertEquals("from-prop", cfg.getOrDefault("OTEL_SERVICE_NAME", "default"));
    }

    @Test
    void default_returned_when_neither_present() {
        EnvConfig cfg = EnvConfig.of(Map.of(), Map.of());
        assertEquals("def", cfg.getOrDefault("OTEL_SERVICE_NAME", "def"));
        assertTrue(cfg.get("OTEL_SERVICE_NAME").isEmpty());
    }

    @Test
    void blank_values_treated_as_absent() {
        EnvConfig cfg = EnvConfig.of(
                Map.of("OTEL_SERVICE_NAME", "   "),
                Map.of());
        assertTrue(cfg.get("OTEL_SERVICE_NAME").isEmpty());
    }

    @Test
    void value_is_stripped() {
        EnvConfig cfg = EnvConfig.of(
                Map.of("OTEL_SERVICE_NAME", "  trimmed  "),
                Map.of());
        assertEquals("trimmed", cfg.get("OTEL_SERVICE_NAME").orElseThrow());
    }

    @Test
    void boolean_parse() {
        EnvConfig cfg = EnvConfig.of(
                Map.of("F1", "true", "F2", "1", "F3", "TRUE", "F4", "false", "F5", "wat"),
                Map.of());
        assertTrue(cfg.getBoolean("F1", false));
        assertTrue(cfg.getBoolean("F2", false));
        assertTrue(cfg.getBoolean("F3", false));
        assertFalse(cfg.getBoolean("F4", true));
        assertFalse(cfg.getBoolean("F5", true), "valeur non-boolean → defaut false");
        assertTrue(cfg.getBoolean("MISSING", true), "absent → defaut");
    }

    @Test
    void long_and_double_parse_with_fallback() {
        EnvConfig cfg = EnvConfig.of(
                Map.of("N", "42", "D", "0.5", "BAD", "not-a-number"),
                Map.of());
        assertEquals(42L, cfg.getLong("N", 0L));
        assertEquals(0.5, cfg.getDouble("D", 0.0), 0.0001);
        assertEquals(99L, cfg.getLong("BAD", 99L), "valeur invalide → defaut");
        assertEquals(7L, cfg.getLong("MISSING", 7L));
    }

    @Test
    void env_to_prop_conversion() {
        assertEquals("otel.service.name", EnvConfig.envToProp("OTEL_SERVICE_NAME"));
        assertEquals("otel.exporter.otlp.endpoint",
                EnvConfig.envToProp("OTEL_EXPORTER_OTLP_ENDPOINT"));
    }
}
