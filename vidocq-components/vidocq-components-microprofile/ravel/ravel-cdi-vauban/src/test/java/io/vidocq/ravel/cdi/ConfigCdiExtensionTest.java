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
package io.vidocq.ravel.cdi;

import io.vidocq.ravel.cdi.internal.ConfigStartCheck;
import io.vidocq.vauban.api.ExtensionPhase;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserverBuilder;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.DeclarationInfo;
import jakarta.enterprise.lang.model.declarations.FieldInfo;
import jakarta.enterprise.lang.model.types.ClassType;
import jakarta.enterprise.lang.model.types.ParameterizedType;
import jakarta.enterprise.lang.model.types.Type;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ConfigCdiExtension — validation BCE @ConfigProperty")
class ConfigCdiExtensionTest {

    @Test
    void resolvePropertyName_uses_configured_name() {
        AnnotationInfo cfg = configPropertyAnnotation("app.name", ConfigProperty.UNCONFIGURED_VALUE);
        DeclarationInfo decl = fieldDeclaration("memberName");

        assertEquals("app.name", ConfigCdiExtension.resolvePropertyName(cfg, decl));
    }

    @Test
    void resolvePropertyName_falls_back_to_FQN_member_when_unconfigured() {
        // Spec MP Config 3.1 §6.1 : par défaut, le nom est le FQN (canonical name)
        // de la classe déclarante + "." + nom du membre.
        AnnotationInfo cfg = configPropertyAnnotation("", ConfigProperty.UNCONFIGURED_VALUE);
        DeclarationInfo decl = fieldDeclaration("memberName");

        assertEquals("com.example.MyBean.memberName",
                ConfigCdiExtension.resolvePropertyName(cfg, decl));
    }

    @Test
    void hasConfiguredDefault_detects_presence() {
        assertTrue(ConfigCdiExtension.hasConfiguredDefault(
                configPropertyAnnotation("app.name", "fallback")));
        assertFalse(ConfigCdiExtension.hasConfiguredDefault(
                configPropertyAnnotation("app.name", ConfigProperty.UNCONFIGURED_VALUE)));
    }

    @Test
    void supportedTypes_include_class_optional_provider_supplier() {
        assertTrue(ConfigCdiExtension.isSupportedType(classType("java.lang.String")));
        assertTrue(ConfigCdiExtension.isSupportedType(parameterizedType("java.util.Optional", classType("java.lang.Integer"))));
        assertTrue(ConfigCdiExtension.isSupportedType(parameterizedType("jakarta.inject.Provider", classType("java.lang.Integer"))));
        assertTrue(ConfigCdiExtension.isSupportedType(parameterizedType("java.util.function.Supplier", classType("java.lang.Integer"))));
    }

    @Test
    void supportedType_accepts_generic_parameterized_types() {
        // MP Config 3.1 §6.1: any type with a Converter can be injected. Deployment
        // validation stays conservative and accepts non-wrapper parameterized types
        // (Set of Class, List of String, ...); a missing Converter shows up at runtime.
        Type setOfClass = parameterizedType("java.util.Set", classType("java.lang.Class"));
        Type optionalOfNested = parameterizedType("java.util.Optional",
                parameterizedType("java.util.List", classType("java.lang.String")));

        assertTrue(ConfigCdiExtension.isSupportedType(setOfClass));
        // An Optional of a List of String passes too: the element conversion is
        // resolved dynamically by the Converter, the wrapper does not restrict it.
        assertTrue(ConfigCdiExtension.isSupportedType(optionalOfNested));
    }

    // -- ravel#50: the extension reads no configuration; it hands what must be checked to the start --

