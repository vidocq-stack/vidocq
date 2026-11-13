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
 * Build Compatible Extension synthesizing one bean per type encountered at a {@code @Claim}
 * injection point (MicroProfile JWT 2.2). The {@code value}/{@code standard} members of
 * {@code @Claim} being {@code @Nonbinding}, one {@code SyntheticBean} qualified {@code @Claim}
 * per type covers all sites — the actual claim name is resolved at runtime by
 * {@link ClaimSyntheticCreator} from the {@code InjectionPoint}.
 *
 * <p> Implemented in {@code @Registration(types = Object.class)} (not {@code @Validation}) because
 * CDI Lite 4.1 prohibits {@code BeanInfo} as a parameter of {@code @Validation} methods. Modelled
 * on {@code io.vidocq.ravel.cdi.ConfigCdiExtension} (made possible by Vauban VAU-BCE-001).</p>
 *
 * <p>The extension reads nothing of the deployment's environment, so it does the same work
 * whenever a container runs it, while the application builds or when it starts. The
 * {@code mp.jwt.*} configuration is checked at each start by a {@code Startup} observer,
 * {@link io.vidocq.cervantes.cdi.internal.JwtConfigurationCheck} (cervantes#52).</p>
 */
public class CervantesClaimExtension implements BuildCompatibleExtension {

    /** {@code @Claim} types collected in {@code @Registration}, deduplicated by textual representation. */
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
        for (Type type : effectiveClaimTypes().values()) {
            //Primitives (boolean, int, long,...): the bean is recorded in boxed type — Weld/Vauban
            //solves a primitive injection from a bean wrapper via auto-unboxing (cf.
            //ConfigCdiRavel Extension). Requires the lang-model to exhibit a PrimitiveType
            //for a primitive injection site (corrected in Vauban — TypeMapper / VAU-INJ-PRIM).
            if (type instanceof PrimitiveType pt) {
                Class<?> boxed = boxPrimitive(pt.primitiveKind());
                if (registered.add(boxed.getName())) {
                    addClaimBean(components, boxed);
                }
            } else {
                registerForType(components, registered, type);
            }
        }
    }

    /**
     * First pass: unwrap Provider&lt;T&gt;/Instance&lt;T&gt; → collect the effective types to
     * register. CDI/Vauban strips Provider&lt;T&gt; and Instance&lt;T&gt; wrappers and looks up a bean of
     * type T directly (CDI spec §6.6). We must register a synthetic @Claim bean for T, not for
     * Provider&lt;T&gt;/Instance&lt;T&gt;. ClaimValue&lt;T&gt;, Optional&lt;T&gt;, Supplier&lt;T&gt; are NOT hit —
     * ClaimSyntheticCreator inspects the full InjectionPoint type and handles the wrapping.
     */
    private Map<String, Type> effectiveClaimTypes() {
        Map<String, Type> effectiveTypes = new LinkedHashMap<>();
        for (Type type : claimTypes.values()) {
            if (type instanceof ParameterizedType pt && isLookupWrapper(pt)) {
                // Unwrap: register a bean for the inner type T, never for Provider<T>/Instance<T> itself
                if (!pt.typeArguments().isEmpty()) {
                    Type innerType = pt.typeArguments().get(0);
                    effectiveTypes.putIfAbsent(innerType.toString(), innerType);
                }
            } else {
                effectiveTypes.put(type.toString(), type);
            }
        }
        return effectiveTypes;
    }

    private static boolean isLookupWrapper(ParameterizedType pt) {
        String rawName = rawTypeName(pt);
        return "jakarta.inject.Provider".equals(rawName) || "jakarta.enterprise.inject.Instance".equals(rawName);
    }

    private static void registerForType(SyntheticComponents components, Set<String> registered, Type type) {
        if (!registered.add(type.toString())) {
            return;
        }
        boolean parameterized = type instanceof ParameterizedType;
        Class<?> runtimeClass = parameterized ? null : toRuntimeClassOrNull(type);
        if (runtimeClass != null) {
            addClaimBean(components, runtimeClass);
        } else {
            //Parametric types (ClaimValue<T>, Optional<T>, Set<String>,...): keep
            //the Type lang-model, otherwise the generic parameter is lost.
            components.addBean(Object.class)
                    .type(type)
                    .qualifier(Claim.class)
                    .scope(Dependent.class)
                    .createWith(ClaimSyntheticCreator.class);
        }
    }

    /** Returns the fully-qualified raw type name for a lang-model ParameterizedType, or null. */
    private static String rawTypeName(ParameterizedType pt) {
        // CDI lang-model API: ParameterizedType.genericClass() returns the raw ClassType
        return pt.genericClass().declaration().name();
    }

    /** Registers a {@code @Claim} {@code @Dependent} {@code SyntheticBean} of type {@code beanClass}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addClaimBean(SyntheticComponents components, Class<?> beanClass) {
        SyntheticBeanBuilder builder = components.addBean(beanClass);
        builder.type(beanClass)
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
        } catch (RuntimeException _) {
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
        } catch (ClassNotFoundException _) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException _) {
                return null;
            }
        }
    }
}
