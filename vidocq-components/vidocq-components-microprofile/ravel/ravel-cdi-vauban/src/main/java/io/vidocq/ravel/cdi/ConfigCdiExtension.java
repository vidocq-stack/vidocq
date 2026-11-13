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
import io.vidocq.ravel.cdi.internal.ConfigSyntheticCreator;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.Vetoed;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.DeclarationInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.types.ClassType;
import jakarta.enterprise.lang.model.types.ParameterizedType;
import jakarta.enterprise.lang.model.types.Type;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * CDI Build Compatible Extension that (1) records the {@code @ConfigProperty} and
 * {@code @ConfigProperties} injection points whose value the application needs to start,
 * and registers the check of the configuration against them as a {@code Startup} observer,
 * and (2) synthesises one bean per IP type encountered to satisfy Weld typed resolutions
 * (see MicroProfile Config 3.1 §6.1).
 *
 * <p>This extension reads no configuration. A container may run it where the application
 * is built, and the values a deployment injects come from where it runs —
 * {@code vidocq.properties}, the environment, the system properties of its JVM. What must
 * be checked is recorded as plain text in the parameters of the synthetic observer
 * {@link ConfigStartCheck}, which runs when the application starts, whether or not the
 * container runs this extension again then (ravel#50). The errors that depend on the code
 * alone — an injection type nothing can supply, {@code @ConfigProperties} on a type that
 * is not a class — are reported here, through {@link Messages}.</p>
 *
 * <p>The injection points are read in {@code @Registration(types = Object.class)} (rather
 * than {@code @Validation}) because CDI Lite 4.1 (§Build Compatible Extensions) forbids
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
     * The class types that need no value at start: {@code ConfigValue} describes a missing
     * property, and {@code OptionalInt/Long/Double} are empty when the property is missing
     * (see {@code Optional<T>} in §6.1).
     */
    private static final Set<String> TYPES_WITHOUT_REQUIRED_VALUE = Set.of(
            "org.eclipse.microprofile.config.ConfigValue",
            "java.util.OptionalInt",
            "java.util.OptionalLong",
            "java.util.OptionalDouble");

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
     * True when a bean typed {@link Config} is already registered — e.g.
     * {@link RavelConfigProducer}, listed in this jar's APT-generated bean index
     * and therefore present whenever ravel-cdi-vauban is on the runtime's bean
     * path (the assembled Vidocq runtime). MP Config 3.1 §6.2 mandates exactly
     * one injectable {@code Config}: synthesizing a second {@code @Default}
     * bean would make every {@code @Inject Config} ambiguous.
     */
    private boolean configBeanAlreadyRegistered;

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
     * Binary names of type-level {@code @ConfigProperties} classes vetoed during
     * {@code @Enhancement}, handed to the start check in {@code @Synthesis}.
     */
    private final java.util.Set<String> vetoedConfigPropertiesClassNames = new java.util.LinkedHashSet<>();

    /**
     * The {@code @ConfigProperty} injection points whose value the application needs to start,
     * collected during {@code @Registration} for the start check.
     */
    private final List<RequiredProperty> requiredProperties = new ArrayList<>();

    /**
     * The {@code @ConfigProperties} injection points, collected during {@code @Registration} for
     * the start check.
     */
    private final List<InjectedConfigProperties> injectedConfigProperties = new ArrayList<>();

    /**
     * A required {@code @ConfigProperty} injection point, as the start check reads it.
     *
     * @param key          the property key
     * @param type         the name of the type the value converts to: a class name, or a primitive type name
     * @param defaultValue the {@code defaultValue}, or {@link ConfigStartCheck#NO_DEFAULT}
     * @param site         where the value is injected
     */
    private record RequiredProperty(String key, String type, String defaultValue, String site) {
    }

    /**
     * A {@code @ConfigProperties} class, as the start check reads it.
     *
     * @param className the binary name of the class
     * @param rawPrefix the prefix written at the injection point, or
     *                  {@link ConfigProperties#UNCONFIGURED_PREFIX} when the class decides. The start
     *                  resolves it on the loaded class: a class the container did not index has no
     *                  annotation in the language model.
     * @param site      where the class is injected; empty for a class that was only discovered
     */
    private record InjectedConfigProperties(String className, String rawPrefix, String site) {
    }

    /**
     * MP Config 3.1 §6.4 — type-level {@code @ConfigProperties} classes must only
     * be injectable through the synthetic beans (whose creator resolves the
     * per-injection-point prefix), never as regular managed beans: the class
     * carries the {@code @ConfigProperties} qualifier, so leaving it discovered
     * makes every {@code @Inject @ConfigProperties} ambiguous. On the Weld path
     * {@link ConfigPropertiesExclusionExtension} vetoes them via
     * {@code ProcessAnnotatedType.veto()}; portable extensions never run on CDI
     * Lite runtimes (Vauban), so the BCE adds {@code @Vetoed} itself. The double
     * veto under Weld is harmless.
     */
    @Enhancement(types = Object.class, withSubtypes = true, withAnnotations = ConfigProperties.class)
    public void vetoConfigPropertiesClasses(ClassConfig classConfig) {
        if (!classConfig.info().hasAnnotation(ConfigProperties.class)) {
            return;
        }
        vetoedConfigPropertiesClassNames.add(classConfig.info().name());
        classConfig.addAnnotation(Vetoed.class);
    }

    @Registration(types = Object.class)
    public void registerConfigPropertyInjectionPoints(BeanInfo beanInfo, Messages messages) {
        for (InjectionPointInfo injectionPoint : beanInfo.injectionPoints()) {
            AnnotationInfo configPropertyQual = findConfigPropertyQualifier(injectionPoint);
            if (configPropertyQual != null) {
                registerConfigPropertyInjectionPoint(injectionPoint, configPropertyQual, messages);
            } else {
                AnnotationInfo configPropertiesQual = findConfigPropertiesQualifier(injectionPoint);
                if (configPropertiesQual != null) {
                    registerConfigPropertiesInjectionPoint(injectionPoint, configPropertiesQual, messages);
                }
            }
        }
    }

    /** Case 1: an {@code @ConfigProperty} injection point. */
    private void registerConfigPropertyInjectionPoint(
            InjectionPointInfo injectionPoint, AnnotationInfo configPropertyQual, Messages messages) {
        DeclarationInfo declaration = injectionPoint.declaration();
        Type ipType = injectionPoint.type();
        if (!isSupportedType(ipType)) {
            messages.error("Unsupported @ConfigProperty injection type: " + ipType, declaration);
            return;
        }
        String requiredType = requiredScalarTypeName(ipType);
        if (requiredType != null) {
            String defaultValue = configuredDefaultValue(configPropertyQual);
            requiredProperties.add(new RequiredProperty(
                    resolvePropertyName(configPropertyQual, declaration),
                    requiredType,
                    defaultValue != null ? defaultValue : ConfigStartCheck.NO_DEFAULT,
                    injectionSite(declaration)));
        }
        collectedTypes.putIfAbsent(ipType.toString(), ipType);
    }

    /** Case 2: an {@code @ConfigProperties} injection point. */
    private void registerConfigPropertiesInjectionPoint(
            InjectionPointInfo injectionPoint, AnnotationInfo configPropertiesQual, Messages messages) {
        Type ipType = injectionPoint.type();
        if (!(ipType instanceof ClassType ct)) {
            messages.error("@ConfigProperties only supports class types, got: " + ipType,
                    injectionPoint.declaration());
            return;
        }
        String fieldPrefix = extractRawPrefix(configPropertiesQual);
        configPropertiesTypes.putIfAbsent(ipType.toString(),
            new ConfigPropertiesEntry(ipType, fieldPrefix));
        injectedConfigProperties.add(new InjectedConfigProperties(
                ct.declaration().name(), fieldPrefix, injectionSite(injectionPoint.declaration())));
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

    /**
     * Invoked for every bean whose types include {@link Config} (producer or
     * managed). When one exists, {@code @Synthesis} skips the fallback
     * {@code @Default Config} synthetic bean. Under the plain TCK archives
     * (no producer bundled) this never fires and the synthetic bean is kept.
     */
    @Registration(types = Config.class)
    public void trackExistingConfigBean(BeanInfo beanInfo) {
        configBeanAlreadyRegistered = true;
    }

    @Synthesis
    public void synthesizeConfigPropertyBeans(SyntheticComponents components, Types types) {
        // Fallback @Default Config bean — many TCK tests simply inject
        // {@code @Inject Config config} without @ConfigProperty and their
        // archives bundle no producer. Skipped when a Config-typed bean is
        // already registered (RavelConfigProducer via the jar's bean index on
        // the assembled runtime) — two @Default Config beans would make every
        // {@code @Inject Config} ambiguous. Never at build time: the compilation
        // does not see RavelConfigProducer, which ships next to this extension, and
        // the container start runs this extension again knowing the deployment (ravel#21).
        if (!configBeanAlreadyRegistered && !runsAtBuildTime()) {
            components.addBean(org.eclipse.microprofile.config.Config.class)
                    .type(org.eclipse.microprofile.config.Config.class)
                    .scope(Dependent.class)
                    .createWith(ConfigSyntheticCreator.class);
        }

        // Synthesise @ConfigProperty beans: for non-parameterized types (Class,
        // Class arrays, boxed primitives) the bean is typed with the runtime class,
        // which Weld binds correctly for arrays, whereas a lang-model array type
        // may be silently ignored (see WELD-001408 for OffsetDateTime arrays).
        var registered = new java.util.HashSet<String>();
        for (Type ipType : collectedTypes.values()) {
            Type effectiveType = ipType instanceof jakarta.enterprise.lang.model.types.PrimitiveType pt
                    ? types.of(boxPrimitive(pt.primitiveKind()))
                    : ipType;

            if (!registered.add(effectiveType.toString())) {
                continue;
            }

            // Arrays and simple class types are typed with their runtime class (see
            // above). Parameterized types such as a List, a Provider or an Optional
            // keep their lang-model type: the runtime class would lose the generic
            // parameter and several beans would then conflict.
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

        registerStartCheck(components);
    }

    /**
     * Registers {@link ConfigStartCheck} as an observer of {@link Startup}, with what
     * {@code @Enhancement} and {@code @Registration} collected as its parameters: the required
     * {@code @ConfigProperty} injection points (§6.1), then the {@code @ConfigProperties} classes
     * (§6.4) — those injected first, then those that were only discovered, which the start checks
     * even when nothing injects them (the TCK's {@code ConfigPropertiesMissingPropertyInjectionTest}
     * bundles such a class and expects a {@code DeploymentException}). Nothing is registered when
     * there is nothing to check.
     *
     * <p>The parameters are {@code String[]} arrays only, which a container that runs this
     * extension at build time records with the observer and hands back at start.</p>
     */
    private void registerStartCheck(SyntheticComponents components) {
        List<InjectedConfigProperties> classes = new ArrayList<>(injectedConfigProperties);
        for (String className : vetoedConfigPropertiesClassNames) {
            classes.add(new InjectedConfigProperties(className, ConfigProperties.UNCONFIGURED_PREFIX, ""));
        }
        if (requiredProperties.isEmpty() && classes.isEmpty()) {
            return;
        }
        components.addObserver(Startup.class)
                .priority(ConfigStartCheck.PRIORITY)
                .withParam(ConfigStartCheck.PROPERTY_KEYS, column(requiredProperties, RequiredProperty::key))
                .withParam(ConfigStartCheck.PROPERTY_TYPES, column(requiredProperties, RequiredProperty::type))
                .withParam(ConfigStartCheck.PROPERTY_DEFAULTS,
                        column(requiredProperties, RequiredProperty::defaultValue))
                .withParam(ConfigStartCheck.PROPERTY_SITES, column(requiredProperties, RequiredProperty::site))
                .withParam(ConfigStartCheck.CLASS_NAMES, column(classes, InjectedConfigProperties::className))
                .withParam(ConfigStartCheck.CLASS_PREFIXES, column(classes, InjectedConfigProperties::rawPrefix))
                .withParam(ConfigStartCheck.CLASS_SITES, column(classes, InjectedConfigProperties::site))
                .observeWith(ConfigStartCheck.class);
    }

    private static <T> String[] column(List<T> rows, Function<T, String> value) {
        return rows.stream().map(value).toArray(String[]::new);
    }

    /**
     * Placeholder phase for future integration.
     */
    @Synthesis
    public void synthesizeAdditional(SyntheticComponents components, Types types) {
        // Placeholder for additional synthesis needs
    }

    private static <T> void addSyntheticConfigPropertyBean(SyntheticComponents components, Class<T> beanClass) {
        components.addBean(beanClass)
                .type(beanClass)
                .qualifier(ConfigProperty.class)
                .scope(Dependent.class)
                .createWith(configPropertyCreator());
    }

    /**
     * {@code SyntheticComponents.addBean(Class<T>)} returns a {@code SyntheticBeanBuilder<T>}
     * whose {@code createWith} expects a creator of the same {@code T}; the one
     * {@link ConfigPropertySyntheticCreator} serves every {@code @ConfigProperty} type.
     */
    @SuppressWarnings("unchecked")
    private static <T> Class<? extends SyntheticBeanCreator<T>> configPropertyCreator() {
        return (Class<? extends SyntheticBeanCreator<T>>) (Class<?>) ConfigPropertySyntheticCreator.class;
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
     * True when the name is the one of a wrapper making the underlying property non-required
     * when the application starts: {@link java.util.Optional}, {@code jakarta.inject.Provider}
     * (lazy lookup), and {@code java.util.function.Supplier} (lazy lookup).
     */
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

    /**
     * The name of the type whose value must be present and convertible when the application
     * starts — a class name, or a primitive type name — or {@code null} when the injection point
     * defers or tolerates a missing value: lazy and optional wrappers, the other parameterized
     * types and the array types, {@code ConfigValue}, and {@code OptionalInt/Long/Double}, which
     * are semantically "absent" when the property is missing (see {@code Optional<T>} in §6.1).
     *
     * <p>A name, not a class: the build may run before the class exists. The start check loads it,
     * and leaves a type it cannot load to the injection itself.</p>
     */
    private static String requiredScalarTypeName(Type ipType) {
        if (ipType instanceof jakarta.enterprise.lang.model.types.PrimitiveType pt) {
            return pt.primitiveKind().name().toLowerCase(Locale.ROOT);
        }
        if (ipType instanceof ClassType ct) {
            String name = ct.declaration().name();
            return TYPES_WITHOUT_REQUIRED_VALUE.contains(name) ? null : name;
        }
        return null;
    }

    /**
     * Where a value is injected, as the start check prints it after its message:
     * {@code Class#field}, or {@code Class#method(parameter)} — {@code <init>} for a constructor.
     */
    private static String injectionSite(DeclarationInfo declaration) {
        return switch (declaration.kind()) {
            case FIELD -> {
                var field = declaration.asField();
                yield field.declaringClass().name() + "#" + field.name();
            }
            case PARAMETER -> {
                var parameter = declaration.asParameter();
                MethodInfo method = parameter.declaringMethod();
                yield method.declaringClass().name() + "#" + (method.isConstructor() ? "<init>" : method.name())
                        + "(" + parameter.name() + ")";
            }
            default -> String.valueOf(declaration);
        };
    }

    /**
     * Whether Vauban runs this extension while the application compiles (ravel#21): the fallback
     * {@code Config} bean is not registered then, see {@link #synthesizeConfigPropertyBeans}.
     * Nothing else depends on the phase: this extension reads no configuration, at build time or
     * later (ravel#50).
     *
     * <p>{@code io.vidocq.vauban.api} is an optional module ({@code requires static}): without it,
     * another container runs this extension, at deployment.
     */
    private static boolean runsAtBuildTime() {
        try {
            return io.vidocq.vauban.api.ExtensionPhase.isBuildTime();
        } catch (NoClassDefFoundError _) {
            return false;
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
        } catch (RuntimeException _) {
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
        } catch (ClassNotFoundException _) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException _) {
                return null;
            }
        }
    }
}
