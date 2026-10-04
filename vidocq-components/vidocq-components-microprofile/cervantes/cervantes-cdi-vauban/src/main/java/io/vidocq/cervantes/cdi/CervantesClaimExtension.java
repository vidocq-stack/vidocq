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
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.Registration;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanBuilder;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.build.compatible.spi.Types;
import jakarta.enterprise.inject.build.compatible.spi.Validation;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.types.ArrayType;
import jakarta.enterprise.lang.model.types.ClassType;
import jakarta.enterprise.lang.model.types.ParameterizedType;
import jakarta.enterprise.lang.model.types.PrimitiveType;
import jakarta.enterprise.lang.model.types.Type;
import io.vidocq.cervantes.cdi.internal.JwtAuthConfigProducer;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.jwt.Claim;

import java.lang.reflect.Array;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
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

    /**
     * Fails the container start on an invalid {@code mp.jwt.*} configuration (unrecognised
     * {@code mp.jwt.verify.publickey.algorithm}, unreadable key, and so on) instead of at the first
     * injection of the {@code @Dependent} validator. A runtime {@code Startup} observer cannot do
     * this: Vauban swallows exceptions thrown from it.
     *
     * <p>Nothing happens when:</p>
     * <ul>
     * <li>the extension runs at build time (Vauban's annotation processor runs every phase while the
     * application compiles, and the configuration found then is the build machine's, not the one the
     * application will run with): the check is left to the container start;</li>
     * <li>no verification key is configured (MP-JWT is off);</li>
     * <li>no MicroProfile Config implementation is present, since nothing can then configure a
     * key. Any other failure while the Config implementation boots is reported as an error.</li>
     * </ul>
     */
    @Validation
    public void validateJwtConfiguration(Messages messages) {
        if (BuildPhase.isBuildTime() || !isConfigImplementationAvailable()) {
            return;
        }
        try {
            JwtAuthConfigProducer.createValidator(ConfigProvider.getConfig());
        } catch (RuntimeException e) {
            messages.error(e);
        }
    }

    /**
     * True unless there is no MicroProfile Config implementation at all: the API reports that with
     * an {@link IllegalStateException}, which is only taken for "absent" when no
     * {@link ConfigProviderResolver} service provider exists either.
     */
    private static boolean isConfigImplementationAvailable() {
        try {
            ConfigProviderResolver.instance();
            return true;
        } catch (IllegalStateException e) {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (ServiceLoader.load(ConfigProviderResolver.class, loader).findFirst().isEmpty()) {
                return false;
            }
            throw e;
        }
    }

    @Synthesis
    public void synthesizeClaimBeans(SyntheticComponents components, Types types) {
        //First pass: unwrap Provider<T>/Instance<T> → collect the effective types to
        // register. CDI/Vauban strips Provider<T> and Instance<T> wrappers and looks up a bean of
        //type T directly (CDI spec §6.6). We must register a synthetic @Claim bean for T, not for
        //Provider<T>/Instance<T>. ClaimValue<T>, Optional<T>, Supplier<T> are NOT hit —
        // ClaimSyntheticCreator inspects the full InjectionPoint type and handles the wrapping.
        Map<String, Type> effectiveTypes = new LinkedHashMap<>();
        for (Type type : claimTypes.values()) {
            if (type instanceof ParameterizedType pt) {
                String rawName = rawTypeName(pt);
                if ("jakarta.inject.Provider".equals(rawName)
                        || "jakarta.enterprise.inject.Instance".equals(rawName)) {
                    // Unwrap: register a bean for the inner type T
                    Type innerType = pt.typeArguments().isEmpty() ? null : pt.typeArguments().get(0);
                    if (innerType != null) {
                        effectiveTypes.putIfAbsent(innerType.toString(), innerType);
                    }
                    // Do NOT add Provider<T>/Instance<T> itself
                    continue;
                }
            }
            effectiveTypes.put(type.toString(), type);
        }

        Set<String> registered = new HashSet<>();
        for (Type type : effectiveTypes.values()) {
            //Primitives (boolean, int, long,...): the bean is recorded in boxed type — Weld/Vauban
            //solves a primitive injection from a bean wrapper via auto-unboxing (cf.
            //ConfigCdiRavel Extension). Requires the lang-model to exhibit a PrimitiveType
            //for a primitive injection site (corrected in Vauban — TypeMapper / VAU-INJ-PRIM).
            Class<?> effectiveClass = type instanceof PrimitiveType pt ? boxPrimitive(pt.primitiveKind()) : null;
            if (effectiveClass != null) {
                if (!registered.add(effectiveClass.getName())) {
                    continue;
                }
                addClaimBean(components, effectiveClass);
                continue;
            }
            registerForType(components, registered, type);
        }
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
