package io.vidocq.heisenberg.internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import org.eclipse.microprofile.faulttolerance.Asynchronous;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;

/**
 * Lecture des annotations Fault Tolerance avec précédence méthode > classe.
 */
public final class AnnotationReader {

    private AnnotationReader() {}

    public static FaultToleranceAnnotations read(Method method) {
        return read(method, method.getDeclaringClass());
    }

    public static FaultToleranceAnnotations read(Method method, Class<?> beanClassHint) {
        Class<?> beanClass = beanClassHint != null ? beanClassHint : method.getDeclaringClass();
        return new FaultToleranceAnnotations(
                select(method, beanClass, Retry.class),
                select(method, beanClass, Timeout.class),
                select(method, beanClass, CircuitBreaker.class),
                select(method, beanClass, Bulkhead.class),
                select(method, beanClass, Fallback.class),
                select(method, beanClass, Asynchronous.class)
        );
    }

    private static <A extends Annotation> A select(Method method, Class<?> beanClass, Class<A> annotationType) {
        A methodLevel = method.getAnnotation(annotationType);
        if (methodLevel != null) {
            return methodLevel;
        }

        // If the intercepted method comes from a superclass/interface, try the concrete bean hierarchy too.
        Method runtimeMethod = findMethod(beanClass, method);
        if (runtimeMethod != null) {
            A runtimeMethodLevel = runtimeMethod.getAnnotation(annotationType);
            if (runtimeMethodLevel != null) {
                return runtimeMethodLevel;
            }
        }

        A classLevel = findClassAnnotation(beanClass, annotationType);
        if (classLevel != null) {
            return classLevel;
        }
        return findClassAnnotation(method.getDeclaringClass(), annotationType);
    }

    private static Method findMethod(Class<?> beanClass, Method interceptedMethod) {
        if (beanClass == null) {
            return null;
        }
        Class<?> current = beanClass;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredMethod(interceptedMethod.getName(), interceptedMethod.getParameterTypes());
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static <A extends Annotation> A findClassAnnotation(Class<?> start, Class<A> annotationType) {
        Class<?> current = start;
        while (current != null && current != Object.class) {
            A found = current.getAnnotation(annotationType);
            if (found != null) {
                return found;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    public record FaultToleranceAnnotations(
            Retry retry,
            Timeout timeout,
            CircuitBreaker circuitBreaker,
            Bulkhead bulkhead,
            Fallback fallback,
            Asynchronous asynchronous
    ) {}
}
