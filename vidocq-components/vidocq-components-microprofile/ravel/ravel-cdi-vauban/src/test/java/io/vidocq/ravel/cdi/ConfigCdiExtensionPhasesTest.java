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

import io.vidocq.ravel.cdi.internal.ConfigPropertiesSyntheticCreator;
import io.vidocq.ravel.cdi.internal.ConfigStartCheck;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.Vetoed;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.ObserverInfo;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanBuilder;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserverBuilder;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.AnnotationTarget;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.DeclarationInfo;
import jakarta.enterprise.lang.model.types.ArrayType;
import jakarta.enterprise.lang.model.types.ClassType;
import jakarta.enterprise.lang.model.types.ParameterizedType;
import jakarta.enterprise.lang.model.types.PrimitiveType;
import jakarta.enterprise.lang.model.types.PrimitiveType.PrimitiveKind;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ConfigCdiExtension — enhancement, registration and synthesis phases")
class ConfigCdiExtensionPhasesTest {

    private static final String KEY = "phases.key";
    private static final String STRING = "java.lang.String";
    private static final String INTEGER = "java.lang.Integer";
    private static final String MISSING_CLASS = "com.example.DoesNotExist";
    private static final String LIST = "java.util.List";
    private static final String PREFIX = "prefix";
    private static final String ADD_BEAN = "addBean";
    private static final String ADD_OBSERVER = "addObserver";
    private static final String WITH_PARAM = "withParam";
    private static final String REQUIRED_PREFIX = "required";
    private static final String PORT = "port";

    @AfterEach
    void cleanup() {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        Config cfg = resolver.getConfig(getClass().getClassLoader());
        resolver.releaseConfig(cfg);
    }

    // -- @Enhancement + @Validation --------------------------------------------------------------

    @Test
    void enhancement_vetoes_type_level_ConfigProperties_classes() {
        List<Class<?>> added = new ArrayList<>();
        ConfigCdiExtension extension = new ConfigCdiExtension();

        extension.vetoConfigPropertiesClasses(classConfig(Required.class.getName(), true, added));
        extension.vetoConfigPropertiesClasses(classConfig(String.class.getName(), false, added));

        assertEquals(List.of(Vetoed.class), added);
    }

    @Test
    void vetoed_classes_are_handed_to_the_start_check_under_their_own_prefix() {
        // MP Config 3.1 §6.4: a discovered class is checked even when nothing injects it. The class
        // prefix is read at start, on the loaded class; no injection site.
        ConfigCdiExtension extension = new ConfigCdiExtension();
        extension.vetoConfigPropertiesClasses(classConfig(Required.class.getName(), true, new ArrayList<>()));
        extension.vetoConfigPropertiesClasses(classConfig(MISSING_CLASS, true, new ArrayList<>()));
        extension.vetoConfigPropertiesClasses(classConfig(String.class.getName(), false, new ArrayList<>()));

        Map<String, Object> params = startCheckParams(extension);

        assertArrayEquals(new String[]{Required.class.getName(), MISSING_CLASS},
                (String[]) params.get(ConfigStartCheck.CLASS_NAMES));
        assertArrayEquals(new String[]{ConfigProperties.UNCONFIGURED_PREFIX, ConfigProperties.UNCONFIGURED_PREFIX},
                (String[]) params.get(ConfigStartCheck.CLASS_PREFIXES));
        assertArrayEquals(new String[]{"", ""}, (String[]) params.get(ConfigStartCheck.CLASS_SITES));
    }

    @Test
    void injected_classes_come_before_the_vetoed_ones() {
        // The start check skips a discovered class an injection point already covered: it reads the
        // injection points first.
        ConfigCdiExtension extension = new ConfigCdiExtension();
        extension.vetoConfigPropertiesClasses(classConfig(Required.class.getName(), true, new ArrayList<>()));
        extension.registerConfigPropertyInjectionPoints(bean(List.of(injectionPoint(
                classType(Required.class.getName()), List.of(configPropertiesAnnotation(Map.of())),
                fieldDeclaration(REQUIRED_PREFIX)))), new CapturingMessages());

        Map<String, Object> params = startCheckParams(extension);

        assertArrayEquals(new String[]{Required.class.getName(), Required.class.getName()},
                (String[]) params.get(ConfigStartCheck.CLASS_NAMES));
        assertArrayEquals(new String[]{"com.example.Bean#required", ""},
                (String[]) params.get(ConfigStartCheck.CLASS_SITES));
    }

