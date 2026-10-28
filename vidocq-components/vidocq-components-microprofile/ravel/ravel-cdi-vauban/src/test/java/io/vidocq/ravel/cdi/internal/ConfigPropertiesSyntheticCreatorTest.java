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

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Annotated;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.InjectionPoint;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Member;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ConfigPropertiesSyntheticCreator — @ConfigProperties beans (MP Config 3.1 §6.4)")
class ConfigPropertiesSyntheticCreatorTest {

    private static final String HOST = "localhost";

    private final ConfigPropertiesSyntheticCreator creator = new ConfigPropertiesSyntheticCreator();

    @AfterEach
    void cleanup() {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        Config cfg = resolver.getConfig(getClass().getClassLoader());
        resolver.releaseConfig(cfg);
    }

    @Test
    void class_level_prefix_applies_when_the_injection_point_has_none() {
        registerConfig(Map.of("server.host", HOST, "server.port", "8080", "server.timeout", "30"));

        Server server = (Server) creator.create(lookupOf(new TestInjectionPoint(Server.class, null, Set.of())), null);

        assertEquals(HOST, server.host);
        assertEquals(8080, server.port);
        assertEquals(30L, server.delay);
    }

    @Test
    void injection_point_prefix_overrides_the_class_level_prefix() {
        registerConfig(Map.of("other.host", "remote", "other.port", "9090"));

        Server server = (Server) creator.create(
                lookupOf(new TestInjectionPoint(Server.class, prefix(OtherPrefix.class), Set.of())), null);

        assertEquals("remote", server.host);
        assertEquals(9090, server.port);
    }

    @Test
    void programmatic_lookup_takes_the_prefix_from_the_qualifier() {
        registerConfig(Map.of("prog.host", "programmatic", "prog.port", "1"));

        Server server = (Server) creator.create(lookupOf(new TestInjectionPoint(
                Server.class, null, Set.of(prefix(ProgPrefix.class)))), null);

        assertEquals("programmatic", server.host);
        assertEquals(1, server.port);
    }

    @Test
    void missing_values_keep_the_default_value_or_the_java_initializer() {
        registerConfig(Map.of("server.host", HOST));

        Server server = (Server) creator.create(lookupOf(new TestInjectionPoint(Server.class, null, Set.of())), null);

        assertEquals(80, server.port);
        assertEquals(15L, server.delay);
    }

    @Test
    void unprefixed_class_reads_plain_names_and_optional_members() {
        registerConfig(Map.of("name", "ravel", "count", "3", "size", "4", "ratio", "0.5",
                "maybe", "12", "untyped", "text"));

        Unprefixed bean = (Unprefixed) creator.create(lookupOf(new TestInjectionPoint(Unprefixed.class, null, Set.of())), null);

        assertEquals("ravel", bean.name);
        assertEquals(OptionalInt.of(3), bean.count);
        assertEquals(OptionalLong.of(4L), bean.size);
        assertEquals(OptionalDouble.of(0.5d), bean.ratio);
        assertEquals(Optional.of(12), bean.maybe);
        assertEquals(Optional.of("text"), bean.untyped);
    }

    @Test
    void absent_optional_members_are_empty() {
        registerConfig(Map.of());

        Unprefixed bean = (Unprefixed) creator.create(lookupOf(new TestInjectionPoint(Unprefixed.class, null, Set.of())), null);

        assertNull(bean.name);
        assertEquals(OptionalInt.empty(), bean.count);
        assertEquals(OptionalLong.empty(), bean.size);
        assertEquals(OptionalDouble.empty(), bean.ratio);
        assertEquals(Optional.empty(), bean.maybe);
    }

    @Test
    void parameterized_injection_type_uses_its_raw_class() {
        registerConfig(Map.of("name", "generic"));
        Type namedOfString = Holder.class.getDeclaredFields()[0].getGenericType();

        Object bean = creator.create(lookupOf(new TestInjectionPoint(namedOfString, null, Set.of())), null);

        assertEquals("generic", assertInstanceOf(Named.class, bean).name);
    }

