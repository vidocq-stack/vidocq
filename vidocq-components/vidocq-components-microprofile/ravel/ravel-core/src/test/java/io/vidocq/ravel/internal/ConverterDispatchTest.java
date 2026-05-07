/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.Converter;
import jakarta.annotation.Priority;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serial;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests d'intégration : converter dispatch via {@link RavelConfig}, priorités,
 * arrays, implicit converters découverts dynamiquement.
 */
@DisplayName("Converters dispatch (built-in, priorité, arrays, implicit)")
class ConverterDispatchTest {

    // ---------- built-in via getValue ----------

    @Test
    void getValue_uses_builtin_converters_for_primitives() {
        Config cfg = newConfig(Map.of(
                "i", "42",
                "l", "1234567890123",
                "d", "3.14",
                "b", "yes",
                "c", "Z"
        ));
        assertEquals(42, cfg.getValue("i", Integer.class));
        assertEquals(1234567890123L, cfg.getValue("l", Long.class));
        assertEquals(3.14d, cfg.getValue("d", Double.class));
        assertEquals(Boolean.TRUE, cfg.getValue("b", Boolean.class));
        assertEquals('Z', cfg.getValue("c", Character.class));
    }

    @Test
    void getValue_uses_builtin_converters_for_time_types() {
        Config cfg = newConfig(Map.of(
                "dur", "PT15S",
                "date", "2026-05-07"
        ));
        assertEquals(java.time.Duration.ofSeconds(15), cfg.getValue("dur", java.time.Duration.class));
        assertEquals(java.time.LocalDate.of(2026, 5, 7), cfg.getValue("date", java.time.LocalDate.class));
    }

    // ---------- arrays §5.4 ----------

    @Test
    void getValue_resolves_string_array_with_comma_split() {
        Config cfg = newConfig(Map.of("k", "a,b,c"));
        assertArrayEquals(new String[]{"a", "b", "c"}, cfg.getValue("k", String[].class));
    }

    @Test
    void getValue_resolves_int_array() {
        Config cfg = newConfig(Map.of("k", "1,2,3"));
        assertArrayEquals(new Integer[]{1, 2, 3}, cfg.getValue("k", Integer[].class));
    }

    @Test
    void getValue_resolves_array_with_escaped_comma() {
        Config cfg = newConfig(Map.of("k", "a\\,b,c"));
        assertArrayEquals(new String[]{"a,b", "c"}, cfg.getValue("k", String[].class));
    }

    @Test
    void getValues_returns_list_split_by_comma() {
        // Méthode default sur Config : délègue au converter de l'array.
        Config cfg = newConfig(Map.of("k", "x,y,z"));
        List<String> vals = cfg.getValues("k", String.class);
        assertEquals(List.of("x", "y", "z"), vals);
    }

    // ---------- implicit converter ----------

    @Test
    void getValue_uses_implicit_converter_for_user_class() {
        Config cfg = newConfig(Map.of("k", "abc"));
        UserType v = cfg.getValue("k", UserType.class);
        assertNotNull(v);
        assertEquals("of:abc", v.value);
    }

    @Test
    void getValue_uses_enum_implicit() {
        Config cfg = newConfig(Map.of("color", "GREEN"));
        Status s = cfg.getValue("color", Status.class);
        assertEquals(Status.GREEN, s);
    }

    @Test
    void getValue_throws_for_unconvertible_type() {
        Config cfg = newConfig(Map.of("k", "x"));
        assertThrows(IllegalArgumentException.class, () -> cfg.getValue("k", Object.class));
    }

    // ---------- priority §5.3 ----------

    @Test
    void custom_converter_overrides_builtin_via_withConverter() {
        // Custom Integer converter avec priorité 200 > built-in (1).
        Converter<Integer> custom = new Converter<>() {
            @Serial private static final long serialVersionUID = 1L;
            @Override public Integer convert(String value) { return Integer.parseInt(value) + 1000; }
        };
        Config cfg = new RavelConfigBuilder()
                .withConverter(Integer.class, 200, custom)
                .withSources(MapConfigSource.of("s", 100, Map.of("k", "5")))
                .build();
        assertEquals(1005, cfg.getValue("k", Integer.class));
    }

    @Test
    void higher_priority_wins_when_multiple_registrations() {
        Converter<Integer> low = new ConstantIntegerConverter(1);
        Converter<Integer> high = new ConstantIntegerConverter(99);
        Config cfg = new RavelConfigBuilder()
                .withConverter(Integer.class, 100, low)
                .withConverter(Integer.class, 500, high)
                .withSources(MapConfigSource.of("s", 100, Map.of("k", "0")))
                .build();
        assertEquals(99, cfg.getValue("k", Integer.class));
    }

    @Test
    void lower_priority_does_not_displace_higher() {
        Converter<Integer> high = new ConstantIntegerConverter(99);
        Converter<Integer> low = new ConstantIntegerConverter(1);
        Config cfg = new RavelConfigBuilder()
                .withConverter(Integer.class, 500, high)
                .withConverter(Integer.class, 100, low)  // ne doit pas écraser high
                .withSources(MapConfigSource.of("s", 100, Map.of("k", "0")))
                .build();
        assertEquals(99, cfg.getValue("k", Integer.class));
    }

    @Test
    void withConverters_reads_jakarta_priority_annotation() {
        // Un converter annoté @TestPriority(300) doit gagner sur le built-in.
        Config cfg = new RavelConfigBuilder()
                .withConverters(new HighPriorityIntegerConverter())
                .withSources(MapConfigSource.of("s", 100, Map.of("k", "0")))
                .build();
        assertEquals(777, cfg.getValue("k", Integer.class));
    }

    @Test
    void withConverters_default_priority_is_100() {
        // Un converter sans @Priority a priorité 100 → écrase le built-in (1).
        Config cfg = new RavelConfigBuilder()
                .withConverters(new ConstantIntegerConverter(42))
                .withSources(MapConfigSource.of("s", 100, Map.of("k", "0")))
                .build();
        assertEquals(42, cfg.getValue("k", Integer.class));
    }

    // ---------- helpers / fixtures ----------

    private static Config newConfig(Map<String, String> data) {
        return new RavelConfigBuilder()
                .withSources(MapConfigSource.of("test", 100, data))
                .build();
    }

    public static final class UserType {
        public final String value;
        private UserType(String v) { this.value = v; }
        public static UserType of(String s) { return new UserType("of:" + s); }
    }

    public enum Status { RED, GREEN, BLUE }

    private static final class ConstantIntegerConverter implements Converter<Integer> {
        @Serial private static final long serialVersionUID = 1L;
        private final int value;
        ConstantIntegerConverter(int value) { this.value = value; }
        @Override public Integer convert(String s) { return value; }
    }

    /** Converter avec priorité explicite §5.3 — doit écraser le built-in (1). */
    @Priority(300)
    public static final class HighPriorityIntegerConverter implements Converter<Integer> {
        @Serial private static final long serialVersionUID = 1L;
        @Override public Integer convert(String s) { return 777; }
    }
}


