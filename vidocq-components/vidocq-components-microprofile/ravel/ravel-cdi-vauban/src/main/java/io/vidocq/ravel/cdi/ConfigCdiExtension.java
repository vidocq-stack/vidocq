/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.cdi;

import io.vidocq.ravel.cdi.internal.ConfigPropertiesSyntheticCreator;
import io.vidocq.ravel.cdi.internal.ConfigSyntheticCreator;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.DeclarationInfo;
import jakarta.enterprise.lang.model.types.ClassType;
import jakarta.enterprise.lang.model.types.ParameterizedType;
import jakarta.enterprise.lang.model.types.Type;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.lang.reflect.Array;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * CDI Build Compatible Extension that (1) validates {@code @ConfigProperty} injection
 * points at deployment time and (2) synthesises one bean per IP type encountered to
 * satisfy Weld typed resolutions (see MicroProfile Config 3.1 §6.1).
 *
 * <p>Implemented as {@code @Registration(types = Object.class)} (rather than
 * {@code @Validation}) because CDI Lite 4.1 (§Build Compatible Extensions) forbids
 * {@code BeanInfo} as a parameter of {@code @Validation} methods:
 * {@code LITE-EXTENSION-TRANSLATOR-000002}. {@code @Registration(types=Object.class)}
 * is invoked once per {@link BeanInfo} (all beans extend {@code Object}) and accepts
 * {@link Messages} to report errors.</p>
 *
 * <p>{@code @Synthesis} phase: for each type collected during the {@code @Registration}
 * phase, a {@code SyntheticBean} qualified with {@code @ConfigProperty} is registered
 * using {@link ConfigPropertySyntheticCreator} as its creator.
 * The {@code name}/{@code defaultValue} members of {@code @ConfigProperty} are marked
 * {@code @Nonbinding} in the MP Config spec, so a single bean per type covers all
 * injection-site variants.</p>
 */
public class ConfigCdiExtension implements BuildCompatibleExtension {

    /**
     * Types collected during {@code @Registration} for synthesis in
     * {@code @Synthesis}. Deduplicated by textual representation because
     * {@link Type} does not guarantee {@code equals}/{@code hashCode}.
     */
    private final Map<String, Type> collectedTypes = new LinkedHashMap<>();

    /**
     * Types annotated with @ConfigProperties collected during {@code @Registration}.
     * Key = type string, Value = (type, prefix) pair.
     */
    private final Map<String, ConfigPropertiesEntry> configPropertiesTypes = new LinkedHashMap<>();

    /**
     * Entry to store a @ConfigProperties type together with its prefix.
     */
    private static class ConfigPropertiesEntry {
        final Type type;
        final String prefix;

        ConfigPropertiesEntry(Type type, String prefix) {
            this.type = type;
            this.prefix = prefix;
        }
    }

    /**
     * Exclusion phase (@Discovery) that marks classes annotated with @ConfigProperties
     * to prevent them from being discovered as managed beans.
     *
     * <p>@ConfigProperties classes should only be injected via our synthetic beans
     * (qualified with @ConfigProperties), not via automatic bean discovery.</p>
     */
    // Note: CDI 4.1 BC API does not expose a direct API to @Exclude classes.
    // This feature must be handled through another extension.

    @Registration(types = Object.class)
    public void registerConfigPropertyInjectionPoints(BeanInfo beanInfo, Messages messages) {
        for (InjectionPointInfo injectionPoint : beanInfo.injectionPoints()) {
            // Case 1: @ConfigProperty
            AnnotationInfo configPropertyQual = findConfigPropertyQualifier(injectionPoint);
            if (configPropertyQual != null) {
                DeclarationInfo declaration = injectionPoint.declaration();
                Type ipType = injectionPoint.type();
                if (!isSupportedType(ipType)) {
                    messages.error("Unsupported @ConfigProperty injection type: " + ipType, declaration);
                    continue;
                }
                validateDeploymentContract(configPropertyQual, declaration, ipType, messages);
                collectedTypes.putIfAbsent(ipType.toString(), ipType);
                continue;
            }

            // Case 2: @ConfigProperties
            AnnotationInfo configPropertiesQual = findConfigPropertiesQualifier(injectionPoint);
            if (configPropertiesQual != null) {
                Type ipType = injectionPoint.type();
                if (!(ipType instanceof ClassType ct)) {
                    messages.error("@ConfigProperties only supports class types, got: " + ipType,
                            injectionPoint.declaration());
                    continue;
                }
                String fieldPrefix = extractRawPrefix(configPropertiesQual);
                configPropertiesTypes.putIfAbsent(ipType.toString(),
                    new ConfigPropertiesEntry(ipType, fieldPrefix));
                validateConfigPropertiesDeployment(ct, fieldPrefix, injectionPoint.declaration(), messages);
                continue;
            }
        }
    }

