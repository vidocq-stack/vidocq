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
 * BCE CDI qui (1) valide les points d'injection {@code @ConfigProperty} au
 * déploiement et (2) synthétise un bean par type d'IP rencontré pour répondre
 * aux résolutions Weld typées (cf. spec MicroProfile Config 3.1 §6.1).
 *
 * <p>Implémentée en {@code @Registration(types = Object.class)} (et non
 * {@code @Validation}) car CDI Lite 4.1 (§Build Compatible Extensions) interdit
 * {@code BeanInfo} comme paramètre des méthodes {@code @Validation} :
 * {@code LITE-EXTENSION-TRANSLATOR-000002}. {@code @Registration(types=Object.class)}
 * est invoqué une fois par {@link BeanInfo} (tous les beans héritent de
 * {@code Object}) et accepte {@link Messages} pour reporter les erreurs.</p>
 *
 * <p>Phase {@code @Synthesis} : pour chaque type collecté pendant la phase
 * {@code @Registration}, un {@code SyntheticBean} qualifié {@code @ConfigProperty}
 * est enregistré avec {@link ConfigPropertySyntheticCreator} comme creator.
 * Les membres {@code name}/{@code defaultValue} de {@code @ConfigProperty} sont
 * marqués {@code @Nonbinding} dans la spec MP Config, donc un seul bean par
 * type couvre toutes les variantes au site d'injection.</p>
 */
public class ConfigCdiExtension implements BuildCompatibleExtension {

    /**
     * Types collectés pendant {@code @Registration} pour synthèse en
     * {@code @Synthesis}. Dédupliqués par représentation textuelle car
     * {@link Type} ne garantit pas {@code equals}/{@code hashCode}.
     */
    private final Map<String, Type> collectedTypes = new LinkedHashMap<>();

    /**
     * Types annotés avec @ConfigProperties collectés pendant {@code @Registration}.
     * Clé = type string, Valeur = (type, prefix) pair
     */
    private final Map<String, ConfigPropertiesEntry> configPropertiesTypes = new LinkedHashMap<>();

    /**
     * Entry pour stocker un type @ConfigProperties avec son préfixe.
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
     * Fase d'exclusion (@Discovery) qui marque les classes avec @ConfigProperties
     * pour exclure leur découverte en tant que beans managés.
     *
     * <p>Les classes @ConfigProperties ne doivent être injectées que via notre
     * synthetic beans (avec le qualifiant @ConfigProperties), pas via une découverte automatique.</p>
     */
    // Note: L'API CDI 4.1 BC n'expose pas directement d'API pour @Exclude les classes.
    // Cette fonctionnalité doit être gérée via une autre extension.

