package io.vidocq.cervantes.cdi;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanBuilder;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.types.ArrayType;
import jakarta.enterprise.lang.model.types.ClassType;
import jakarta.enterprise.lang.model.types.ParameterizedType;
import jakarta.enterprise.lang.model.types.PrimitiveType;
import jakarta.enterprise.lang.model.types.Type;
import org.eclipse.microprofile.jwt.Claim;

import java.lang.reflect.Array;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Build Compatible Extension synthétisant un bean par type rencontré au site d'injection
 * {@code @Claim} (MicroProfile JWT 2.1). Les membres {@code value}/{@code standard} de
 * {@code @Claim} étant {@code @Nonbinding}, un seul {@code SyntheticBean} qualifié {@code @Claim}
 * par type couvre tous les sites — le nom effectif du claim est résolu au runtime par
 * {@link ClaimSyntheticCreator} depuis l'{@code InjectionPoint}.
 *
 * <p>Implémentée en {@code @Registration(types = Object.class)} (et non {@code @Validation}) car
 * CDI Lite 4.1 interdit {@code BeanInfo} comme paramètre des méthodes {@code @Validation}. Calque
 * {@code io.vidocq.ravel.cdi.ConfigCdiExtension} (débloqué par Vauban VAU-BCE-001).</p>
 */
public class CervantesClaimExtension implements BuildCompatibleExtension {

    /** Types {@code @Claim} collectés en {@code @Registration}, dédupliqués par représentation textuelle. */
    private final Map<String, Type> claimTypes = new LinkedHashMap<>();

    @Registration(types = Object.class)
    public void collectClaimInjectionPoints(BeanInfo beanInfo) {
        for (InjectionPointInfo injectionPoint : beanInfo.injectionPoints()) {
            if (hasClaimQualifier(injectionPoint)) {
                Type type = injectionPoint.type();
                claimTypes.putIfAbsent(type.toString(), type);
            }
        }
    }

    @Synthesis
    public void synthesizeClaimBeans(SyntheticComponents components, Types types) {
        Set<String> registered = new HashSet<>();
        for (Type type : claimTypes.values()) {
            // Primitifs (boolean, int, long, …) : on enregistre le bean au type boxé — Weld/Vauban
            // résout une injection primitive depuis un bean wrapper via auto-unboxing (cf.
            // ConfigCdiExtension de Ravel). Nécessite que le lang-model expose bien un PrimitiveType
            // pour un point d'injection primitif (corrigé dans Vauban — TypeMapper / VAU-INJ-PRIM).
            Class<?> effectiveClass = type instanceof PrimitiveType pt ? boxPrimitive(pt.primitiveKind()) : null;
            if (effectiveClass != null) {
                if (!registered.add(effectiveClass.getName())) {
                    continue;
                }
                addClaimBean(components, effectiveClass);
                continue;
            }
            if (!registered.add(type.toString())) {
                continue;
            }
            boolean parameterized = type instanceof ParameterizedType;
            Class<?> runtimeClass = parameterized ? null : toRuntimeClassOrNull(type);
            if (runtimeClass != null) {
                addClaimBean(components, runtimeClass);
            } else {
                // Types paramétrés (ClaimValue<T>, Optional<T>, Provider<T>, Set<String>, …) : on
                // conserve la Type lang-model, sinon le paramètre générique est perdu.
                components.addBean(Object.class)
                        .type(type)
                        .qualifier(Claim.class)
                        .scope(Dependent.class)
                        .createWith(ClaimSyntheticCreator.class);
            }
        }
    }

    /** Enregistre un {@code SyntheticBean} {@code @Claim} {@code @Dependent} de type {@code beanClass}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addClaimBean(SyntheticComponents components, Class<?> beanClass) {
        ((SyntheticBeanBuilder) components.addBean(beanClass))
                .type(beanClass)
                .qualifier(Claim.class)
                .scope(Dependent.class)
                .createWith(ClaimSyntheticCreator.class);
    }

    private static boolean hasClaimQualifier(InjectionPointInfo injectionPoint) {
        for (AnnotationInfo qualifier : injectionPoint.qualifiers()) {
            if (Claim.class.getName().equals(qualifier.name())) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> boxPrimitive(PrimitiveType.PrimitiveKind kind) {
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

    private static Class<?> toRuntimeClassOrNull(Type type) {
        try {
            return toRuntimeClass(type);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Class<?> toRuntimeClass(Type type) {
        if (type instanceof PrimitiveType pt) {
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
        if (type instanceof ArrayType at) {
            Class<?> component = toRuntimeClass(at.componentType());
            return component == null ? null : Array.newInstance(component, 0).getClass();
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