    @Test
    void type_variable_injection_type_is_rejected() {
        registerConfig(Map.of());
        Type typeVariable = Holder.class.getTypeParameters()[0];
        Instance<Object> lookup = lookupOf(new TestInjectionPoint(typeVariable, null, Set.of()));

        assertThrows(IllegalArgumentException.class, () -> creator.create(lookup, null));
    }

    @Test
    void class_without_no_arg_constructor_cannot_be_created() {
        registerConfig(Map.of());
        Instance<Object> lookup = lookupOf(new TestInjectionPoint(NoDefaultConstructor.class, null, Set.of()));

        assertThrows(IllegalStateException.class, () -> creator.create(lookup, null));
    }

    // -- Helpers -------------------------------------------------------------------------------

    private static ConfigProperties prefix(Class<?> carrier) {
        return carrier.getAnnotation(ConfigProperties.class);
    }

    @ConfigProperties(prefix = "other")
    private static final class OtherPrefix {
    }

    @ConfigProperties(prefix = "prog")
    private static final class ProgPrefix {
    }

    @SuppressWarnings("unchecked")
    private static Instance<Object> lookupOf(InjectionPoint ip) {
        Instance<Object> selected = (Instance<Object>) Proxy.newProxyInstance(
                Instance.class.getClassLoader(), new Class<?>[]{Instance.class},
                (_, method, _) -> switch (method.getName()) {
                    case "get" -> ip;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (Instance<Object>) Proxy.newProxyInstance(
                Instance.class.getClassLoader(), new Class<?>[]{Instance.class},
                (_, method, _) -> switch (method.getName()) {
                    case "select" -> selected;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private void registerConfig(Map<String, String> values) {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        Config custom = resolver.getBuilder()
                .withSources(new TestSource(values))
                .forClassLoader(getClass().getClassLoader())
                .build();
        resolver.registerConfig(custom, getClass().getClassLoader());
    }

    /** Injection point whose annotated element carries {@code fieldAnnotation} (null: no annotated element). */
    private record TestInjectionPoint(Type type, ConfigProperties fieldAnnotation, Set<Annotation> qualifiers)
            implements InjectionPoint {
        @Override public Type getType() { return type; }
        @Override public Set<Annotation> getQualifiers() { return qualifiers; }
        @Override public Bean<?> getBean() { return null; }
        @Override public Member getMember() { return null; }
        @Override public boolean isDelegate() { return false; }
        @Override public boolean isTransient() { return false; }

        @Override
        public Annotated getAnnotated() {
            if (fieldAnnotation == null) {
                return null;
            }
            return new Annotated() {
                @Override public Type getBaseType() { return type; }
                @Override public Set<Type> getTypeClosure() { return Set.of(type); }
                @Override public <T extends Annotation> T getAnnotation(Class<T> annotationType) {
                    return annotationType.isInstance(fieldAnnotation) ? annotationType.cast(fieldAnnotation) : null;
                }
                @Override public Set<Annotation> getAnnotations() { return Set.of(fieldAnnotation); }
                @Override public <T extends Annotation> Set<T> getAnnotations(Class<T> annotationType) {
                    T found = getAnnotation(annotationType);
                    return found == null ? Set.of() : Set.of(found);
                }
                @Override public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
                    return annotationType.isInstance(fieldAnnotation);
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

    @ConfigProperties(prefix = "server")
    static class Server {
        static final String PROTOCOL = "http";

        String host;

        @ConfigProperty(defaultValue = "80")
        int port;

        @ConfigProperty(name = "timeout")
        long delay = 15L;
    }

    @ConfigProperties
    static class Unprefixed {
        String name;
        OptionalInt count;
        OptionalLong size;
        OptionalDouble ratio;
        Optional<Integer> maybe;
        @SuppressWarnings({"rawtypes", "unused"})
        Optional untyped;
    }

    @SuppressWarnings("unused")
    static class Holder<T> {
        Named<String> named;
    }

    @ConfigProperties
    static class Named<T> {
        String name;
    }

    @ConfigProperties
    static class NoDefaultConstructor {
        final String name;

        NoDefaultConstructor(String name) {
            this.name = name;
        }
    }
}
