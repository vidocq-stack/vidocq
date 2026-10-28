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
import jakarta.enterprise.inject.Vetoed;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.ObserverInfo;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanBuilder;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
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
import org.eclipse.microprofile.config.spi.Converter;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ConfigCdiExtension — enhancement, registration, validation and synthesis phases")
class ConfigCdiExtensionPhasesTest {

    private static final String KEY = "phases.key";
    private static final String STRING = "java.lang.String";
    private static final String INTEGER = "java.lang.Integer";
    private static final String MISSING_CLASS = "com.example.DoesNotExist";
    private static final String LIST = "java.util.List";
    private static final String PREFIX = "prefix";
    private static final String ADD_BEAN = "addBean";
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
    void validation_reports_missing_members_of_vetoed_classes() {
        registerConfig(Map.of("required.host", "localhost"));
        ConfigCdiExtension extension = new ConfigCdiExtension();
        extension.vetoConfigPropertiesClasses(classConfig(Required.class.getName(), true, new ArrayList<>()));
        extension.vetoConfigPropertiesClasses(classConfig(MISSING_CLASS, true, new ArrayList<>()));
        CapturingMessages messages = new CapturingMessages();

        extension.validateVetoedConfigPropertiesClasses(messages);

        assertEquals(1, messages.errors.size());
        assertTrue(messages.errors.getFirst().contains("'required.port'"), messages.errors.getFirst());
    }

    @Test
    void validation_skips_static_defaulted_optional_and_initialized_members() {
        registerConfig(Map.of());
        ConfigCdiExtension extension = new ConfigCdiExtension();
        extension.vetoConfigPropertiesClasses(classConfig(Lenient.class.getName(), true, new ArrayList<>()));
        CapturingMessages messages = new CapturingMessages();

        extension.validateVetoedConfigPropertiesClasses(messages);

        assertEquals(List.of(), messages.errors);
    }