    // -- @Registration: @ConfigProperties injection points ---------------------------------------

    @Test
    void ConfigProperties_injection_point_must_be_a_class_type() {
        CapturingMessages messages = register(injectionPoint(
                parameterizedType(LIST, classType(STRING)),
                List.of(configPropertiesAnnotation(Map.of())), fieldDeclaration("list")));

        assertEquals(1, messages.errors.size());
        assertTrue(messages.errors.getFirst().contains("only supports class types"));
    }

    @Test
    void ConfigProperties_injection_point_prefix_is_handed_over_as_written() {
        ConfigCdiExtension extension = new ConfigCdiExtension();
        CapturingMessages messages = new CapturingMessages();
        extension.registerConfigPropertyInjectionPoints(bean(List.of(
                injectionPoint(classType(Required.class.getName()),
                        List.of(configPropertiesAnnotation(Map.of(PREFIX, stringMember("override")))),
                        fieldDeclaration(REQUIRED_PREFIX)),
                injectionPoint(classType(MISSING_CLASS),
                        List.of(configPropertiesAnnotation(Map.of())), fieldDeclaration("missing")))), messages);

        Map<String, Object> params = startCheckParams(extension);

        assertEquals(List.of(), messages.errors);
        assertArrayEquals(new String[]{Required.class.getName(), MISSING_CLASS},
                (String[]) params.get(ConfigStartCheck.CLASS_NAMES));
        assertArrayEquals(new String[]{"override", ConfigProperties.UNCONFIGURED_PREFIX},
                (String[]) params.get(ConfigStartCheck.CLASS_PREFIXES));
        assertArrayEquals(new String[]{"com.example.Bean#required", "com.example.Bean#missing"},
                (String[]) params.get(ConfigStartCheck.CLASS_SITES));
    }

    @Test
    void injection_point_without_config_qualifier_is_ignored() {
        ConfigCdiExtension extension = new ConfigCdiExtension();
        CapturingMessages messages = new CapturingMessages();
        extension.registerConfigPropertyInjectionPoints(
                bean(List.of(injectionPoint(classType(STRING), List.of(), fieldDeclaration("plain")))), messages);

        assertEquals(List.of(), messages.errors);
        assertEquals(Map.of(), startCheckParams(extension));
    }

    // -- @Registration: @ConfigProperty injection points -----------------------------------------

    @Test
    void unsupported_ConfigProperty_injection_type_is_reported() {
        CapturingMessages messages = register(injectionPoint(typeVariable(),
                List.of(configProperty(KEY, null)), fieldDeclaration("generic")));

        assertEquals(1, messages.errors.size());
        assertTrue(messages.errors.getFirst().startsWith("Unsupported @ConfigProperty injection type"));
    }

    @Test
    void registration_reads_no_configuration() {
        // ravel#50: a value that does not convert, a default no converter takes — the start reports
        // them (ConfigStartCheckTest), the extension only records the injection points.
        registerConfig(Map.of(KEY, "not-a-number"));

        CapturingMessages messages = register(
                injectionPoint(classType(INTEGER), List.of(configProperty(KEY, null)), fieldDeclaration("number")),
                injectionPoint(classType(STRING), List.of(configProperty("absent", "")), fieldDeclaration("empty")),
                injectionPoint(classType(NotConvertible.class.getName()), List.of(configProperty("absent", "x")),
                        fieldDeclaration("notConvertible")));

        assertEquals(List.of(), messages.errors);
    }

