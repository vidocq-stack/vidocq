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
package io.vidocq.heisenberg.internal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.lang.reflect.GenericArrayType;
import java.util.Arrays;
import org.eclipse.microprofile.faulttolerance.ExecutionContext;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.FallbackHandler;
import org.eclipse.microprofile.faulttolerance.exceptions.FaultToleranceDefinitionException;

public final class FallbackResolver {

    public Object resolve(Fallback fallback, Object target, Method guardedMethod, Object[] parameters, Throwable failure)
            throws Exception {
        Class<?> beanClass = resolveUserClass(target.getClass());
        validateDefinition(beanClass, guardedMethod, fallback);

        String fallbackMethodName = fallback.fallbackMethod();
        if (!fallbackMethodName.isEmpty()) {
            return invokeFallbackMethod(target, guardedMethod, fallbackMethodName, parameters);
        }

        FallbackHandler<?> handler = instantiateHandler(fallback.value());
        Object result = handler.handle(new DefaultExecutionContext(guardedMethod, parameters, failure));
        ensureReturnTypeCompatible(guardedMethod, result);
        return result;
    }

    public void validateDefinition(Class<?> beanClass, Method guardedMethod, Fallback fallback) {
        boolean hasFallbackMethod = !fallback.fallbackMethod().isEmpty();
        boolean hasFallbackHandler = fallback.value() != Fallback.DEFAULT.class;

        ensureExactlyOneFallbackStrategy(hasFallbackMethod, hasFallbackHandler);

        if (hasFallbackMethod) {
            resolveFallbackMethodHandle(beanClass, guardedMethod, fallback.fallbackMethod());
        } else {
            instantiateHandler(fallback.value());
            validateHandlerReturnType(guardedMethod, fallback.value());
        }
    }

    public void validateDefinition(Class<?> beanClass, Method guardedMethod, FallbackConfig config) {
        String fallbackMethod = config.fallbackMethod();
        Class<? extends FallbackHandler<?>> fallbackHandler = config.fallbackHandlerClass();

        boolean hasFallbackMethod = fallbackMethod != null && !fallbackMethod.isEmpty();
        boolean hasFallbackHandler = fallbackHandler != null && fallbackHandler != Fallback.DEFAULT.class;

        ensureExactlyOneFallbackStrategy(hasFallbackMethod, hasFallbackHandler);

        if (hasFallbackMethod) {
            resolveFallbackMethodHandle(beanClass, guardedMethod, fallbackMethod);
        } else {
            instantiateHandler(fallbackHandler);
            validateHandlerReturnType(guardedMethod, fallbackHandler);
        }
    }

    public Object resolve(FallbackConfig config, Object target, Method guardedMethod, Object[] parameters, Throwable failure)
            throws Exception {
        Class<?> beanClass = resolveUserClass(target.getClass());
        validateDefinition(beanClass, guardedMethod, config);

        String fallbackMethodName = config.fallbackMethod();
        if (fallbackMethodName != null && !fallbackMethodName.isEmpty()) {
            return invokeFallbackMethod(target, guardedMethod, fallbackMethodName, parameters);
        }

        FallbackHandler<?> handler = instantiateHandler(config.fallbackHandlerClass());
        Object result = handler.handle(new DefaultExecutionContext(guardedMethod, parameters, failure));
        ensureReturnTypeCompatible(guardedMethod, result);
        return result;
    }

