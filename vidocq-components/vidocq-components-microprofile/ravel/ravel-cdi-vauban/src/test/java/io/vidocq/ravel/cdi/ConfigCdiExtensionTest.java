/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.cdi;

import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.DeclarationInfo;
import jakarta.enterprise.lang.model.declarations.FieldInfo;
import jakarta.enterprise.lang.model.types.ClassType;
import jakarta.enterprise.lang.model.types.ParameterizedType;
import jakarta.enterprise.lang.model.types.Type;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void resolvePropertyName_falls_back_to_field_name_when_unconfigured() {
        AnnotationInfo cfg = configPropertyAnnotation("", ConfigProperty.UNCONFIGURED_VALUE);
        DeclarationInfo decl = fieldDeclaration("memberName");

        assertEquals("memberName", ConfigCdiExtension.resolvePropertyName(cfg, decl));
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
    void unsupportedType_rejects_nested_parameterized_argument() {
        Type nested = parameterizedType("java.util.List", classType("java.lang.String"));
        Type optionalOfNested = parameterizedType("java.util.Optional", nested);

        assertFalse(ConfigCdiExtension.isSupportedType(optionalOfNested));
    }

    @Test
    void validate_reports_missing_required_non_optional_property() {
        String key = "cdi.test.missing.required." + System.nanoTime();
        AnnotationInfo cfg = configPropertyAnnotation(key, ConfigProperty.UNCONFIGURED_VALUE);

        InjectionPointInfo ip = injectionPoint(classType("java.lang.String"), List.of(cfg), fieldDeclaration("requiredValue"));
        BeanInfo bean = beanWithInjectionPoints(List.of(ip));
        CapturingMessages messages = new CapturingMessages();

        new ConfigCdiExtension().validateConfigPropertyInjectionPoints(bean, messages);

        assertEquals(1, messages.errors.size());
        assertTrue(messages.errors.get(0).contains(key));
    }

    @Test
    void validate_does_not_report_missing_property_for_optional_injection() {
        String key = "cdi.test.missing.optional." + System.nanoTime();
        AnnotationInfo cfg = configPropertyAnnotation(key, ConfigProperty.UNCONFIGURED_VALUE);

        InjectionPointInfo ip = injectionPoint(
                parameterizedType("java.util.Optional", classType("java.lang.Integer")),
                List.of(cfg),
                fieldDeclaration("optionalValue"));

        BeanInfo bean = beanWithInjectionPoints(List.of(ip));
        CapturingMessages messages = new CapturingMessages();

        new ConfigCdiExtension().validateConfigPropertyInjectionPoints(bean, messages);

        assertTrue(messages.errors.isEmpty());
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
        FieldInfo field = (FieldInfo) Proxy.newProxyInstance(
                FieldInfo.class.getClassLoader(),
                new Class[]{FieldInfo.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "name" -> name;
                    case "kind" -> DeclarationInfo.Kind.FIELD;
                    case "asField" -> proxy;
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
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        return (ClassType) Proxy.newProxyInstance(
                ClassType.class.getClassLoader(),
                new Class[]{ClassType.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "declaration" -> declaration;
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
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class CapturingMessages implements Messages {
        private final List<String> errors = new ArrayList<>();

        @Override public void info(String message) { }
        @Override public void info(String message, jakarta.enterprise.lang.model.AnnotationTarget target) { }
        @Override public void info(String message, BeanInfo bean) { }
        @Override public void info(String message, jakarta.enterprise.inject.build.compatible.spi.ObserverInfo observer) { }
        @Override public void warn(String message) { }
        @Override public void warn(String message, jakarta.enterprise.lang.model.AnnotationTarget target) { }
        @Override public void warn(String message, BeanInfo bean) { }
        @Override public void warn(String message, jakarta.enterprise.inject.build.compatible.spi.ObserverInfo observer) { }
        @Override public void error(String message) { errors.add(message); }
        @Override public void error(String message, jakarta.enterprise.lang.model.AnnotationTarget target) { errors.add(message); }
        @Override public void error(String message, BeanInfo bean) { errors.add(message); }
        @Override public void error(String message, jakarta.enterprise.inject.build.compatible.spi.ObserverInfo observer) { errors.add(message); }
        @Override public void error(Exception exception) { errors.add(exception.getMessage()); }
    }
}

