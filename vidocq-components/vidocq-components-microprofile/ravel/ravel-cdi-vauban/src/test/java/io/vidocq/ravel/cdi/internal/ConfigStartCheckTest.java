/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.ravel.cdi.internal;

import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.spi.DeploymentException;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The start check alone (ravel#50): fed with the plain data the extension collected at build, and
 * nothing of the extension, it reads the configuration the application starts with.
 */
@DisplayName("ConfigStartCheck — the configuration checked when the application starts")
class ConfigStartCheckTest {

    private static final String KEY = "start.key";
    private static final String STRING = "java.lang.String";
    private static final String INTEGER = "java.lang.Integer";
    private static final String SITE = "com.example.Bean#member";
    private static final String NONE = ConfigStartCheck.NO_DEFAULT;
    private static final String UNCONFIGURED = ConfigProperties.UNCONFIGURED_PREFIX;

    @AfterEach
    void cleanup() {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        Config cfg = resolver.getConfig(getClass().getClassLoader());
        resolver.releaseConfig(cfg);
    }

    // -- The observer: one DeploymentException for every problem ----------------------------------

    @Test
    void missing_required_property_fails_the_start_spec_section6_1() {
        registerConfig(Map.of());
        Map<String, Object> params = properties(property(KEY, STRING, NONE, SITE));

        DeploymentException failure = assertThrows(DeploymentException.class, () -> observe(params));

        assertTrue(failure.getMessage().contains(
                "Missing required config property '" + KEY + "' for type java.lang.String [at " + SITE + "]"),
                failure.getMessage());
    }

    @Test
    void unconvertible_required_property_fails_the_start() {
        registerConfig(Map.of(KEY, "not-a-number"));
        Map<String, Object> params = properties(property(KEY, INTEGER, NONE, SITE));

        DeploymentException failure = assertThrows(DeploymentException.class, () -> observe(params));

        assertTrue(failure.getMessage().contains("Invalid @ConfigProperty for key '" + KEY + "'"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("[at " + SITE + "]"), failure.getMessage());
    }

    @Test
    void present_and_defaulted_properties_let_the_application_start() {
        registerConfig(Map.of(KEY, "42"));
        Map<String, Object> params = properties(
                property(KEY, INTEGER, NONE, SITE),
                property("start.absent", INTEGER, "7", SITE));

        assertDoesNotThrow(() -> observe(params));
    }

    @Test
    void every_problem_is_listed_in_one_exception() {
        registerConfig(Map.of("required.host", "localhost"));
        Map<String, Object> params = new HashMap<>(properties(
                property("first.missing", STRING, NONE, "com.example.A#first"),
                property("second.missing", INTEGER, NONE, "com.example.B#second")));
        params.putAll(classes(configProperties(Required.class.getName(), UNCONFIGURED, "com.example.C#required")));

        DeploymentException failure = assertThrows(DeploymentException.class, () -> observe(params));

        String message = failure.getMessage();
        assertTrue(message.contains("3 problems"), message);
        assertTrue(message.contains("'first.missing' for type java.lang.String [at com.example.A#first]"), message);
        assertTrue(message.contains("'second.missing' for type java.lang.Integer [at com.example.B#second]"), message);
        assertTrue(message.contains("@ConfigProperties: missing required property 'required.port' for "
                + Required.class.getName() + ".port [at com.example.C#required]"), message);
    }

    @Test
    void nothing_to_check_lets_the_application_start() {
        registerConfig(Map.of());

        assertDoesNotThrow(() -> observe(Map.of()));
    }

    // -- @ConfigProperty: the checks the extension ran at deployment ------------------------------

    @Test
    void present_value_whose_converter_returns_null_is_reported() {
        registerConfig(Map.of(KEY, "anything"));

        List<String> problems = problems(properties(property(KEY, NullConverted.class.getName(), NONE, SITE)));

        assertEquals(List.of("Cannot convert required config property '" + KEY + "' to "
                + NullConverted.class.getName() + " (converter returned null) [at " + SITE + "]"), problems);
    }

    @Test
    void empty_default_value_is_reported() {
        registerConfig(Map.of());

        List<String> problems = problems(properties(property(KEY, STRING, "", SITE)));

        assertEquals(List.of("Empty defaultValue is invalid for required config property '" + KEY + "' [at "
                + SITE + "]"), problems);
    }

    @Test
    void default_value_converting_to_null_is_reported() {
        registerConfig(Map.of());

        List<String> problems = problems(properties(property(KEY, NullConverted.class.getName(), "x", SITE)));

        assertEquals(List.of("defaultValue for config property '" + KEY + "' converts to null [at " + SITE + "]"),
                problems);
    }

    @Test
    void default_value_of_a_type_without_converter_is_reported() {
        registerConfig(Map.of());

        List<String> problems = problems(properties(property(KEY, NotConvertible.class.getName(), "x", SITE)));

        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().startsWith("Invalid @ConfigProperty for key '" + KEY + "': No converter"),
                problems.getFirst());
    }

    @Test
    void a_problem_without_injection_site_has_no_location() {
        registerConfig(Map.of());

        List<String> problems = problems(properties(property(KEY, STRING, NONE, "")));

        assertEquals(List.of("Missing required config property '" + KEY + "' for type java.lang.String"), problems);
    }

    @Test
    void every_primitive_type_is_loaded_by_its_name() {
        Map<String, String> values = Map.of("boolean", "true", "byte", "1", "short", "2", "int", "3",
                "long", "4", "float", "5.5", "double", "6.5", "char", "c");
        Map<String, String> config = new HashMap<>();
        List<String[]> rows = new ArrayList<>();
        values.forEach((type, value) -> {
            config.put("prim." + type, value);
            rows.add(property("prim." + type, type, NONE, SITE));
            rows.add(property("prim.missing." + type, type, NONE, SITE));
        });
        registerConfig(config);

        List<String> problems = problems(properties(rows.toArray(String[][]::new)));

        assertEquals(8, problems.size(), problems.toString());
        assertTrue(problems.contains("Missing required config property 'prim.missing.int' for type int [at "
                + SITE + "]"), problems.toString());
    }

    @Test
    void a_type_the_start_cannot_load_is_left_to_the_injection() {
        registerConfig(Map.of());

        List<String> problems = problems(properties(property(KEY, "com.example.DoesNotExist", NONE, SITE)));

        assertEquals(List.of(), problems);
    }

    // -- @ConfigProperties: MP Config 3.1 §6.4 ----------------------------------------------------

    @Test
    void missing_members_are_reported_under_the_class_prefix_spec_section6_4() {
        registerConfig(Map.of("required.host", "localhost"));

        List<String> problems = problems(classes(configProperties(Required.class.getName(), UNCONFIGURED, "")));

        assertEquals(List.of("@ConfigProperties: missing required property 'required.port' for "
                + Required.class.getName() + ".port"), problems);
    }

    @Test
    void the_injection_point_prefix_overrides_the_class_prefix() {
        registerConfig(Map.of("override.host", "h", "override.port", "1"));

        assertEquals(List.of(),
                problems(classes(configProperties(Required.class.getName(), "override", SITE))));
        assertEquals(2,
                problems(classes(configProperties(Required.class.getName(), UNCONFIGURED, SITE))).size());
    }

    @Test
    void static_defaulted_optional_and_initialized_members_are_not_required() {
        registerConfig(Map.of());

        assertEquals(List.of(), problems(classes(configProperties(Lenient.class.getName(), UNCONFIGURED, ""))));
    }

    @Test
    void every_zero_valued_primitive_is_required_and_an_empty_value_is_missing() {
        registerConfig(Map.of("prim.flag", ""));

        List<String> problems = problems(classes(
                configProperties(Primitives.class.getName(), UNCONFIGURED, ""),
                configProperties(NoDefaultConstructor.class.getName(), UNCONFIGURED, "")));

        assertEquals(9, problems.size(), problems.toString());
        assertTrue(problems.stream().anyMatch(m -> m.contains("'prim.flag'")));
        assertTrue(problems.stream().anyMatch(m -> m.contains("'port'")));
    }

    @Test
    void a_class_the_start_cannot_load_is_left_to_the_injection() {
        registerConfig(Map.of());

        assertEquals(List.of(),
                problems(classes(configProperties("com.example.DoesNotExist", UNCONFIGURED, SITE))));
    }

    @Test
    void a_discovered_class_already_checked_at_an_injection_point_is_not_listed_twice() {
        registerConfig(Map.of("required.host", "localhost"));

        List<String> problems = problems(classes(
                configProperties(Required.class.getName(), UNCONFIGURED, SITE),
                configProperties(Required.class.getName(), UNCONFIGURED, "")));

        assertEquals(List.of("@ConfigProperties: missing required property 'required.port' for "
                + Required.class.getName() + ".port [at " + SITE + "]"), problems);
    }

    @Test
    void a_discovered_class_injected_under_another_prefix_is_still_checked_under_its_own() {
        registerConfig(Map.of("override.host", "h", "override.port", "1", "required.host", "localhost"));

        List<String> problems = problems(classes(
                configProperties(Required.class.getName(), "override", SITE),
                configProperties(Required.class.getName(), UNCONFIGURED, "")));

        assertEquals(List.of("@ConfigProperties: missing required property 'required.port' for "
                + Required.class.getName() + ".port"), problems);
    }

    // -- Static helpers ---------------------------------------------------------------------------

    @Test
    void fieldConfigPropertyName_prefixes_the_member_or_the_configured_name() throws NoSuchFieldException {
        var host = Required.class.getDeclaredField("host");
        var port = Required.class.getDeclaredField("port");
        ConfigProperty portAnno = port.getAnnotation(ConfigProperty.class);

        assertEquals("p.host", ConfigStartCheck.fieldConfigPropertyName(host, null, "p"));
        assertEquals("host", ConfigStartCheck.fieldConfigPropertyName(host, null, ""));
        assertEquals("p.port", ConfigStartCheck.fieldConfigPropertyName(port, portAnno, "p"));
        assertEquals("port", ConfigStartCheck.fieldConfigPropertyName(port, portAnno, ""));
    }

    @Test
    void resolveConfigPropertiesPrefix_prefers_the_injection_point_then_the_class() {
        assertEquals("ip", ConfigStartCheck.resolveConfigPropertiesPrefix(Required.class, "ip"));
        assertEquals("required", ConfigStartCheck.resolveConfigPropertiesPrefix(Required.class, UNCONFIGURED));
        assertEquals("", ConfigStartCheck.resolveConfigPropertiesPrefix(String.class, UNCONFIGURED));
    }

    @Test
    void isOptionalLike_recognises_optional_types() {
        assertTrue(ConfigStartCheck.isOptionalLike(Optional.class));
        assertTrue(ConfigStartCheck.isOptionalLike(java.util.OptionalInt.class));
        assertFalse(ConfigStartCheck.isOptionalLike(String.class));
    }

    // -- Fixtures ---------------------------------------------------------------------------------

    @ConfigProperties(prefix = "required")
    static class Required {
        String host;
        @ConfigProperty(name = "port")
        int port;
    }

    @SuppressWarnings("unused")
    @ConfigProperties(prefix = "lenient")
    static class Lenient {
        static final String CONSTANT = "c";
        @ConfigProperty(defaultValue = "d")
        String defaulted;
        Optional<String> optional;
        int initialized = 4;
    }

    @SuppressWarnings("unused")
    @ConfigProperties(prefix = "prim")
    static class Primitives {
        boolean flag;
        byte b;
        short s;
        int i;
        long l;
        float f;
        double d;
        char c;
    }

    @SuppressWarnings("unused")
    @ConfigProperties
    static class NoDefaultConstructor {
        int port = 1;

        NoDefaultConstructor(int port) {
            this.port = port;
        }
    }

    public static final class NullConverted {
    }

    public static final class NullConverter implements Converter<NullConverted> {
        @Override public NullConverted convert(String value) { return null; }
    }

    public static final class NotConvertible {
        private NotConvertible() {
            // no public factory nor String constructor: nothing can convert to this type
        }
    }

    // -- The data the extension hands over, and the configuration ---------------------------------

    private static void observe(Map<String, Object> params) {
        new ConfigStartCheck().observe(null, new MapParameters(params));
    }

    private static List<String> problems(Map<String, Object> params) {
        return ConfigStartCheck.problems(new MapParameters(params), ConfigProvider.getConfig());
    }

    /** One required {@code @ConfigProperty}: key, type name, default value, injection site. */
    private static String[] property(String key, String type, String defaultValue, String site) {
        return new String[]{key, type, defaultValue, site};
    }

    private static Map<String, Object> properties(String[]... rows) {
        return Map.of(
                ConfigStartCheck.PROPERTY_KEYS, column(rows, 0),
                ConfigStartCheck.PROPERTY_TYPES, column(rows, 1),
                ConfigStartCheck.PROPERTY_DEFAULTS, column(rows, 2),
                ConfigStartCheck.PROPERTY_SITES, column(rows, 3));
    }

    /** One {@code @ConfigProperties} class: class name, raw prefix, injection site (empty: discovered). */
    private static String[] configProperties(String className, String rawPrefix, String site) {
        return new String[]{className, rawPrefix, site};
    }

    private static Map<String, Object> classes(String[]... rows) {
        return Map.of(
                ConfigStartCheck.CLASS_NAMES, column(rows, 0),
                ConfigStartCheck.CLASS_PREFIXES, column(rows, 1),
                ConfigStartCheck.CLASS_SITES, column(rows, 2));
    }

    private static String[] column(String[][] rows, int index) {
        String[] column = new String[rows.length];
        for (int i = 0; i < rows.length; i++) {
            column[i] = rows[i][index];
        }
        return column;
    }

    private void registerConfig(Map<String, String> values) {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        Config custom = resolver.getBuilder()
                .withSources(new TestSource(values))
                .withConverter(NullConverted.class, 100, new NullConverter())
                .forClassLoader(getClass().getClassLoader())
                .build();
        resolver.registerConfig(custom, getClass().getClassLoader());
    }

    private record MapParameters(Map<String, Object> values) implements Parameters {
        @Override public <T> T get(String key, Class<T> type) { return type.cast(values.get(key)); }

        @Override public <T> T get(String key, Class<T> type, T defaultValue) {
            T value = get(key, type);
            return value != null ? value : defaultValue;
        }
    }

    private record TestSource(Map<String, String> values) implements ConfigSource {
        @Override public Map<String, String> getProperties() { return values; }
        @Override public Set<String> getPropertyNames() { return values.keySet(); }
        @Override public String getValue(String propertyName) { return values.get(propertyName); }
        @Override public String getName() { return "start-check-source"; }
        @Override public int getOrdinal() { return 1000; }
    }
}
