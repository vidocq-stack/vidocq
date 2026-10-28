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

import jakarta.enterprise.inject.spi.Annotated;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.inject.spi.InjectionPoint;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RavelConfigPropertyResolver — @ConfigProperty injection point resolution (MP Config 3.1 §5, §6)")
class RavelConfigPropertyResolverTest {

    private static final String KEY = "resolver.key";
    private static final String DEFAULT = "dflt";

    @AfterEach
    void cleanup() {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        Config cfg = resolver.getConfig(getClass().getClassLoader());
        resolver.releaseConfig(cfg);
    }

    @Test
    void injection_point_without_ConfigProperty_is_rejected() {
        registerConfig(Map.of());
        InjectionPoint ip = injectionPointFor("notAnnotated");

        assertThrows(IllegalArgumentException.class, () -> RavelConfigPropertyResolver.resolve(ip));
    }

    @Test
    void blank_name_falls_back_to_the_canonical_member_name() {
        registerConfig(Map.of(Fields.class.getCanonicalName() + ".blankName", "by-member"));

        assertEquals("by-member", resolve("blankName"));
    }

    @Test
    void empty_value_counts_as_missing_and_the_default_applies() {
        registerConfig(Map.of(KEY, ""));

        assertEquals("fallback", resolve("stringWithDefault"));
    }

    // -- §6.3 ConfigValue ---------------------------------------------------------------------

    @Test
    void ConfigValue_injection_returns_the_resolved_value_and_its_source() {
        registerConfig(Map.of(KEY, "present"));

        ConfigValue cv = assertInstanceOf(ConfigValue.class, resolve("configValue"));

        assertEquals(KEY, cv.getName());
        assertEquals("present", cv.getValue());
        assertEquals("test-source", cv.getSourceName());
    }

    @Test
    void ConfigValue_injection_of_a_missing_property_carries_the_default_value() {
        registerConfig(Map.of());

        ConfigValue cv = assertInstanceOf(ConfigValue.class, resolve("configValueWithDefault"));

        assertEquals(KEY, cv.getName());
        assertEquals(DEFAULT, cv.getValue());
        assertEquals(DEFAULT, cv.getRawValue());
        assertNull(cv.getSourceName());
        assertEquals(0, cv.getSourceOrdinal());
    }

    @Test
    void ConfigValue_injection_of_a_missing_property_without_default_has_no_value() {
        registerConfig(Map.of());

        ConfigValue cv = assertInstanceOf(ConfigValue.class, resolve("configValue"));

        assertEquals(KEY, cv.getName());
        assertNull(cv.getValue());
    }

    // -- OptionalInt / OptionalLong / OptionalDouble -------------------------------------------

    @Test
    void OptionalInt_reads_a_present_value() {
        registerConfig(Map.of(KEY, "42"));

        assertEquals(OptionalInt.of(42), resolve("optionalInt"));
    }

    @Test
    void OptionalInt_without_value_nor_default_is_empty() {
        registerConfig(Map.of());

        assertEquals(OptionalInt.empty(), resolve("optionalInt"));
    }

    @Test
    void OptionalLong_converts_the_default_value_when_missing() {
        registerConfig(Map.of());

        assertEquals(OptionalLong.of(7L), resolve("optionalLongWithDefault"));
    }

    @Test
    void OptionalLong_without_value_nor_default_is_empty() {
        registerConfig(Map.of());

        assertEquals(OptionalLong.empty(), resolve("optionalLong"));
    }

    @Test
    void OptionalDouble_without_value_nor_default_is_empty() {
        registerConfig(Map.of());

        assertEquals(OptionalDouble.empty(), resolve("optionalDouble"));
    }

    @Test
    void OptionalDouble_reads_a_present_value() {
        registerConfig(Map.of(KEY, "1.5"));

        assertEquals(OptionalDouble.of(1.5d), resolve("optionalDouble"));
    }