    @Registration(types = Object.class)
    public void registerConfigPropertyInjectionPoints(BeanInfo beanInfo, Messages messages) {
        for (InjectionPointInfo injectionPoint : beanInfo.injectionPoints()) {
            // Cas 1 : @ConfigProperty
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

            // Cas 2 : @ConfigProperties
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
     * Collecte les classes {@code @ConfigProperties} du contexte de build.
     * Appelé avant {@code @Synthesis}.
     */
    @Registration(types = Object.class)
    public void registerConfigPropertiesClasses(BeanInfo beanInfo, Messages messages) {
        // Cette méthode pourrait être utilisée pour collecter des classes avec @ConfigProperties,
        // mais pour le moment cette logique est déjà gérée dans registerConfigPropertyInjectionPoints
    }

    @Synthesis
    public void synthesizeConfigPropertyBeans(SyntheticComponents components, Types types) {
        // Bean Config @Default — beaucoup de tests TCK injectent simplement
        // {@code @Inject Config config} sans @ConfigProperty, et le producer
        // RavelConfigProducer n'est pas embarqué dans les archives ShrinkWrap.
        components.addBean(org.eclipse.microprofile.config.Config.class)
                .type(org.eclipse.microprofile.config.Config.class)
                .scope(Dependent.class)
                .createWith(ConfigSyntheticCreator.class);

        // Synthétiser les beans @ConfigProperty.
        // Pour les types non paramétrés (Class, Class[], primitives boxés),
        // on utilise la {@code Class<?>} runtime — Weld bind les array types sans
        // problème via {@code .type(Class<?>)} alors que {@code .type(ArrayType lang-model)}
        // peut être ignoré silencieusement (cf. WELD-001408 sur OffsetDateTime[]).
        var registered = new java.util.HashSet<String>();
        for (Type ipType : collectedTypes.values()) {
            Type effectiveType = ipType instanceof jakarta.enterprise.lang.model.types.PrimitiveType pt
                    ? types.of(boxPrimitive(pt.primitiveKind()))
                    : ipType;

            if (!registered.add(effectiveType.toString())) {
                continue;
            }

            // Pour les arrays et class types simples, on utilise la {@code Class<?>}
            // runtime — Weld bind les array types correctement par cette voie alors que
            // {@code .type(ArrayType lang-model)} peut être ignoré (cf. WELD-001408
            // sur OffsetDateTime[]). Pour les types paramétrés (List<X>, Provider<T>,
            // Optional<T>, etc.) on conserve la {@code Type} lang-model — sinon on
            // perd le paramètre générique et plusieurs beans entrent en conflit.
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

        // Synthétiser les beans @ConfigProperties.
        // Spec MP Config 3.1 : @ConfigProperties.prefix est @Nonbinding — donc
        // un seul SyntheticBean par BeanType qualifié @ConfigProperties suffit
        // (le préfixe effectif est résolu à runtime par le creator depuis l'IP).
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
     * Phase placeholder pour future intégration.
     */
    @Synthesis
    public void synthesizeAdditional(SyntheticComponents components, Types types) {
        // Placeholder pour d'autres besoins de synthèse
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addSyntheticConfigPropertyBean(SyntheticComponents components, Class<?> beanClass) {
        // Cast nécessaire car {@code SyntheticComponents.addBean(Class<T>)} renvoie
        // {@code SyntheticBeanBuilder<T>} et le {@code createWith} attend
        // {@code Class<? extends SyntheticBeanCreator<T>>} avec le même T.
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
     * Préfixe brut du @ConfigProperties — peut être {@link ConfigProperties#UNCONFIGURED_PREFIX}
     * (membre absent ⇒ valeur par défaut), {@code ""} (override explicite "pas de préfixe"),
     * ou un préfixe applicatif. Distinguer ces 3 cas est nécessaire pour décider du
     * fallback class-level (§6.4).
     */
    static String extractRawPrefix(AnnotationInfo configPropertiesQual) {
        AnnotationMember prefixMember = configPropertiesQual.members().get("prefix");
        if (prefixMember != null && prefixMember.isString()) {
            return prefixMember.asString();
        }
        return ConfigProperties.UNCONFIGURED_PREFIX;
    }

    /**
     * Validation déploiement pour {@code @ConfigProperties} (§6.4) — vérifie que
     * toutes les propriétés requises sont présentes. Une propriété est dite
     * requise si elle ne dispose d'aucun fallback :
     * <ul>
     *   <li>pas de {@code @ConfigProperty(defaultValue=...)} sur le champ ;</li>
     *   <li>pas de type {@code Optional} / {@code OptionalInt/Long/Double} ;</li>
     *   <li>pas d'initialiseur Java (ex. {@code int port = 9080;}) — détecté en
     *       instanciant le bean et en comparant la valeur à la "zero-value" du
     *       type.</li>
     * </ul>
     * Cible {@link org.eclipse.microprofile.config.tck.broken.ConfigPropertiesMissingPropertyInjectionTest}.
     */
    private static void validateConfigPropertiesDeployment(
            ClassType beanType, String fieldRawPrefix, DeclarationInfo declaration, Messages messages) {
        Class<?> beanClass = loadClass(beanType.declaration().name());
        if (beanClass == null) {
            // Classe non chargeable au déploiement — on laisse la validation runtime gérer.
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
        // Primitives (int, long, boolean, ...) sont valides §6.1 — Weld les
        // résoudra via l'auto-boxing du synthetic bean (bean type primitif).
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
                // Types paramétrés génériques (List<X>, Set<X>, Map<K,V>, etc.)
                // sont également valides en tant que bean type — la conversion
                // se fait à runtime via Converter<T>. On accepte tout PT.
                return true;
            }
            return pt.typeArguments().size() == 1;
        }
        // Array types (jakarta.enterprise.lang.model.types.ArrayType) supportés
        // pour String[], Duration[], etc. (spec §5.4).
        return type instanceof jakarta.enterprise.lang.model.types.ArrayType;
    }

    /**
     * Vrai si le type est un "wrapper" qui rend la propriété sous-jacente
     * non requise au déploiement : {@link java.util.Optional}, {@code jakarta.inject.Provider}
     * (lookup paresseux), {@code java.util.function.Supplier} (lookup paresseux).
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
        // §6.1 — par défaut le nom est <FQN classe déclarante>.<nom membre>
        // (canonical name, donc '.' au lieu de '$' pour les classes imbriquées).
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
        // Convertit le nom binaire (FQN avec '$' pour classes imbriquées) en nom canonique.
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
        // OptionalInt/Long/Double : sémantiquement "absent" si la propriété est
        // manquante — pas d'échec au déploiement (cf. Optional<T> §6.1).
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
     * Variante non lança-exception de {@link #toRuntimeClass(Type)} pour le cas
     * où on veut tester si un type peut être exprimé en {@code Class<?>}
     * (utilisé par {@link #synthesizeConfigPropertyBeans}).
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