    @Test
    void validation_requires_every_zero_valued_primitive() {
        registerConfig(Map.of("prim.flag", ""));
        ConfigCdiExtension extension = new ConfigCdiExtension();
        extension.vetoConfigPropertiesClasses(classConfig(Primitives.class.getName(), true, new ArrayList<>()));
        extension.vetoConfigPropertiesClasses(classConfig(NoDefaultConstructor.class.getName(), true, new ArrayList<>()));
        CapturingMessages messages = new CapturingMessages();

        extension.validateVetoedConfigPropertiesClasses(messages);

        assertEquals(9, messages.errors.size());
        assertTrue(messages.errors.stream().anyMatch(m -> m.contains("'prim.flag'")));
        assertTrue(messages.errors.stream().anyMatch(m -> m.contains("'port'")));
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
    void ConfigProperties_injection_point_prefix_overrides_the_class_prefix() {
        registerConfig(Map.of("override.host", "h", "override.port", "1"));

        CapturingMessages messages = register(injectionPoint(classType(Required.class.getName()),
                List.of(configPropertiesAnnotation(Map.of(PREFIX, stringMember("override")))),
                fieldDeclaration(REQUIRED_PREFIX)));

        assertEquals(List.of(), messages.errors);
    }

    @Test
    void ConfigProperties_injection_point_of_an_unloadable_class_is_left_to_runtime() {
        CapturingMessages messages = register(injectionPoint(classType(MISSING_CLASS),
                List.of(configPropertiesAnnotation(Map.of())), fieldDeclaration("missing")));

        assertEquals(List.of(), messages.errors);
    }

    @Test
    void injection_point_without_config_qualifier_is_ignored() {
        CapturingMessages messages = register(injectionPoint(classType(STRING), List.of(), fieldDeclaration("plain")));

        assertEquals(List.of(), messages.errors);
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
    void present_value_that_does_not_convert_is_reported() {
        registerConfig(Map.of(KEY, "not-a-number"));

        CapturingMessages messages = register(injectionPoint(classType(INTEGER),
                List.of(configProperty(KEY, null)), fieldDeclaration("number")));

        assertEquals(1, messages.errors.size());
        assertTrue(messages.errors.getFirst().startsWith("Invalid @ConfigProperty for key '" + KEY + "'"));
    }

    @Test
    void present_value_whose_converter_returns_null_is_reported() {
        registerConfig(Map.of(KEY, "anything"));

        CapturingMessages messages = register(injectionPoint(classType(NullConverted.class.getName()),
                List.of(configProperty(KEY, null)), fieldDeclaration("nullConverted")));

        assertEquals(1, messages.errors.size());
        assertTrue(messages.errors.getFirst().contains("converter returned null"));
    }

    @Test
    void empty_default_value_is_reported() {
        registerConfig(Map.of());

        CapturingMessages messages = register(injectionPoint(classType(STRING),
                List.of(configProperty(KEY, "")), fieldDeclaration("emptyDefault")));

        assertEquals(1, messages.errors.size());
        assertTrue(messages.errors.getFirst().startsWith("Empty defaultValue"));
    }

    @Test
    void default_value_converting_to_null_is_reported() {
        registerConfig(Map.of());

        CapturingMessages messages = register(injectionPoint(classType(NullConverted.class.getName()),
                List.of(configProperty(KEY, "x")), fieldDeclaration("nullDefault")));

        assertEquals(1, messages.errors.size());
        assertTrue(messages.errors.getFirst().contains("converts to null"));
    }

    @Test
    void default_value_of_a_type_without_converter_is_reported() {
        registerConfig(Map.of());

        CapturingMessages messages = register(injectionPoint(classType(NotConvertible.class.getName()),
                List.of(configProperty(KEY, "x")), fieldDeclaration("notConvertible")));

        assertEquals(1, messages.errors.size());
        assertTrue(messages.errors.getFirst().contains("No converter"));
    }

    @Test
    void valid_default_value_and_types_resolved_later_are_accepted() {
        registerConfig(Map.of());

        CapturingMessages messages = register(
                injectionPoint(classType(INTEGER), List.of(configProperty(KEY, "5")), fieldDeclaration("a")),
                injectionPoint(classType("org.eclipse.microprofile.config.ConfigValue"),
                        List.of(configProperty(KEY, null)), fieldDeclaration("b")),
                injectionPoint(classType(OptionalInt.class.getName()), List.of(configProperty(KEY, null)),
                        fieldDeclaration("c")),
                injectionPoint(arrayType(classType(STRING)), List.of(configProperty(KEY, null)),
                        fieldDeclaration("d")),
                injectionPoint(parameterizedType(LIST, classType(STRING)),
                        List.of(configProperty(KEY, null)), fieldDeclaration("e")),
                injectionPoint(classType(MISSING_CLASS), List.of(configProperty(KEY, null)),
                        fieldDeclaration("f")));

        assertEquals(List.of(), messages.errors);
    }

    @Test
    void every_primitive_kind_is_validated_against_its_runtime_class() {
        Map<PrimitiveKind, String> values = Map.of(
                PrimitiveKind.BOOLEAN, "true", PrimitiveKind.BYTE, "1", PrimitiveKind.SHORT, "2",
                PrimitiveKind.INT, "3", PrimitiveKind.LONG, "4", PrimitiveKind.FLOAT, "5.5",
                PrimitiveKind.DOUBLE, "6.5", PrimitiveKind.CHAR, "c");
        Map<String, String> config = new HashMap<>();
        List<InjectionPointInfo> points = new ArrayList<>();
        values.forEach((kind, value) -> {
            config.put("prim." + kind, value);
            points.add(injectionPoint(primitiveType(kind), List.of(configProperty("prim." + kind, null)),
                    fieldDeclaration(kind.name())));
        });
        registerConfig(config);

        CapturingMessages messages = register(points.toArray(InjectionPointInfo[]::new));

        assertEquals(List.of(), messages.errors);
    }

    @Test
    void default_name_of_a_parameter_injection_point_is_its_canonical_member_name() {
        registerConfig(Map.of("com.example.Outer.Inner.param", "ok"));

        CapturingMessages messages = register(injectionPoint(classType(STRING),
                List.of(configProperty(null, null)), parameterDeclaration("com.example.Outer$Inner", "param")));

        assertEquals(List.of(), messages.errors);
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

    @Test
    void isDeferredOrOptionalWrapper_recognises_the_three_wrappers_only() {
        assertTrue(ConfigCdiExtension.isDeferredOrOptionalWrapper(
                parameterizedType("java.util.function.Supplier", classType(STRING))));
        assertFalse(ConfigCdiExtension.isDeferredOrOptionalWrapper(
                parameterizedType(LIST, classType(STRING))));
        assertFalse(ConfigCdiExtension.isDeferredOrOptionalWrapper(classType(STRING)));
    }

    @Test
    void fieldConfigPropertyName_prefixes_the_member_or_the_configured_name() throws NoSuchFieldException {
        var host = Required.class.getDeclaredField("host");
        var port = Required.class.getDeclaredField(PORT);
        ConfigProperty portAnno = port.getAnnotation(ConfigProperty.class);

        assertEquals("p.host", ConfigCdiExtension.fieldConfigPropertyName(host, null, "p"));
        assertEquals("host", ConfigCdiExtension.fieldConfigPropertyName(host, null, ""));
        assertEquals("p.port", ConfigCdiExtension.fieldConfigPropertyName(port, portAnno, "p"));
        assertEquals(PORT, ConfigCdiExtension.fieldConfigPropertyName(port, portAnno, ""));
    }

    @Test
    void resolveConfigPropertiesPrefix_prefers_the_injection_point_then_the_class() {
        assertEquals("ip", ConfigCdiExtension.resolveConfigPropertiesPrefix(Required.class, "ip"));
        assertEquals(REQUIRED_PREFIX,
                ConfigCdiExtension.resolveConfigPropertiesPrefix(Required.class, ConfigProperties.UNCONFIGURED_PREFIX));
        assertEquals("",
                ConfigCdiExtension.resolveConfigPropertiesPrefix(String.class, ConfigProperties.UNCONFIGURED_PREFIX));
    }

    @Test
    void isOptionalLikeRuntime_recognises_optional_types() {
        assertTrue(ConfigCdiExtension.isOptionalLikeRuntime(Optional.class));
        assertFalse(ConfigCdiExtension.isOptionalLikeRuntime(String.class));
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

    // -- Lang model and SPI fakes ----------------------------------------------------------------

    private static CapturingMessages register(InjectionPointInfo... points) {
        CapturingMessages messages = new CapturingMessages();
        new ConfigCdiExtension().registerConfigPropertyInjectionPoints(bean(List.of(points)), messages);
        return messages;
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

    private static DeclarationInfo parameterDeclaration(String ownerName, String name) {
        ClassInfo owner = classInfo(ownerName, List.of());
        var method = proxy(jakarta.enterprise.lang.model.declarations.MethodInfo.class, (_, m, _) -> switch (m.getName()) {
            case "declaringClass" -> owner;
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

    /** Records every call as {@code [methodName, firstArgument]}. */
    private static SyntheticComponents recordingComponents(List<List<Object>> calls) {
        SyntheticBeanBuilder<?> builder = proxy(SyntheticBeanBuilder.class, (p, m, a) -> {
            calls.add(List.of(m.getName(), a[0]));
            return p;
        });
        return proxy(SyntheticComponents.class, (_, m, a) -> switch (m.getName()) {
            case ADD_BEAN -> {
                calls.add(List.of(ADD_BEAN, a[0]));
                yield builder;
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