    @Test
    void only_scalar_injection_points_are_handed_to_the_start_check() {
        ConfigCdiExtension extension = new ConfigCdiExtension();
        CapturingMessages messages = new CapturingMessages();
        extension.registerConfigPropertyInjectionPoints(bean(List.of(
                injectionPoint(classType(INTEGER), List.of(configProperty(KEY, "5")), fieldDeclaration("a")),
                injectionPoint(classType("org.eclipse.microprofile.config.ConfigValue"),
                        List.of(configProperty(KEY, null)), fieldDeclaration("b")),
                injectionPoint(classType(OptionalInt.class.getName()), List.of(configProperty(KEY, null)),
                        fieldDeclaration("c")),
                injectionPoint(classType("java.util.OptionalLong"), List.of(configProperty(KEY, null)),
                        fieldDeclaration("c2")),
                injectionPoint(classType("java.util.OptionalDouble"), List.of(configProperty(KEY, null)),
                        fieldDeclaration("c3")),
                injectionPoint(arrayType(classType(STRING)), List.of(configProperty(KEY, null)),
                        fieldDeclaration("d")),
                injectionPoint(parameterizedType(LIST, classType(STRING)),
                        List.of(configProperty(KEY, null)), fieldDeclaration("e")),
                injectionPoint(parameterizedType("java.util.Optional", classType(STRING)),
                        List.of(configProperty(KEY, null)), fieldDeclaration("e2")),
                injectionPoint(classType(MISSING_CLASS), List.of(configProperty("other", null)),
                        fieldDeclaration("f")))), messages);

        Map<String, Object> params = startCheckParams(extension);

        assertEquals(List.of(), messages.errors);
        assertArrayEquals(new String[]{KEY, "other"}, (String[]) params.get(ConfigStartCheck.PROPERTY_KEYS));
        assertArrayEquals(new String[]{INTEGER, MISSING_CLASS},
                (String[]) params.get(ConfigStartCheck.PROPERTY_TYPES));
        assertArrayEquals(new String[]{"5", ConfigStartCheck.NO_DEFAULT},
                (String[]) params.get(ConfigStartCheck.PROPERTY_DEFAULTS));
        assertArrayEquals(new String[]{"com.example.Bean#a", "com.example.Bean#f"},
                (String[]) params.get(ConfigStartCheck.PROPERTY_SITES));
    }

    @Test
    void every_primitive_kind_is_handed_over_by_its_name() {
        ConfigCdiExtension extension = new ConfigCdiExtension();
        List<InjectionPointInfo> points = new ArrayList<>();
        for (PrimitiveKind kind : PrimitiveKind.values()) {
            points.add(injectionPoint(primitiveType(kind), List.of(configProperty("prim." + kind, null)),
                    fieldDeclaration(kind.name())));
        }
        extension.registerConfigPropertyInjectionPoints(bean(points), new CapturingMessages());

        Map<String, Object> params = startCheckParams(extension);

        assertArrayEquals(new String[]{"boolean", "byte", "short", "int", "long", "float", "double", "char"},
                (String[]) params.get(ConfigStartCheck.PROPERTY_TYPES));
    }

    @Test
    void default_name_of_a_parameter_injection_point_is_its_canonical_member_name() {
        ConfigCdiExtension extension = new ConfigCdiExtension();
        extension.registerConfigPropertyInjectionPoints(bean(List.of(
                injectionPoint(classType(STRING), List.of(configProperty(null, null)),
                        parameterDeclaration("com.example.Outer$Inner", "configure", false, "param")),
                injectionPoint(classType(STRING), List.of(configProperty(null, null)),
                        parameterDeclaration("com.example.Outer", "com.example.Outer", true, "value")))),
                new CapturingMessages());

        Map<String, Object> params = startCheckParams(extension);

        assertArrayEquals(new String[]{"com.example.Outer.Inner.param", "com.example.Outer.value"},
                (String[]) params.get(ConfigStartCheck.PROPERTY_KEYS));
        assertArrayEquals(new String[]{"com.example.Outer$Inner#configure(param)", "com.example.Outer#<init>(value)"},
                (String[]) params.get(ConfigStartCheck.PROPERTY_SITES));
    }

    @Test
    void injection_site_of_another_declaration_kind_is_its_text() {
        ConfigCdiExtension extension = new ConfigCdiExtension();
        extension.registerConfigPropertyInjectionPoints(bean(List.of(injectionPoint(classType(STRING),
                List.of(configProperty(KEY, null)), declaration(DeclarationInfo.Kind.METHOD)))),
                new CapturingMessages());

        Map<String, Object> params = startCheckParams(extension);

        assertArrayEquals(new String[]{"a method"}, (String[]) params.get(ConfigStartCheck.PROPERTY_SITES));
    }

    @Test
    void resolvePropertyName_of_another_declaration_kind_is_empty() {
        assertEquals("", ConfigCdiExtension.resolvePropertyName(
                configProperty(null, null), declaration(DeclarationInfo.Kind.METHOD)));
    }

    // -- @Synthesis ------------------------------------------------------------------------------