    /**
     * Collects {@code @ConfigProperties} classes from the build context.
     * Called before {@code @Synthesis}.
     */
    @Registration(types = Object.class)
    public void registerConfigPropertiesClasses(BeanInfo beanInfo, Messages messages) {
        // This method could be used to collect classes with @ConfigProperties,
        // but for now this logic is already handled in registerConfigPropertyInjectionPoints
    }

    @Synthesis
    public void synthesizeConfigPropertyBeans(SyntheticComponents components, Types types) {
        // @Default Config bean — many TCK tests simply inject
        // {@code @Inject Config config} without @ConfigProperty, and the
        // RavelConfigProducer is not included in ShrinkWrap archives.
        components.addBean(org.eclipse.microprofile.config.Config.class)
                .type(org.eclipse.microprofile.config.Config.class)
                .scope(Dependent.class)
                .createWith(ConfigSyntheticCreator.class);

        // Synthesise @ConfigProperty beans.
        // For non-parameterized types (Class, Class[], boxed primitives),
        // use the runtime {@code Class<?>} — Weld binds array types without
        // issue via {@code .type(Class<?>)}, whereas {@code .type(ArrayType lang-model)}
        // may be silently ignored (see WELD-001408 for OffsetDateTime[]).
        var registered = new java.util.HashSet<String>();
        for (Type ipType : collectedTypes.values()) {
            Type effectiveType = ipType instanceof jakarta.enterprise.lang.model.types.PrimitiveType pt
                    ? types.of(boxPrimitive(pt.primitiveKind()))
                    : ipType;

            if (!registered.add(effectiveType.toString())) {
                continue;
            }

            // For arrays and simple class types, use the runtime {@code Class<?>}
            // — Weld binds array types correctly this way, whereas
            // {@code .type(ArrayType lang-model)} may be silently ignored (see WELD-001408
            // for OffsetDateTime[]). For parameterized types (List<X>, Provider<T>,
            // Optional<T>, etc.) keep the lang-model {@code Type} — otherwise the
            // generic parameter is lost and multiple beans conflict.
            boolean isParameterized = effectiveType instanceof ParameterizedType;
            Class<?> runtimeClass = isParameterized ? null : toRuntimeClassOrNull(effectiveType);
            if (runtimeClass != null) {
                addSyntheticConfigPropertyBean(components, runtimeClass);
            } else {
                components.addBean(Object.class)
                        .type(effectiveType)
                        .qualifier(ConfigProperty.class)
                        .scope(Dependent.class)
                        .createWith(ConfigPropertySyntheticCreator.class);
            }
        }

        // Synthesize @ConfigProperties beans.
        // MP Config 3.1 spec: @ConfigProperties.prefix is @Nonbinding — so
        // a single SyntheticBean per BeanType qualified with @ConfigProperties suffices
        // (the effective prefix is resolved at runtime by the creator from the IP).
        var registeredConfigProperties = new java.util.HashSet<String>();
        for (ConfigPropertiesEntry entry : configPropertiesTypes.values()) {
            String typeStr = entry.type.toString();
            if (!registeredConfigProperties.add(typeStr)) {
                continue;
            }
            components.addBean(Object.class)
                    .type(entry.type)
                    .qualifier(ConfigProperties.class)
                    .scope(Dependent.class)
                    .createWith(ConfigPropertiesSyntheticCreator.class);
        }
    }