    // -- Optional<T> ---------------------------------------------------------------------------

    @Test
    void Optional_converts_the_default_value_when_missing() {
        registerConfig(Map.of());

        assertEquals(Optional.of(9), resolve("optionalIntegerWithDefault"));
    }

    @Test
    void Optional_present_value_ignores_the_default() {
        registerConfig(Map.of(KEY, "3"));

        assertEquals(Optional.of(3), resolve("optionalIntegerWithDefault"));
    }

    // -- §5.4 List<T> / Set<T> -----------------------------------------------------------------

    @Test
    void List_splits_and_converts_a_present_value() {
        registerConfig(Map.of(KEY, "1,2,3"));

        assertEquals(List.of(1, 2, 3), resolve("integerList"));
    }

    @Test
    void List_honours_escaped_commas() {
        registerConfig(Map.of(KEY, "a\\,b,c"));

        assertEquals(List.of("a,b", "c"), resolve("stringList"));
    }

    @Test
    void Set_uses_the_default_value_and_removes_duplicates_in_order() {
        registerConfig(Map.of());

        Object value = resolve("stringSetWithDefault");

        assertInstanceOf(LinkedHashSet.class, value);
        assertEquals(List.of("x", "y"), List.copyOf((Set<?>) value));
    }

    @Test
    void List_missing_without_default_is_a_deployment_problem() {
        registerConfig(Map.of());
        InjectionPoint ip = injectionPointFor("stringList");

        assertThrows(DeploymentException.class, () -> RavelConfigPropertyResolver.resolve(ip));
    }

    // -- Required scalars ----------------------------------------------------------------------

    @Test
    void required_value_whose_converter_returns_null_is_a_deployment_problem() {
        registerConfig(Map.of(KEY, "anything"));
        InjectionPoint ip = injectionPointFor("nullConverted");

        DeploymentException e = assertThrows(DeploymentException.class, () -> RavelConfigPropertyResolver.resolve(ip));
        assertTrue(e.getMessage().contains("converter returned null"), e.getMessage());
    }

    @Test
    void required_value_with_an_empty_default_is_a_deployment_problem() {
        registerConfig(Map.of());
        InjectionPoint ip = injectionPointFor("stringWithEmptyDefault");

        DeploymentException e = assertThrows(DeploymentException.class, () -> RavelConfigPropertyResolver.resolve(ip));
        assertTrue(e.getMessage().contains("Empty defaultValue"), e.getMessage());
    }

    @Test
    void required_value_whose_default_converts_to_null_is_a_deployment_problem() {
        registerConfig(Map.of());
        InjectionPoint ip = injectionPointFor("nullConvertedWithDefault");

        DeploymentException e = assertThrows(DeploymentException.class, () -> RavelConfigPropertyResolver.resolve(ip));
        assertTrue(e.getMessage().contains("converts to null"), e.getMessage());
    }

    @Test
    void default_value_of_a_type_without_converter_is_a_deployment_problem() {
        registerConfig(Map.of());
        InjectionPoint ip = injectionPointFor("notConvertibleWithDefault");

        DeploymentException e = assertThrows(DeploymentException.class, () -> RavelConfigPropertyResolver.resolve(ip));
        assertTrue(e.getMessage().contains("No converter"), e.getMessage());
    }

    @Test
    void type_variable_injection_point_is_rejected() {
        registerConfig(Map.of(KEY, "v"));
        InjectionPoint ip = injectionPointFor(GenericFields.class, "value");

        assertThrows(IllegalArgumentException.class, () -> RavelConfigPropertyResolver.resolve(ip));
    }

    // -- Helpers -------------------------------------------------------------------------------

