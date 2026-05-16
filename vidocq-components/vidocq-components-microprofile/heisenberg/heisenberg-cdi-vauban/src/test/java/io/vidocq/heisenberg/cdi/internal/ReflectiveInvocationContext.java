package io.vidocq.heisenberg.cdi.internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import jakarta.interceptor.InvocationContext;

/**
 * Utilitaire de test partagé : implémente {@link InvocationContext} via reflection sur une méthode
 * réelle d'un bean cible. Permet d'invoquer {@link FaultToleranceInterceptor#around(InvocationContext)}
 * sans dépendre d'un container CDI complet.
 *
 * <p>Utilisé par tous les {@code *IntegrationTest} de {@code heisenberg-cdi-vauban}.
 * Conforme AGENTS.md : pas de {@code setAccessible(true)} — les méthodes interceptées doivent rester
 * accessibles depuis le package de test (package-private ou public).</p>
 */
final class ReflectiveInvocationContext implements InvocationContext {

    private final Object target;
    private final Method method;
    private Object[] parameters;
    private final Map<String, Object> contextData = new HashMap<>();

    ReflectiveInvocationContext(Object target, Method method, Object[] parameters) {
        this.target = target;
        this.method = method;
        this.parameters = parameters;
    }

    @Override public Object getTarget() { return target; }
    @Override public Object getTimer() { return null; }
    @Override public Method getMethod() { return method; }
    @Override public Constructor<?> getConstructor() { return null; }
    @Override public Object[] getParameters() { return parameters; }
    @Override public void setParameters(Object[] params) { this.parameters = params; }
    @Override public Map<String, Object> getContextData() { return contextData; }
    @Override public Set<Annotation> getInterceptorBindings() { return Set.of(); }

    @Override
    public Object proceed() throws Exception {
        try {
            return method.invoke(target, parameters);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new RuntimeException(cause);
        }
    }
}