    @Test
    void synthesis_registers_one_bean_per_distinct_type() {
        registerConfig(Map.of(KEY, "1"));
        ConfigCdiExtension extension = new ConfigCdiExtension();
        List<InjectionPointInfo> points = new ArrayList<>();
        for (PrimitiveKind kind : PrimitiveKind.values()) {
            points.add(injectionPoint(primitiveType(kind), List.of(configProperty(KEY, "1")),
                    fieldDeclaration(kind.name())));
        }
        points.add(injectionPoint(classType(INTEGER), List.of(configProperty(KEY, null)), fieldDeclaration("boxed")));
        points.add(injectionPoint(arrayType(classType(STRING)), List.of(configProperty(KEY, null)),
                fieldDeclaration("array")));
        points.add(injectionPoint(parameterizedType("java.util.Optional", classType(INTEGER)),
                List.of(configProperty(KEY, null)), fieldDeclaration("optional")));
        points.add(injectionPoint(classType(MISSING_CLASS), List.of(configProperty(KEY, null)),
                fieldDeclaration("unloadable")));
        points.add(injectionPoint(classType(Lenient.class.getName()),
                List.of(configPropertiesAnnotation(Map.of())), fieldDeclaration("lenient")));
        extension.registerConfigPropertyInjectionPoints(bean(points), new CapturingMessages());
        extension.trackExistingConfigBean(bean(List.of()));
        List<List<Object>> calls = new ArrayList<>();

        extension.synthesizeConfigPropertyBeans(recordingComponents(calls), classTypes());

        List<Object> beanClasses = calls.stream().filter(c -> c.getFirst().equals(ADD_BEAN)).map(c -> c.get(1)).toList();
        assertEquals(List.of(Boolean.class, Byte.class, Short.class, Integer.class, Long.class, Float.class,
                Double.class, Character.class, String[].class, Object.class, Object.class, Object.class), beanClasses);
        assertTrue(calls.contains(List.of("qualifier", ConfigProperties.class)));
        assertTrue(calls.contains(List.of("createWith", ConfigPropertiesSyntheticCreator.class)));
        assertTrue(calls.contains(List.of("createWith", ConfigPropertySyntheticCreator.class)));
    }

    @Test
    void synthesis_registers_one_early_start_check_observing_Startup() {
        ConfigCdiExtension extension = new ConfigCdiExtension();
        extension.registerConfigPropertyInjectionPoints(bean(List.of(
                injectionPoint(classType(INTEGER), List.of(configProperty(KEY, null)), fieldDeclaration("boxed")),
                injectionPoint(classType(Lenient.class.getName()),
                        List.of(configPropertiesAnnotation(Map.of())), fieldDeclaration("lenient")))),
                new CapturingMessages());
        List<List<Object>> calls = new ArrayList<>();

        extension.synthesizeConfigPropertyBeans(recordingComponents(calls), classTypes());

        assertEquals(1, calls.stream().filter(c -> c.getFirst().equals(ADD_OBSERVER)).count());
        assertTrue(calls.contains(List.of(ADD_OBSERVER, Startup.class)));
        assertTrue(calls.contains(List.of("observeWith", ConfigStartCheck.class)));
        // Before the application's own Startup observers, which may already read the configuration.
        assertTrue(calls.contains(List.of("priority", ConfigStartCheck.PRIORITY)));
        assertTrue(ConfigStartCheck.PRIORITY < jakarta.interceptor.Interceptor.Priority.LIBRARY_BEFORE);
    }

    @Test
    void synthesis_registers_no_start_check_without_anything_to_check() {
        ConfigCdiExtension extension = new ConfigCdiExtension();
        extension.registerConfigPropertyInjectionPoints(bean(List.of(injectionPoint(
                parameterizedType("java.util.Optional", classType(INTEGER)),
                List.of(configProperty(KEY, null)), fieldDeclaration("optional")))), new CapturingMessages());
        List<List<Object>> calls = new ArrayList<>();

        extension.synthesizeConfigPropertyBeans(recordingComponents(calls), classTypes());

        assertEquals(0, calls.stream().filter(c -> c.getFirst().equals(ADD_OBSERVER)).count());
    }

    // -- Static helpers --------------------------------------------------------------------------