    /**
     * Placeholder phase for future integration.
     */
    @Synthesis
    public void synthesizeAdditional(SyntheticComponents components, Types types) {
        // Placeholder for additional synthesis needs
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addSyntheticConfigPropertyBean(SyntheticComponents components, Class<?> beanClass) {
        // Cast required because {@code SyntheticComponents.addBean(Class<T>)} returns
        // {@code SyntheticBeanBuilder<T>} and {@code createWith} expects
        // {@code Class<? extends SyntheticBeanCreator<T>>} with the same T.
        ((jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanBuilder) components.addBean(beanClass))
                .type(beanClass)
                .qualifier(ConfigProperty.class)
                .scope(Dependent.class)
                .createWith(ConfigPropertySyntheticCreator.class);
    }

    private static Class<?> boxPrimitive(jakarta.enterprise.lang.model.types.PrimitiveType.PrimitiveKind kind) {
        return switch (kind) {
            case BOOLEAN -> Boolean.class;
            case BYTE -> Byte.class;
            case SHORT -> Short.class;
            case INT -> Integer.class;
            case LONG -> Long.class;
            case FLOAT -> Float.class;
            case DOUBLE -> Double.class;
            case CHAR -> Character.class;
        };
    }

    static AnnotationInfo findConfigPropertyQualifier(InjectionPointInfo injectionPoint) {
        for (AnnotationInfo qualifier : injectionPoint.qualifiers()) {
            if (ConfigProperty.class.getName().equals(qualifier.name())) {
                return qualifier;
            }
        }
        return null;
    }

    static AnnotationInfo findConfigPropertiesQualifier(InjectionPointInfo injectionPoint) {
        for (AnnotationInfo qualifier : injectionPoint.qualifiers()) {
            if (ConfigProperties.class.getName().equals(qualifier.name())) {
                return qualifier;
            }
        }
        return null;
    }

    static String extractPrefix(AnnotationInfo configPropertiesQual) {
        Map<String, AnnotationMember> members = configPropertiesQual.members();
        AnnotationMember prefixMember = members.get("prefix");
        if (prefixMember != null && prefixMember.isString()) {
            return prefixMember.asString();
        }
        return "";
    }

    /**
     * Raw prefix of @ConfigProperties — may be {@link ConfigProperties#UNCONFIGURED_PREFIX}
     * (member absent ⇒ default value), {@code ""} (explicit override "no prefix"),
     * or an application prefix. Distinguishing these 3 cases is necessary to decide
     * the class-level fallback (§6.4).
     */
    static String extractRawPrefix(AnnotationInfo configPropertiesQual) {
        AnnotationMember prefixMember = configPropertiesQual.members().get("prefix");
        if (prefixMember != null && prefixMember.isString()) {
            return prefixMember.asString();
        }
        return ConfigProperties.UNCONFIGURED_PREFIX;
    }

    /**
     * Deployment validation for {@code @ConfigProperties} (§6.4) — verifies that
     * all required properties are present. A property is considered required if it
     * has no fallback:
     * <ul>
     *   <li>no {@code @ConfigProperty(defaultValue=...)} on the field;</li>
     *   <li>not an {@code Optional} / {@code OptionalInt/Long/Double} type;</li>
     *   <li>no Java initializer (e.g. {@code int port = 9080;}) — detected by
     *       instantiating the bean and comparing the value to the zero-value of
     *       the type.</li>
     * </ul>
     * Targets {@link org.eclipse.microprofile.config.tck.broken.ConfigPropertiesMissingPropertyInjectionTest}.
     */
    private static void validateConfigPropertiesDeployment(
            ClassType beanType, String fieldRawPrefix, DeclarationInfo declaration, Messages messages) {
        Class<?> beanClass = loadClass(beanType.declaration().name());
        if (beanClass == null) {
        // Class not loadable at deployment time — let runtime validation handle it.
            return;
        }
        String resolvedPrefix = resolveConfigPropertiesPrefix(beanClass, fieldRawPrefix);
        Object probe = tryInstantiate(beanClass);
        Config config = ConfigProvider.getConfig();
        for (java.lang.reflect.Field field : beanClass.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            ConfigProperty fieldAnno = field.getAnnotation(ConfigProperty.class);
            String name = fieldConfigPropertyName(field, fieldAnno, resolvedPrefix);
            if (fieldAnno != null
                    && !ConfigProperty.UNCONFIGURED_VALUE.equals(fieldAnno.defaultValue())) continue;
            if (isOptionalLikeRuntime(field.getType(), field.getGenericType())) continue;
            if (hasJavaInitializer(probe, field)) continue;
            ConfigValue cv = config.getConfigValue(name);
            String resolved = cv != null ? cv.getValue() : null;
            if (resolved == null || resolved.isEmpty()) {
                messages.error("@ConfigProperties: missing required property '" + name
                        + "' for " + beanClass.getName() + "." + field.getName(), declaration);
            }
        }
    }

    private static Object tryInstantiate(Class<?> beanClass) {
        try {
            var ctor = beanClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasJavaInitializer(Object probe, java.lang.reflect.Field field) {
        if (probe == null) return false;
        try {
            field.setAccessible(true);
            Object actual = field.get(probe);
            Object zero = zeroValue(field.getType());
            return !java.util.Objects.equals(actual, zero);
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    private static Object zeroValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return (char) 0;
        return null;
    }

    static String resolveConfigPropertiesPrefix(Class<?> beanClass, String fieldRawPrefix) {
        if (!ConfigProperties.UNCONFIGURED_PREFIX.equals(fieldRawPrefix)) {
            return fieldRawPrefix;
        }
        ConfigProperties classAnno = beanClass.getAnnotation(ConfigProperties.class);
        if (classAnno != null && !ConfigProperties.UNCONFIGURED_PREFIX.equals(classAnno.prefix())) {
            return classAnno.prefix();
        }
        return "";
    }

    static String fieldConfigPropertyName(java.lang.reflect.Field field, ConfigProperty anno, String resolvedPrefix) {
        if (anno != null && !ConfigProperty.UNCONFIGURED_VALUE.equals(anno.name()) && !anno.name().isEmpty()) {
            return resolvedPrefix.isEmpty() ? anno.name() : resolvedPrefix + "." + anno.name();
        }
        return resolvedPrefix.isEmpty() ? field.getName() : resolvedPrefix + "." + field.getName();
    }

    static boolean isOptionalLikeRuntime(Class<?> rawType, java.lang.reflect.Type genericType) {
        return rawType == Optional.class
                || rawType == OptionalInt.class
                || rawType == OptionalLong.class
                || rawType == OptionalDouble.class;
    }

    static boolean isSupportedType(Type type) {
        // Primitives (int, long, boolean, ...) are valid §6.1 — Weld resolves them
        // via auto-boxing of the synthetic bean (primitive bean type).
        if (type instanceof jakarta.enterprise.lang.model.types.PrimitiveType) {
            return true;
        }
        if (type instanceof ClassType) {
            return true;
        }
        if (type instanceof ParameterizedType pt) {
            if (!(pt.genericClass() instanceof ClassType classType)) {
                return false;
            }
            if (!isDeferredOrOptionalWrapperName(classType.declaration().name())) {
                // Generic parameterized types (List<X>, Set<X>, Map<K,V>, etc.)
                // are also valid as bean types — conversion happens at runtime via
                // Converter<T>. Accept all PT.
                return true;
            }
            return pt.typeArguments().size() == 1;
        }
        // Array types (jakarta.enterprise.lang.model.types.ArrayType) supported
        // for String[], Duration[], etc. (spec §5.4).
        return type instanceof jakarta.enterprise.lang.model.types.ArrayType;
    }

    /**
     * True when type is a wrapper making the underlying property non-required
     * at deployment time: {@link java.util.Optional}, {@code jakarta.inject.Provider}
     * (lazy lookup), and {@code java.util.function.Supplier} (lazy lookup).
     */
    static boolean isDeferredOrOptionalWrapper(Type type) {
        return type instanceof ParameterizedType pt
                && pt.genericClass() instanceof ClassType classType
                && isDeferredOrOptionalWrapperName(classType.declaration().name());
    }

    private static boolean isDeferredOrOptionalWrapperName(String fqn) {
        return "java.util.Optional".equals(fqn)
                || "jakarta.inject.Provider".equals(fqn)
                || "java.util.function.Supplier".equals(fqn);
    }

    static boolean hasConfigPropertiesAnnotation(ClassInfo classInfo) {
        for (AnnotationInfo anno : classInfo.annotations()) {
            if (ConfigProperties.class.getName().equals(anno.name())) {
                return true;
            }
        }
        return false;
    }

    static String resolvePropertyName(AnnotationInfo cfg, DeclarationInfo declaration) {
        Map<String, AnnotationMember> members = cfg.members();
        AnnotationMember name = members.get("name");
        String configured = name != null && name.isString() ? name.asString() : null;
        if (configured != null && !configured.isBlank() && !ConfigProperty.UNCONFIGURED_VALUE.equals(configured)) {
            return configured;
        }
        // §6.1: default name is <declaring-class-FQN>.<member-name>
        // (canonical name, so '.' instead of '$' for nested classes).
        return switch (declaration.kind()) {
            case FIELD -> {
                var fi = declaration.asField();
                yield canonicalize(fi.declaringClass().name()) + "." + fi.name();
            }
            case PARAMETER -> {
                var pi = declaration.asParameter();
                String clsBin = pi.declaringMethod().declaringClass().name();
                yield canonicalize(clsBin) + "." + pi.name();
            }
            default -> "";
        };
    }

    private static String canonicalize(String binaryName) {
        // Converts binary name (FQN with '$' for nested classes) to canonical name.
        return binaryName == null ? "" : binaryName.replace('$', '.');
    }

    static boolean hasConfiguredDefault(AnnotationInfo cfg) {
        AnnotationMember member = cfg.members().get("defaultValue");
        if (member == null || !member.isString()) {
            return false;
        }
        return !ConfigProperty.UNCONFIGURED_VALUE.equals(member.asString());
    }

    private static void validateDeploymentContract(
            AnnotationInfo cfg,
            DeclarationInfo declaration,
            Type ipType,
            Messages messages
    ) {
        if (isDeferredOrOptionalWrapper(ipType)) {
            return;
        }
        if (ipType instanceof ParameterizedType || ipType instanceof jakarta.enterprise.lang.model.types.ArrayType) {
            return;
        }

        Class<?> targetType = toRuntimeClass(ipType);
        if (targetType == null || targetType == ConfigValue.class || targetType.isArray()) {
            return;
        }
        // OptionalInt/Long/Double are semantically "absent" when property is
        // missing, so no deployment failure (see Optional<T> §6.1).
        if (targetType == OptionalInt.class
                || targetType == OptionalLong.class
                || targetType == OptionalDouble.class) {
            return;
        }

        String key = resolvePropertyName(cfg, declaration);
        String defaultValue = configuredDefaultValue(cfg);
        Config config = ConfigProvider.getConfig();

        ConfigValue configValue = config.getConfigValue(key);
        String resolved = configValue != null ? configValue.getValue() : null;
        boolean rawPresent = resolved != null && !resolved.isEmpty();

        try {
            if (rawPresent) {
                Optional<?> converted = config.getOptionalValue(key, targetType);
                if (converted.isEmpty()) {
                    messages.error("Cannot convert required config property '" + key + "' to "
                            + targetType.getName() + " (converter returned null)", declaration);
                }
                return;
            }

            if (defaultValue == null) {
                messages.error("Missing required config property '" + key + "' for type "
                        + targetType.getName(), declaration);
                return;
            }

            if (defaultValue.isEmpty()) {
                messages.error("Empty defaultValue is invalid for required config property '" + key + "'",
                        declaration);
                return;
            }

            Object convertedDefault = config.getConverter(targetType)
                    .orElseThrow(() -> new IllegalStateException("No converter for " + targetType.getName()))
                    .convert(defaultValue);
            if (convertedDefault == null) {
                messages.error("defaultValue for config property '" + key + "' converts to null", declaration);
            }
        } catch (RuntimeException e) {
            messages.error("Invalid @ConfigProperty for key '" + key + "': " + e.getMessage(), declaration);
        }
    }

    private static String configuredDefaultValue(AnnotationInfo cfg) {
        AnnotationMember member = cfg.members().get("defaultValue");
        if (member == null || !member.isString()) {
            return null;
        }
        String value = member.asString();
        return ConfigProperty.UNCONFIGURED_VALUE.equals(value) ? null : value;
    }

    /**
     * Non-throwing variant of {@link #toRuntimeClass(Type)} used to test whether
     * a type can be represented as {@code Class<?>}.
     */
    private static Class<?> toRuntimeClassOrNull(Type type) {
        try {
            return toRuntimeClass(type);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Class<?> toRuntimeClass(Type type) {
        if (type instanceof jakarta.enterprise.lang.model.types.PrimitiveType pt) {
            return switch (pt.primitiveKind()) {
                case BOOLEAN -> boolean.class;
                case BYTE -> byte.class;
                case SHORT -> short.class;
                case INT -> int.class;
                case LONG -> long.class;
                case FLOAT -> float.class;
                case DOUBLE -> double.class;
                case CHAR -> char.class;
            };
        }
        if (type instanceof ClassType ct) {
            return loadClass(ct.declaration().name());
        }
        if (type instanceof jakarta.enterprise.lang.model.types.ArrayType at) {
            Class<?> component = toRuntimeClass(at.componentType());
            return component == null ? null : Array.newInstance(component, 0).getClass();
        }
        if (type instanceof ParameterizedType pt && pt.genericClass() instanceof ClassType ct) {
            return loadClass(ct.declaration().name());
        }
        return null;
    }

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {
                return null;
            }
        }
    }
}
