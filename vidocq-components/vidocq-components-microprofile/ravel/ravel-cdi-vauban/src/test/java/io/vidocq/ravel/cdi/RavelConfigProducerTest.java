/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.cdi;

import jakarta.enterprise.inject.spi.Annotated;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Provider;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("RavelConfigProducer — CDI @ConfigProperty support")
class RavelConfigProducerTest {

    private final RavelConfigProducer producer = new RavelConfigProducer();

    @AfterEach
    void cleanup() {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        Config cfg = resolver.getConfig(getClass().getClassLoader());
        resolver.releaseConfig(cfg);
    }

    @Test
    void produceConfig_returns_current_config() {
        registerConfig(Map.of("app.name", "ravel"));
        Config produced = producer.produceConfig();
        assertEquals("ravel", produced.getValue("app.name", String.class));
    }

    @Test
    void produceConfigProperty_reads_named_property() throws Exception {
        registerConfig(Map.of("app.name", "ravel"));
        InjectionPoint ip = injectionPointFor(BeanFields.class.getDeclaredField("name"));

        Object value = producer.produceConfigProperty(ip);
        assertEquals("ravel", value);
    }

    @Test
    void produceConfigProperty_uses_default_value_when_missing() throws Exception {
        registerConfig(Map.of());
        InjectionPoint ip = injectionPointFor(BeanFields.class.getDeclaredField("nameWithDefault"));

        Object value = producer.produceConfigProperty(ip);
        assertEquals("fallback-name", value);
    }

    @Test
    void produceConfigProperty_converts_default_value_to_target_type() throws Exception {
        registerConfig(Map.of());
        InjectionPoint ip = injectionPointFor(BeanFields.class.getDeclaredField("portWithDefault"));

        Object value = producer.produceConfigProperty(ip);
        assertEquals(8088, value);
    }

    @Test
    void produceConfigProperty_uses_member_name_when_annotation_name_unset() throws Exception {
        registerConfig(Map.of("memberNamed", "ok"));
        InjectionPoint ip = injectionPointFor(BeanFields.class.getDeclaredField("memberNamed"));

        Object value = producer.produceConfigProperty(ip);
        assertEquals("ok", value);
    }

    @Test
    void produceConfigProperty_supports_optional() throws Exception {
        registerConfig(Map.of("app.port", "8080"));
        InjectionPoint ip = injectionPointFor(BeanFields.class.getDeclaredField("optionalPort"));

        Object value = producer.produceConfigProperty(ip);
        assertInstanceOf(Optional.class, value);
        assertEquals(Optional.of(8080), value);
    }

    @Test
    void produceConfigProperty_supports_provider_and_supplier() throws Exception {
        registerConfig(Map.of("app.port", "9090"));

        InjectionPoint ipProvider = injectionPointFor(BeanFields.class.getDeclaredField("providerPort"));
        Object provided = producer.produceConfigProperty(ipProvider);
        assertInstanceOf(Provider.class, provided);
        assertEquals(9090, ((Provider<?>) provided).get());

        InjectionPoint ipSupplier = injectionPointFor(BeanFields.class.getDeclaredField("supplierPort"));
        Object supplied = producer.produceConfigProperty(ipSupplier);
        assertInstanceOf(Supplier.class, supplied);
        assertEquals(9090, ((Supplier<?>) supplied).get());
    }

    @Test
    void produceConfigProperty_provider_and_supplier_are_dynamic() throws Exception {
        registerConfig(Map.of("app.port", "9090"));

        InjectionPoint ipProvider = injectionPointFor(BeanFields.class.getDeclaredField("providerPort"));
        @SuppressWarnings("unchecked")
        Provider<Integer> provider = (Provider<Integer>) producer.produceConfigProperty(ipProvider);

        InjectionPoint ipSupplier = injectionPointFor(BeanFields.class.getDeclaredField("supplierPort"));
        @SuppressWarnings("unchecked")
        Supplier<Integer> supplier = (Supplier<Integer>) producer.produceConfigProperty(ipSupplier);

        assertEquals(9090, provider.get());
        assertEquals(9090, supplier.get());

        // Changement de Config enregistré : Provider/Supplier doivent refléter la valeur au prochain get().
        registerConfig(Map.of("app.port", "9191"));

        assertEquals(9191, provider.get());
        assertEquals(9191, supplier.get());
    }