    private Object invokeFallbackMethod(Object target, Method guardedMethod, String fallbackMethodName, Object[] parameters)
            throws Exception {
        MethodHandle methodHandle = resolveFallbackMethodHandle(resolveUserClass(target.getClass()), guardedMethod, fallbackMethodName);
        Object[] args = parameters == null ? new Object[0] : parameters;
        try {
            return methodHandle.bindTo(target).invokeWithArguments(args);
        } catch (Throwable failure) {
            if (failure instanceof Exception exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(failure);
        }
    }

    private MethodHandle resolveFallbackMethodHandle(Class<?> beanClass, Method guardedMethod, String fallbackMethodName) {
        Method fallbackMethod = findCompatibleFallbackMethod(beanClass, guardedMethod, fallbackMethodName);
        if (fallbackMethod == null) {
            throw new FaultToleranceDefinitionException(
                    "Invalid fallbackMethod '" + fallbackMethodName + "' for " + guardedMethod,
                    new NoSuchMethodException(fallbackMethodName)
            );
        }

        try {
            Class<?> owner = fallbackMethod.getDeclaringClass();
            MethodType methodType = MethodType.methodType(fallbackMethod.getReturnType(), fallbackMethod.getParameterTypes());
            MethodHandles.Lookup privateLookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
            if ((fallbackMethod.getModifiers() & java.lang.reflect.Modifier.PRIVATE) != 0) {
                return privateLookup.findSpecial(owner, fallbackMethodName, methodType, owner);
            }
            return privateLookup.findVirtual(owner, fallbackMethodName, methodType);
        } catch (NoSuchMethodException | IllegalAccessException failure) {
            throw new FaultToleranceDefinitionException(
                    "Invalid fallbackMethod '" + fallbackMethodName + "' for " + guardedMethod,
                    failure
            );
        }
    }

    private Method findCompatibleFallbackMethod(Class<?> beanClass, Method guardedMethod, String fallbackMethodName) {
        Class<?> current = beanClass;
        while (current != null && current != Object.class) {
            for (Method candidate : current.getDeclaredMethods()) {
                if (!candidate.getName().equals(fallbackMethodName)) {
                    continue;
                }
                if (!isFallbackMethodVisibleFrom(beanClass, candidate)) {
                    continue;
                }
                if (!isMethodCompatible(guardedMethod, candidate)) {
                    continue;
                }
                return candidate;
            }
            Method fromInterface = findCompatibleFallbackMethodOnInterfaces(current, beanClass, guardedMethod, fallbackMethodName);
            if (fromInterface != null) {
                return fromInterface;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private Method findCompatibleFallbackMethodOnInterfaces(
            Class<?> type,
            Class<?> beanClass,
            Method guardedMethod,
            String fallbackMethodName
    ) {
        for (Class<?> itf : type.getInterfaces()) {
            for (Method candidate : itf.getMethods()) {
                if (!candidate.getName().equals(fallbackMethodName)) {
                    continue;
                }
                if (!isFallbackMethodVisibleFrom(beanClass, candidate)) {
                    continue;
                }
                if (!isMethodCompatible(guardedMethod, candidate)) {
                    continue;
                }
                return candidate;
            }
            Method nested = findCompatibleFallbackMethodOnInterfaces(itf, beanClass, guardedMethod, fallbackMethodName);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private boolean isFallbackMethodVisibleFrom(Class<?> beanClass, Method fallbackMethod) {
        int modifiers = fallbackMethod.getModifiers();
        if (Modifier.isAbstract(modifiers)) {
            return false;
        }
        Class<?> owner = fallbackMethod.getDeclaringClass();
        if (Modifier.isPublic(modifiers)) {
            return true;
        }
        if (Modifier.isPrivate(modifiers)) {
            return owner == beanClass;
        }
        String beanPackage = beanClass.getPackageName();
        String ownerPackage = owner.getPackageName();
        if (Modifier.isProtected(modifiers)) {
            return ownerPackage.equals(beanPackage) || owner.isAssignableFrom(beanClass);
        }
        // package-private
        return ownerPackage.equals(beanPackage);
    }

    private boolean isMethodCompatible(Method guardedMethod, Method fallbackMethod) {
        Class<?>[] guardedParams = guardedMethod.getParameterTypes();
        Class<?>[] fallbackParams = fallbackMethod.getParameterTypes();
        if (guardedParams.length != fallbackParams.length) {
            return false;
        }
        for (int i = 0; i < guardedParams.length; i++) {
            if (!fallbackParams[i].isAssignableFrom(guardedParams[i])) {
                return false;
            }
        }
        if (!guardedMethod.getReturnType().isAssignableFrom(fallbackMethod.getReturnType())) {
            return false;
        }

        Type[] guardedGenericParams = guardedMethod.getGenericParameterTypes();
        Type[] fallbackGenericParams = fallbackMethod.getGenericParameterTypes();
        for (int i = 0; i < guardedGenericParams.length; i++) {
            if (isConcreteGenericMismatch(guardedGenericParams[i], fallbackGenericParams[i])) {
                return false;
            }
        }
        return true;
    }

    private boolean isConcreteGenericMismatch(Type guarded, Type fallback) {
        if (containsTypeVariable(guarded) || containsTypeVariable(fallback)) {
            return false;
        }
        return !typeEquivalent(guarded, fallback);
    }

    private boolean containsTypeVariable(Type type) {
        if (type instanceof TypeVariable<?>) {
            return true;
        }
        if (type instanceof ParameterizedType p) {
            for (Type arg : p.getActualTypeArguments()) {
                if (containsTypeVariable(arg)) {
                    return true;
                }
            }
            return false;
        }
        if (type instanceof GenericArrayType g) {
            return containsTypeVariable(g.getGenericComponentType());
        }
        if (type instanceof WildcardType w) {
            for (Type bound : w.getUpperBounds()) {
                if (containsTypeVariable(bound)) {
                    return true;
                }
            }
            for (Type bound : w.getLowerBounds()) {
                if (containsTypeVariable(bound)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean typeEquivalent(Type left, Type right) {
        if (left.equals(right)) {
            return true;
        }
        if (left instanceof ParameterizedType lpt && right instanceof ParameterizedType rpt) {
            if (!typeEquivalent(lpt.getRawType(), rpt.getRawType())) {
                return false;
            }
            Type[] la = lpt.getActualTypeArguments();
            Type[] ra = rpt.getActualTypeArguments();
            if (la.length != ra.length) {
                return false;
            }
            for (int i = 0; i < la.length; i++) {
                if (!typeEquivalent(la[i], ra[i])) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof WildcardType lwt && right instanceof WildcardType rwt) {
            return Arrays.equals(lwt.getUpperBounds(), rwt.getUpperBounds())
                    && Arrays.equals(lwt.getLowerBounds(), rwt.getLowerBounds());
        }
        return false;
    }

    private void validateHandlerReturnType(Method guardedMethod, Class<? extends FallbackHandler<?>> handlerClass) {
        Type[] interfaces = handlerClass.getGenericInterfaces();
        for (Type itf : interfaces) {
            if (!(itf instanceof ParameterizedType p)) {
                continue;
            }
            if (!(p.getRawType() instanceof Class<?> raw) || raw != FallbackHandler.class) {
                continue;
            }
            Type fallbackType = p.getActualTypeArguments()[0];
            if (fallbackType instanceof Class<?> clazz) {
                if (!boxed(guardedMethod.getReturnType()).isAssignableFrom(clazz)) {
                    throw new FaultToleranceDefinitionException(
                            "FallbackHandler return type mismatch for " + guardedMethod
                    );
                }
            }
            return;
        }
    }

    private Class<?> resolveUserClass(Class<?> runtimeClass) {
        Class<?> current = runtimeClass;
        while (current.getName().contains("$$Intercepted") && current.getSuperclass() != null) {
            current = current.getSuperclass();
        }
        return current;
    }

    private FallbackHandler<?> instantiateHandler(Class<? extends FallbackHandler<?>> handlerClass) {
        FallbackHandler<?> cdiManaged = instantiateHandlerFromCdi(handlerClass);
        if (cdiManaged != null) {
            return cdiManaged;
        }
        try {
            return handlerClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new FaultToleranceDefinitionException(
                    "FallbackHandler must expose an accessible no-arg constructor: " + handlerClass.getName(),
                    failure
            );
        }
    }

    @SuppressWarnings("unchecked")
    private FallbackHandler<?> instantiateHandlerFromCdi(Class<? extends FallbackHandler<?>> handlerClass) {
        try {
            Class<?> cdiClass = Class.forName("jakarta.enterprise.inject.spi.CDI");
            Object cdi = cdiClass.getMethod("current").invoke(null);
            Object instance = cdi.getClass()
                    .getMethod("select", Class.class, java.lang.annotation.Annotation[].class)
                    .invoke(cdi, handlerClass, new java.lang.annotation.Annotation[0]);
            Object resolved = instance.getClass().getMethod("get").invoke(instance);
            if (resolved instanceof FallbackHandler<?> handler) {
                return handler;
            }
        } catch (Throwable ignored) {
            // CDI absent/inactive: fall back to local reflective instantiation.
        }
        return null;
    }

    private void ensureReturnTypeCompatible(Method guardedMethod, Object fallbackValue) {
        Class<?> returnType = guardedMethod.getReturnType();
        if (returnType == void.class) {
            return;
        }
        if (fallbackValue == null) {
            if (returnType.isPrimitive()) {
                throw new FaultToleranceDefinitionException(
                        "Fallback returned null for primitive return type " + returnType.getName()
                );
            }
            return;
        }

        Class<?> expectedType = boxed(returnType);
        if (!expectedType.isInstance(fallbackValue)) {
            throw new FaultToleranceDefinitionException(
                    "Fallback return type mismatch, expected " + expectedType.getName()
                            + " but got " + fallbackValue.getClass().getName()
            );
        }
    }

    private void ensureExactlyOneFallbackStrategy(boolean hasFallbackMethod, boolean hasFallbackHandler) {
        if (hasFallbackMethod == hasFallbackHandler) {
            throw new FaultToleranceDefinitionException(
                    "@Fallback must define exactly one strategy between fallbackMethod and value()"
            );
        }
    }

    private Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == boolean.class) return Boolean.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return Void.class;
    }

    private static final class DefaultExecutionContext implements ExecutionContext {
        private final Method method;
        private final Object[] parameters;
        private final Throwable failure;

        private DefaultExecutionContext(Method method, Object[] parameters, Throwable failure) {
            this.method = method;
            this.parameters = parameters;
            this.failure = failure;
        }

        @Override
        public Method getMethod() {
            return method;
        }

        @Override
        public Object[] getParameters() {
            return parameters == null ? new Object[0] : parameters.clone();
        }

        @Override
        public Throwable getFailure() {
            return failure;
        }
    }
}