    private Object resolve(String fieldName) {
        return RavelConfigPropertyResolver.resolve(injectionPointFor(fieldName));
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

    private static InjectionPoint injectionPointFor(String fieldName) {
        return injectionPointFor(Fields.class, fieldName);
    }

    private static InjectionPoint injectionPointFor(Class<?> owner, String fieldName) {
        Field field;
        try {
            field = owner.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
        return new FieldInjectionPoint(field);
    }

    private record FieldInjectionPoint(Field field) implements InjectionPoint {
        @Override public Type getType() { return field.getGenericType(); }
        @Override public Set<Annotation> getQualifiers() { return Set.of(field.getAnnotations()); }
        @Override public Bean<?> getBean() { return null; }
        @Override public Member getMember() { return field; }
        @Override public boolean isDelegate() { return false; }
        @Override public boolean isTransient() { return false; }

        @Override
        public Annotated getAnnotated() {
            return new Annotated() {
                @Override public Type getBaseType() { return field.getGenericType(); }
                @Override public Set<Type> getTypeClosure() { return Set.of(field.getGenericType()); }
                @Override public <T extends Annotation> T getAnnotation(Class<T> type) { return field.getAnnotation(type); }
                @Override public Set<Annotation> getAnnotations() { return Set.of(field.getAnnotations()); }
                @Override public boolean isAnnotationPresent(Class<? extends Annotation> type) {
                    return field.isAnnotationPresent(type);
                }
                @Override public <T extends Annotation> Set<T> getAnnotations(Class<T> type) {
                    return Arrays.stream(field.getAnnotationsByType(type)).collect(Collectors.toSet());
                }
            };
        }
    }

    private record TestSource(Map<String, String> values) implements ConfigSource {
        @Override public Map<String, String> getProperties() { return values; }
        @Override public Set<String> getPropertyNames() { return values.keySet(); }
        @Override public String getValue(String propertyName) { return values.get(propertyName); }
        @Override public String getName() { return "test-source"; }
        @Override public int getOrdinal() { return 1000; }
    }

    /** A type whose registered converter answers {@code null} for every input. */
    public static final class NullConverted {
    }

    public static final class NullConverter implements Converter<NullConverted> {
        @Override public NullConverted convert(String value) { return null; }
    }

    /** No built-in, registered or implicit converter applies to this type. */
    public static final class NotConvertible {
        private NotConvertible() {
            // no public factory nor String constructor: nothing can convert to this type
        }
    }

    static final class GenericFields<T> {
        @ConfigProperty(name = KEY)
        T value;
    }

    @SuppressWarnings("unused")
    static final class Fields {
        String notAnnotated;

        @ConfigProperty(name = " ")
        String blankName;

        @ConfigProperty(name = KEY, defaultValue = "fallback")
        String stringWithDefault;

        @ConfigProperty(name = KEY, defaultValue = "")
        String stringWithEmptyDefault;

        @ConfigProperty(name = KEY)
        ConfigValue configValue;

        @ConfigProperty(name = KEY, defaultValue = DEFAULT)
        ConfigValue configValueWithDefault;

        @ConfigProperty(name = KEY)
        OptionalInt optionalInt;

        @ConfigProperty(name = KEY)
        OptionalLong optionalLong;

        @ConfigProperty(name = KEY, defaultValue = "7")
        OptionalLong optionalLongWithDefault;

        @ConfigProperty(name = KEY)
        OptionalDouble optionalDouble;

        @ConfigProperty(name = KEY, defaultValue = "9")
        Optional<Integer> optionalIntegerWithDefault;

        @ConfigProperty(name = KEY)
        List<Integer> integerList;

        @ConfigProperty(name = KEY)
        List<String> stringList;

        @ConfigProperty(name = KEY, defaultValue = "x,y,x")
        Set<String> stringSetWithDefault;

        @ConfigProperty(name = KEY)
        NullConverted nullConverted;

        @ConfigProperty(name = KEY, defaultValue = "x")
        NullConverted nullConvertedWithDefault;

        @ConfigProperty(name = KEY, defaultValue = "x")
        NotConvertible notConvertibleWithDefault;
    }
}