    @Test
    void produceConfigProperty_missing_required_throws_deployment_exception() throws Exception {
        registerConfig(Map.of());
        InjectionPoint ip = injectionPointFor(BeanFields.class.getDeclaredField("requiredPort"));

        assertThrows(DeploymentException.class, () -> producer.produceConfigProperty(ip));
    }

    @Test
    void produceConfigProperty_missing_required_provider_throws_on_get() throws Exception {
        registerConfig(Map.of());
        InjectionPoint ip = injectionPointFor(BeanFields.class.getDeclaredField("requiredProviderPort"));

        @SuppressWarnings("unchecked")
        Provider<Integer> provider = (Provider<Integer>) producer.produceConfigProperty(ip);
        assertThrows(DeploymentException.class, provider::get);
    }

    @Test
    void produceConfigProperty_missing_required_supplier_throws_on_get() throws Exception {
        registerConfig(Map.of());
        InjectionPoint ip = injectionPointFor(BeanFields.class.getDeclaredField("requiredSupplierPort"));

        @SuppressWarnings("unchecked")
        Supplier<Integer> supplier = (Supplier<Integer>) producer.produceConfigProperty(ip);
        assertThrows(DeploymentException.class, supplier::get);
    }

    private void registerConfig(Map<String, String> values) {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        Config custom = resolver.getBuilder()
                .withSources(new TestSource(values))
                .forClassLoader(getClass().getClassLoader())
                .build();
        resolver.registerConfig(custom, getClass().getClassLoader());
    }

    private static InjectionPoint injectionPointFor(Field field) {
        return new InjectionPoint() {
            @Override
            public Type getType() {
                return field.getGenericType();
            }

            @Override
            public Set<Annotation> getQualifiers() {
                return Set.copyOf(Set.of(field.getAnnotation(ConfigProperty.class)));
            }

            @Override
            public Bean<?> getBean() {
                return null;
            }

            @Override
            public Member getMember() {
                return field;
            }

            @Override
            public Annotated getAnnotated() {
                return new Annotated() {
                    @Override
                    public Type getBaseType() {
                        return field.getGenericType();
                    }

                    @Override
                    public Set<Type> getTypeClosure() {
                        return Set.of(field.getGenericType());
                    }

                    @Override
                    public <T extends Annotation> T getAnnotation(Class<T> annotationType) {
                        return field.getAnnotation(annotationType);
                    }

                    @Override
                    public Set<Annotation> getAnnotations() {
                        return Arrays.stream(field.getAnnotations()).collect(Collectors.toSet());
                    }

                    @Override
                    public <T extends Annotation> Set<T> getAnnotations(Class<T> annotationType) {
                        return Arrays.stream(field.getAnnotationsByType(annotationType)).collect(Collectors.toSet());
                    }

                    @Override
                    public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
                        return field.isAnnotationPresent(annotationType);
                    }
                };
            }

            @Override
            public boolean isDelegate() {
                return false;
            }

            @Override
            public boolean isTransient() {
                return false;
            }
        };
    }

    private static final class TestSource implements ConfigSource {
        private final Map<String, String> values;

        private TestSource(Map<String, String> values) {
            this.values = Map.copyOf(values);
        }

        @Override
        public Map<String, String> getProperties() {
            return values;
        }

        @Override
        public Set<String> getPropertyNames() {
            return values.keySet();
        }

        @Override
        public String getValue(String propertyName) {
            return values.get(propertyName);
        }

        @Override
        public String getName() {
            return "test-source";
        }

        @Override
        public int getOrdinal() {
            return 1000;
        }
    }

    static final class BeanFields {
        @ConfigProperty(name = "app.name")
        String name;

        @ConfigProperty(name = "app.name", defaultValue = "fallback-name")
        String nameWithDefault;

        @ConfigProperty(name = "app.port.default", defaultValue = "8088")
        Integer portWithDefault;

        @ConfigProperty
        String memberNamed;

        @ConfigProperty(name = "app.port")
        Optional<Integer> optionalPort;

        @ConfigProperty(name = "app.port")
        Provider<Integer> providerPort;

        @ConfigProperty(name = "app.port")
        Supplier<Integer> supplierPort;

        @ConfigProperty(name = "app.required.port")
        Integer requiredPort;

        @ConfigProperty(name = "app.required.provider.port")
        Provider<Integer> requiredProviderPort;

        @ConfigProperty(name = "app.required.supplier.port")
        Supplier<Integer> requiredSupplierPort;
    }
}