    @Test
    void extractPrefix_reads_the_string_member_or_defaults_to_empty() {
        assertEquals("p", ConfigCdiExtension.extractPrefix(configPropertiesAnnotation(Map.of(PREFIX, stringMember("p")))));
        assertEquals("", ConfigCdiExtension.extractPrefix(configPropertiesAnnotation(Map.of())));
        assertEquals("", ConfigCdiExtension.extractPrefix(configPropertiesAnnotation(Map.of(PREFIX, intMember()))));
    }

    @Test
    void extractRawPrefix_defaults_to_the_unconfigured_marker() {
        assertEquals(ConfigProperties.UNCONFIGURED_PREFIX,
                ConfigCdiExtension.extractRawPrefix(configPropertiesAnnotation(Map.of())));
    }

    @Test
    void hasConfiguredDefault_ignores_absent_and_non_string_members() {
        assertFalse(ConfigCdiExtension.hasConfiguredDefault(configPropertiesAnnotation(Map.of())));
        assertFalse(ConfigCdiExtension.hasConfiguredDefault(
                configPropertiesAnnotation(Map.of("defaultValue", intMember()))));
    }

    @Test
    void hasConfigPropertiesAnnotation_looks_at_class_annotations() {
        assertTrue(ConfigCdiExtension.hasConfigPropertiesAnnotation(
                classInfo("a.B", List.of(configPropertiesAnnotation(Map.of())))));
        assertFalse(ConfigCdiExtension.hasConfigPropertiesAnnotation(
                classInfo("a.B", List.of(configProperty(KEY, null)))));
    }

    @Test
    void isSupportedType_covers_every_lang_model_kind() {
        assertTrue(ConfigCdiExtension.isSupportedType(primitiveType(PrimitiveKind.INT)));
        assertTrue(ConfigCdiExtension.isSupportedType(arrayType(classType(STRING))));
        assertFalse(ConfigCdiExtension.isSupportedType(typeVariable()));
        assertFalse(ConfigCdiExtension.isSupportedType(parameterizedType(null, classType(STRING))));
        assertFalse(ConfigCdiExtension.isSupportedType(
                parameterizedType("java.util.Optional", classType(STRING), classType(STRING))));
    }

    // -- Fixtures --------------------------------------------------------------------------------

    @ConfigProperties(prefix = REQUIRED_PREFIX)
    static class Required {
        String host;
        @ConfigProperty(name = PORT)
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

    public static final class NotConvertible {
        private NotConvertible() {
            // no public factory nor String constructor: nothing can convert to this type
        }
    }

    // -- Lang model and SPI fakes ----------------------------------------------------------------

    private static CapturingMessages register(InjectionPointInfo... points) {
        CapturingMessages messages = new CapturingMessages();
        new ConfigCdiExtension().registerConfigPropertyInjectionPoints(bean(List.of(points)), messages);
        return messages;
    }

    /**
     * Runs {@code @Synthesis} and answers the parameters the extension gave the start check, by name;
     * empty when it registered none.
     */
    private static Map<String, Object> startCheckParams(ConfigCdiExtension extension) {
        List<List<Object>> calls = new ArrayList<>();
        extension.synthesizeConfigPropertyBeans(recordingComponents(calls), classTypes());
        Map<String, Object> params = new HashMap<>();
        calls.stream().filter(c -> c.getFirst().equals(WITH_PARAM)).forEach(c -> params.put((String) c.get(1), c.get(2)));
        return params;
    }