    @Test
    void a_missing_required_property_is_handed_to_the_start_check_not_reported() {
        String key = "cdi.test.missing.required." + System.nanoTime();
        ConfigCdiExtension extension = new ConfigCdiExtension();
        CapturingMessages messages = new CapturingMessages();
        Recorded recorded = new Recorded();

        extension.registerConfigPropertyInjectionPoints(requiredStringBean(key), messages);
        extension.synthesizeConfigPropertyBeans(recordingComponents(recorded), null);

        assertEquals(List.of(), messages.errors);
        assertEquals(List.of(Startup.class), recorded.observedTypes);
        assertEquals(List.of(ConfigStartCheck.class), recorded.observerClasses);
        assertEquals(ConfigStartCheck.PRIORITY, recorded.priority);
        assertArrayEquals(new String[]{key}, recorded.strings(ConfigStartCheck.PROPERTY_KEYS));
        assertArrayEquals(new String[]{"java.lang.String"}, recorded.strings(ConfigStartCheck.PROPERTY_TYPES));
        assertArrayEquals(new String[]{ConfigStartCheck.NO_DEFAULT},
                recorded.strings(ConfigStartCheck.PROPERTY_DEFAULTS));
        assertArrayEquals(new String[]{"com.example.MyBean#requiredValue"},
                recorded.strings(ConfigStartCheck.PROPERTY_SITES));
        assertArrayEquals(new String[0], recorded.strings(ConfigStartCheck.CLASS_NAMES));
    }

    @Test
    void the_build_hands_the_same_required_property_to_the_start_check() {
        // The configuration of the build machine is not the one the application starts with (ravel#21):
        // the build only records what the start must check.
        String key = "cdi.test.missing.at.build." + System.nanoTime();
        ConfigCdiExtension extension = new ConfigCdiExtension();
        CapturingMessages messages = new CapturingMessages();
        Recorded recorded = new Recorded();

        ExtensionPhase.atBuildTime(() -> {
            extension.registerConfigPropertyInjectionPoints(requiredStringBean(key), messages);
            extension.synthesizeConfigPropertyBeans(recordingComponents(recorded), null);
            return null;
        });

        assertEquals(List.of(), messages.errors);
        assertEquals(List.of(Startup.class), recorded.observedTypes);
        assertArrayEquals(new String[]{key}, recorded.strings(ConfigStartCheck.PROPERTY_KEYS));
        assertArrayEquals(new String[]{"com.example.MyBean#requiredValue"},
                recorded.strings(ConfigStartCheck.PROPERTY_SITES));
    }

    @Test
    void the_data_recorded_at_build_fails_the_start_on_a_missing_property_and_passes_once_it_is_there() {
        // The path Vauban takes once extensions run at build only (Vidocq/vauban#288): the parameters
        // the build recorded reach the observer at start, and the extension does not run again.
        String key = "cdi.test.frozen." + System.nanoTime();
        ConfigCdiExtension extension = new ConfigCdiExtension();
        Recorded recorded = new Recorded();
        ExtensionPhase.atBuildTime(() -> {
            extension.registerConfigPropertyInjectionPoints(requiredStringBean(key), new CapturingMessages());
            extension.synthesizeConfigPropertyBeans(recordingComponents(recorded), null);
            return null;
        });
        Parameters frozen = recorded.parameters();
        ConfigStartCheck startCheck = new ConfigStartCheck();

        registerConfig(Map.of());
        DeploymentException failure = assertThrows(DeploymentException.class, () -> startCheck.observe(null, frozen));
        assertTrue(failure.getMessage().contains("Missing required config property '" + key
                + "' for type java.lang.String [at com.example.MyBean#requiredValue]"), failure.getMessage());

        registerConfig(Map.of(key, "now-there"));
        assertDoesNotThrow(() -> startCheck.observe(null, frozen));
    }

