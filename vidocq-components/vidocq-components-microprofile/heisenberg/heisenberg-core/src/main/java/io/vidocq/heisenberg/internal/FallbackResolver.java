package io.vidocq.heisenberg.internal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import org.eclipse.microprofile.faulttolerance.ExecutionContext;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.FallbackHandler;
import org.eclipse.microprofile.faulttolerance.exceptions.FaultToleranceDefinitionException;

public final class FallbackResolver {

    public Object resolve(Fallback fallback, Object target, Method guardedMethod, Object[] parameters, Throwable failure)
            throws Exception {
        validateDefinition(target.getClass(), guardedMethod, fallback);

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

        if (hasFallbackMethod == hasFallbackHandler) {
            throw new FaultToleranceDefinitionException(
                    "@Fallback must define exactly one strategy between fallbackMethod and value()"
            );
        }

        if (hasFallbackMethod) {
            resolveFallbackMethodHandle(beanClass, guardedMethod, fallback.fallbackMethod());
        } else {
            instantiateHandler(fallback.value());
        }
    }

    private Object invokeFallbackMethod(Object target, Method guardedMethod, String fallbackMethodName, Object[] parameters)
            throws Exception {
        MethodHandle methodHandle = resolveFallbackMethodHandle(target.getClass(), guardedMethod, fallbackMethodName);
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
        MethodType fallbackSignature = MethodType.methodType(guardedMethod.getReturnType(), guardedMethod.getParameterTypes());
        try {
            MethodHandles.Lookup privateLookup = MethodHandles.privateLookupIn(beanClass, MethodHandles.lookup());
            return privateLookup.findVirtual(beanClass, fallbackMethodName, fallbackSignature);
        } catch (NoSuchMethodException | IllegalAccessException failure) {
            throw new FaultToleranceDefinitionException(
                    "Invalid fallbackMethod '" + fallbackMethodName + "' for " + guardedMethod,
                    failure
            );
        }
    }

    private FallbackHandler<?> instantiateHandler(Class<? extends FallbackHandler<?>> handlerClass) {
        try {
            return handlerClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new FaultToleranceDefinitionException(
                    "FallbackHandler must expose an accessible no-arg constructor: " + handlerClass.getName(),
                    failure
            );
        }
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