    private void registerConfig(Map<String, String> values) {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        Config custom = resolver.getBuilder()
                .withSources(new TestSource(values))
                .forClassLoader(getClass().getClassLoader())
                .build();
        resolver.registerConfig(custom, getClass().getClassLoader());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(ConfigCdiExtensionPhasesTest.class.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static UnsupportedOperationException unsupported(java.lang.reflect.Method method) {
        return new UnsupportedOperationException(method.getName());
    }

    private static ClassConfig classConfig(String name, boolean annotated, List<Class<?>> added) {
        ClassInfo info = proxy(ClassInfo.class, (_, m, _) -> switch (m.getName()) {
            case "name" -> name;
            case "hasAnnotation" -> annotated;
            default -> throw unsupported(m);
        });
        return proxy(ClassConfig.class, (p, m, a) -> switch (m.getName()) {
            case "info" -> info;
            case "addAnnotation" -> {
                added.add((Class<?>) a[0]);
                yield p;
            }
            default -> throw unsupported(m);
        });
    }

    private static ClassInfo classInfo(String name, List<AnnotationInfo> annotations) {
        return proxy(ClassInfo.class, (_, m, _) -> switch (m.getName()) {
            case "name", "toString" -> name;
            case "annotations" -> annotations;
            default -> throw unsupported(m);
        });
    }

    private static BeanInfo bean(List<InjectionPointInfo> points) {
        return proxy(BeanInfo.class, (_, m, _) -> switch (m.getName()) {
            case "injectionPoints" -> points;
            default -> throw unsupported(m);
        });
    }

    private static InjectionPointInfo injectionPoint(Type type, List<AnnotationInfo> qualifiers, DeclarationInfo declaration) {
        return proxy(InjectionPointInfo.class, (_, m, _) -> switch (m.getName()) {
            case "type" -> type;
            case "qualifiers" -> qualifiers;
            case "declaration" -> declaration;
            default -> throw unsupported(m);
        });
    }

    private static DeclarationInfo fieldDeclaration(String name) {
        ClassInfo owner = classInfo("com.example.Bean", List.of());
        return proxy(jakarta.enterprise.lang.model.declarations.FieldInfo.class, (p, m, _) -> switch (m.getName()) {
            case "name" -> name;
            case "kind" -> DeclarationInfo.Kind.FIELD;
            case "asField" -> p;
            case "declaringClass" -> owner;
            default -> throw unsupported(m);
        });
    }

    /** A parameter of a method, or of a constructor, whose lang model name is its class name. */
    private static DeclarationInfo parameterDeclaration(String ownerName, String methodName, boolean constructor,
                                                        String name) {
        ClassInfo owner = classInfo(ownerName, List.of());
        var method = proxy(jakarta.enterprise.lang.model.declarations.MethodInfo.class, (_, m, _) -> switch (m.getName()) {
            case "declaringClass" -> owner;
            case "name" -> methodName;
            case "isConstructor" -> constructor;
            default -> throw unsupported(m);
        });
        return proxy(jakarta.enterprise.lang.model.declarations.ParameterInfo.class, (p, m, _) -> switch (m.getName()) {
            case "name" -> name;
            case "kind" -> DeclarationInfo.Kind.PARAMETER;
            case "asParameter" -> p;
            case "declaringMethod" -> method;
            default -> throw unsupported(m);
        });
    }

    private static DeclarationInfo declaration(DeclarationInfo.Kind kind) {
        return proxy(DeclarationInfo.class, (_, m, _) -> switch (m.getName()) {
            case "kind" -> kind;
            case "toString" -> "a " + kind.name().toLowerCase(java.util.Locale.ROOT);
            default -> throw unsupported(m);
        });
    }

    /** {@code @ConfigProperty} whose {@code name}/{@code defaultValue} members are absent when null. */
    private static AnnotationInfo configProperty(String name, String defaultValue) {
        Map<String, AnnotationMember> members = new HashMap<>();
        if (name != null) members.put("name", stringMember(name));
        if (defaultValue != null) members.put("defaultValue", stringMember(defaultValue));
        return annotation(ConfigProperty.class.getName(), members);
    }

    private static AnnotationInfo configPropertiesAnnotation(Map<String, AnnotationMember> members) {
        return annotation(ConfigProperties.class.getName(), members);
    }

    private static AnnotationInfo annotation(String name, Map<String, AnnotationMember> members) {
        return proxy(AnnotationInfo.class, (_, m, _) -> switch (m.getName()) {
            case "name" -> name;
            case "members" -> members;
            default -> throw unsupported(m);
        });
    }

    private static AnnotationMember stringMember(String value) {
        return proxy(AnnotationMember.class, (_, m, _) -> switch (m.getName()) {
            case "isString" -> true;
            case "asString" -> value;
            default -> throw unsupported(m);
        });
    }

    private static AnnotationMember intMember() {
        return proxy(AnnotationMember.class, (_, m, _) -> switch (m.getName()) {
            case "isString" -> false;
            default -> throw unsupported(m);
        });
    }

    private static ClassType classType(String fqcn) {
        ClassInfo declaration = classInfo(fqcn, List.of());
        return proxy(ClassType.class, (_, m, _) -> switch (m.getName()) {
            case "declaration" -> declaration;
            case "toString" -> fqcn;
            default -> throw unsupported(m);
        });
    }

    /** {@code genericClass()} answers null when {@code rawFqcn} is null. */
    private static ParameterizedType parameterizedType(String rawFqcn, Type... args) {
        ClassType raw = rawFqcn == null ? null : classType(rawFqcn);
        List<Type> arguments = List.of(args);
        return proxy(ParameterizedType.class, (_, m, _) -> switch (m.getName()) {
            case "genericClass" -> raw;
            case "typeArguments" -> arguments;
            case "toString" -> rawFqcn + arguments;
            default -> throw unsupported(m);
        });
    }

    private static PrimitiveType primitiveType(PrimitiveKind kind) {
        return proxy(PrimitiveType.class, (_, m, _) -> switch (m.getName()) {
            case "primitiveKind" -> kind;
            case "toString" -> kind.name().toLowerCase(java.util.Locale.ROOT);
            default -> throw unsupported(m);
        });
    }

    private static ArrayType arrayType(Type component) {
        return proxy(ArrayType.class, (_, m, _) -> switch (m.getName()) {
            case "componentType" -> component;
            case "toString" -> component + "[]";
            default -> throw unsupported(m);
        });
    }

    private static TypeVariable typeVariable() {
        return proxy(TypeVariable.class, (_, m, _) -> switch (m.getName()) {
            case "toString" -> "T";
            default -> throw unsupported(m);
        });
    }

    /** {@code Types.of(Class)} answers the matching class type. */
    private static Types classTypes() {
        return proxy(Types.class, (_, m, a) -> switch (m.getName()) {
            case "of" -> classType(((Class<?>) a[0]).getName());
            default -> throw unsupported(m);
        });
    }

    /**
     * Records every call as {@code [methodName, firstArgument]}, and a parameter of the synthetic
     * observer as {@code [withParam, name, value]}.
     */
    private static SyntheticComponents recordingComponents(List<List<Object>> calls) {
        SyntheticBeanBuilder<?> builder = proxy(SyntheticBeanBuilder.class, (p, m, a) -> {
            calls.add(List.of(m.getName(), a[0]));
            return p;
        });
        SyntheticObserverBuilder<?> observerBuilder = proxy(SyntheticObserverBuilder.class, (p, m, a) -> {
            calls.add(m.getName().equals(WITH_PARAM) ? List.of(WITH_PARAM, a[0], a[1]) : List.of(m.getName(), a[0]));
            return p;
        });
        return proxy(SyntheticComponents.class, (_, m, a) -> switch (m.getName()) {
            case ADD_BEAN -> {
                calls.add(List.of(ADD_BEAN, a[0]));
                yield builder;
            }
            case ADD_OBSERVER -> {
                calls.add(List.of(ADD_OBSERVER, a[0]));
                yield observerBuilder;
            }
            default -> throw unsupported(m);
        });
    }

    private record TestSource(Map<String, String> values) implements ConfigSource {
        @Override public Map<String, String> getProperties() { return values; }
        @Override public Set<String> getPropertyNames() { return values.keySet(); }
        @Override public String getValue(String propertyName) { return values.get(propertyName); }
        @Override public String getName() { return "test-source"; }
        @Override public int getOrdinal() { return 1000; }
    }

    private static final class CapturingMessages implements Messages {
        private final List<String> errors = new ArrayList<>();

        @Override public void info(String message) { /* only errors matter */ }
        @Override public void info(String message, AnnotationTarget target) { /* only errors matter */ }
        @Override public void info(String message, BeanInfo bean) { /* only errors matter */ }
        @Override public void info(String message, ObserverInfo observer) { /* only errors matter */ }
        @Override public void warn(String message) { /* only errors matter */ }
        @Override public void warn(String message, AnnotationTarget target) { /* only errors matter */ }
        @Override public void warn(String message, BeanInfo bean) { /* only errors matter */ }
        @Override public void warn(String message, ObserverInfo observer) { /* only errors matter */ }
        @Override public void error(String message) { errors.add(message); }
        @Override public void error(String message, AnnotationTarget target) { errors.add(message); }
        @Override public void error(String message, BeanInfo bean) { errors.add(message); }
        @Override public void error(String message, ObserverInfo observer) { errors.add(message); }
        @Override public void error(Exception exception) { errors.add(exception.getMessage()); }
    }
}