    @Test
    void ConfigProperties_injection_points_are_handed_to_the_start_check_not_reported() {
        InjectionPointInfo ip = injectionPoint(classType(MissingServer.class.getName()),
                List.of(configPropertiesAnnotation(ConfigProperties.UNCONFIGURED_PREFIX)), fieldDeclaration("server"));
        ConfigCdiExtension extension = new ConfigCdiExtension();
        CapturingMessages messages = new CapturingMessages();
        Recorded recorded = new Recorded();

        ExtensionPhase.atBuildTime(() -> {
            extension.registerConfigPropertyInjectionPoints(beanWithInjectionPoints(List.of(ip)), messages);
            extension.synthesizeConfigPropertyBeans(recordingComponents(recorded), null);
            return null;
        });

        assertEquals(List.of(), messages.errors);
        assertArrayEquals(new String[]{MissingServer.class.getName()},
                recorded.strings(ConfigStartCheck.CLASS_NAMES));
        assertArrayEquals(new String[]{ConfigProperties.UNCONFIGURED_PREFIX},
                recorded.strings(ConfigStartCheck.CLASS_PREFIXES));
        assertArrayEquals(new String[]{"com.example.MyBean#server"}, recorded.strings(ConfigStartCheck.CLASS_SITES));

        Parameters frozen = recorded.parameters();
        ConfigStartCheck startCheck = new ConfigStartCheck();
        DeploymentException failure = assertThrows(DeploymentException.class, () -> startCheck.observe(null, frozen));
        assertTrue(failure.getMessage().contains("@ConfigProperties: missing required property"
                + " 'cdi.test.missing.server.host' for " + MissingServer.class.getName()
                + ".host [at com.example.MyBean#server]"), failure.getMessage());
    }

    @Test
    void synthesis_adds_the_fallback_Config_bean_when_the_deployment_has_none() {
        Recorded recorded = new Recorded();

        new ConfigCdiExtension().synthesizeConfigPropertyBeans(recordingComponents(recorded), null);

        assertEquals(List.of(org.eclipse.microprofile.config.Config.class), recorded.beans);
    }

    @Test
    void synthesis_leaves_the_fallback_Config_bean_to_the_container_start_at_build_time() {
        // ravel#21: the compilation does not see RavelConfigProducer, which ships next to this
        // extension — a fallback frozen at build time made every @Inject Config ambiguous at start.
        Recorded recorded = new Recorded();

        ExtensionPhase.atBuildTime(() -> {
            new ConfigCdiExtension().synthesizeConfigPropertyBeans(recordingComponents(recorded), null);
            return null;
        });

        assertEquals(List.of(), recorded.beans);
    }

    @Test
    void synthesis_registers_no_start_check_when_there_is_nothing_to_check() {
        String key = "cdi.test.missing.optional." + System.nanoTime();
        AnnotationInfo cfg = configPropertyAnnotation(key, ConfigProperty.UNCONFIGURED_VALUE);
        InjectionPointInfo ip = injectionPoint(
                parameterizedType("java.util.Optional", classType("java.lang.Integer")),
                List.of(cfg),
                fieldDeclaration("optionalValue"));
        ConfigCdiExtension extension = new ConfigCdiExtension();
        CapturingMessages messages = new CapturingMessages();
        Recorded recorded = new Recorded();

        extension.registerConfigPropertyInjectionPoints(beanWithInjectionPoints(List.of(ip)), messages);
        extension.synthesizeConfigPropertyBeans(recordingComponents(recorded), null);

        assertTrue(messages.errors.isEmpty());
        assertEquals(List.of(), recorded.observedTypes);
    }

    /** Replaces the configuration of this class loader; {@link #releaseConfig()} drops it after the test. */
    private void registerConfig(Map<String, String> values) {
        releaseConfig();
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        resolver.registerConfig(resolver.getBuilder()
                .withSources(new TestSource(values))
                .forClassLoader(getClass().getClassLoader())
                .build(), getClass().getClassLoader());
    }

    @AfterEach
    void releaseConfig() {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        resolver.releaseConfig(resolver.getConfig(getClass().getClassLoader()));
    }

    private record TestSource(Map<String, String> values) implements ConfigSource {
        @Override public Map<String, String> getProperties() { return values; }
        @Override public java.util.Set<String> getPropertyNames() { return values.keySet(); }
        @Override public String getValue(String propertyName) { return values.get(propertyName); }
        @Override public String getName() { return "extension-test-source"; }
        @Override public int getOrdinal() { return 1000; }
    }

    private static BeanInfo requiredStringBean(String key) {
        AnnotationInfo cfg = configPropertyAnnotation(key, ConfigProperty.UNCONFIGURED_VALUE);
        InjectionPointInfo ip = injectionPoint(classType("java.lang.String"), List.of(cfg),
                fieldDeclaration("requiredValue"));
        return beanWithInjectionPoints(List.of(ip));
    }

    /** What a {@code @Synthesis} method registered: the beans, and the observers with their builder calls. */
    private static final class Recorded {
        private final List<Object> beans = new ArrayList<>();
        private final List<Object> observedTypes = new ArrayList<>();
        private final List<Object> observerClasses = new ArrayList<>();
        private final Map<String, Object> params = new HashMap<>();
        private Object priority;

        String[] strings(String param) {
            return (String[]) params.get(param);
        }

        /** The recorded parameters as the container hands them to the synthetic observer. */
        Parameters parameters() {
            return new Parameters() {
                @Override public <T> T get(String key, Class<T> type) { return type.cast(params.get(key)); }

                @Override public <T> T get(String key, Class<T> type, T defaultValue) {
                    T value = get(key, type);
                    return value != null ? value : defaultValue;
                }
            };
        }
    }

    /** Records what is added; each builder call returns the builder. */
    private static SyntheticComponents recordingComponents(Recorded recorded) {
        Object beanBuilder = Proxy.newProxyInstance(
                ConfigCdiExtensionTest.class.getClassLoader(),
                new Class[]{jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanBuilder.class},
                (proxy, method, args) -> proxy);
        Object observerBuilder = Proxy.newProxyInstance(
                ConfigCdiExtensionTest.class.getClassLoader(),
                new Class[]{SyntheticObserverBuilder.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "withParam" -> recorded.params.put((String) args[0], args[1]);
                        case "priority" -> recorded.priority = args[0];
                        case "observeWith" -> recorded.observerClasses.add(args[0]);
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                    return proxy;
                });
        return (SyntheticComponents) Proxy.newProxyInstance(
                ConfigCdiExtensionTest.class.getClassLoader(),
                new Class[]{SyntheticComponents.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "addBean" -> {
                        recorded.beans.add(args[0]);
                        yield beanBuilder;
                    }
                    case "addObserver" -> {
                        recorded.observedTypes.add(args[0]);
                        yield observerBuilder;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A {@code @ConfigProperties} class whose one member no configuration source provides. */
    @ConfigProperties(prefix = "cdi.test.missing.server")
    static class MissingServer {
        String host;
    }

    @Test
    void findConfigPropertyQualifier_returns_annotation_when_present() {
        AnnotationInfo cfg = configPropertyAnnotation("app.name", ConfigProperty.UNCONFIGURED_VALUE);
        InjectionPointInfo ip = injectionPoint(classType("java.lang.String"), List.of(cfg), fieldDeclaration("x"));

        AnnotationInfo found = ConfigCdiExtension.findConfigPropertyQualifier(ip);
        assertNotNull(found);
        assertEquals(ConfigProperty.class.getName(), found.name());
    }

    private static InjectionPointInfo injectionPoint(Type type, Collection<AnnotationInfo> qualifiers, DeclarationInfo declaration) {
        return (InjectionPointInfo) Proxy.newProxyInstance(
                InjectionPointInfo.class.getClassLoader(),
                new Class[]{InjectionPointInfo.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "type" -> type;
                    case "qualifiers" -> qualifiers;
                    case "declaration" -> declaration;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static BeanInfo beanWithInjectionPoints(Collection<InjectionPointInfo> injectionPoints) {
        return (BeanInfo) Proxy.newProxyInstance(
                BeanInfo.class.getClassLoader(),
                new Class[]{BeanInfo.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "injectionPoints" -> injectionPoints;
                    case "isClassBean" -> true;
                    case "isProducerMethod", "isProducerField", "isSynthetic", "isAlternative" -> false;
                    case "name" -> "testBean";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static DeclarationInfo fieldDeclaration(String name) {
        var declaringClass = (jakarta.enterprise.lang.model.declarations.ClassInfo) Proxy.newProxyInstance(
                jakarta.enterprise.lang.model.declarations.ClassInfo.class.getClassLoader(),
                new Class[]{jakarta.enterprise.lang.model.declarations.ClassInfo.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "name" -> "com.example.MyBean";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        FieldInfo field = (FieldInfo) Proxy.newProxyInstance(
                FieldInfo.class.getClassLoader(),
                new Class[]{FieldInfo.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "name" -> name;
                    case "kind" -> DeclarationInfo.Kind.FIELD;
                    case "asField" -> proxy;
                    case "declaringClass" -> declaringClass;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        return (DeclarationInfo) field;
    }

    private static AnnotationInfo configPropertyAnnotation(String name, String defaultValue) {
        Map<String, AnnotationMember> members = Map.of(
                "name", stringMember(name),
                "defaultValue", stringMember(defaultValue)
        );

        return (AnnotationInfo) Proxy.newProxyInstance(
                AnnotationInfo.class.getClassLoader(),
                new Class[]{AnnotationInfo.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "name" -> ConfigProperty.class.getName();
                    case "hasMember" -> members.containsKey((String) args[0]);
                    case "member" -> members.get((String) args[0]);
                    case "members" -> members;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static AnnotationInfo configPropertiesAnnotation(String prefix) {
        Map<String, AnnotationMember> members = Map.of("prefix", stringMember(prefix));

        return (AnnotationInfo) Proxy.newProxyInstance(
                AnnotationInfo.class.getClassLoader(),
                new Class[]{AnnotationInfo.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "name" -> ConfigProperties.class.getName();
                    case "hasMember" -> members.containsKey((String) args[0]);
                    case "member" -> members.get((String) args[0]);
                    case "members" -> members;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static AnnotationMember stringMember(String value) {
        return (AnnotationMember) Proxy.newProxyInstance(
                AnnotationMember.class.getClassLoader(),
                new Class[]{AnnotationMember.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isString" -> true;
                    case "asString" -> value;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ClassType classType(String fqcn) {
        ClassInfo declaration = (ClassInfo) Proxy.newProxyInstance(
                ClassInfo.class.getClassLoader(),
                new Class[]{ClassInfo.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "name" -> fqcn;
                    case "toString" -> fqcn;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        return (ClassType) Proxy.newProxyInstance(
                ClassType.class.getClassLoader(),
                new Class[]{ClassType.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "declaration" -> declaration;
                    case "toString" -> fqcn;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ParameterizedType parameterizedType(String rawFqcn, Type arg) {
        ClassType raw = classType(rawFqcn);
        return (ParameterizedType) Proxy.newProxyInstance(
                ParameterizedType.class.getClassLoader(),
                new Class[]{ParameterizedType.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "genericClass" -> raw;
                    case "typeArguments" -> List.of(arg);
                    case "toString" -> rawFqcn + "<" + arg + ">";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class CapturingMessages implements Messages {
        private final List<String> errors = new ArrayList<>();

        @Override public void info(String message) { /* only errors are captured */ }
        @Override public void info(String message, jakarta.enterprise.lang.model.AnnotationTarget target) { /* only errors are captured */ }
        @Override public void info(String message, BeanInfo bean) { /* only errors are captured */ }
        @Override public void info(String message, jakarta.enterprise.inject.build.compatible.spi.ObserverInfo observer) { /* only errors are captured */ }
        @Override public void warn(String message) { /* only errors are captured */ }
        @Override public void warn(String message, jakarta.enterprise.lang.model.AnnotationTarget target) { /* only errors are captured */ }
        @Override public void warn(String message, BeanInfo bean) { /* only errors are captured */ }
        @Override public void warn(String message, jakarta.enterprise.inject.build.compatible.spi.ObserverInfo observer) { /* only errors are captured */ }
        @Override public void error(String message) { errors.add(message); }
        @Override public void error(String message, jakarta.enterprise.lang.model.AnnotationTarget target) { errors.add(message); }
        @Override public void error(String message, BeanInfo bean) { errors.add(message); }
        @Override public void error(String message, jakarta.enterprise.inject.build.compatible.spi.ObserverInfo observer) { errors.add(message); }
        @Override public void error(Exception exception) { errors.add(exception.getMessage()); }
    }
}

