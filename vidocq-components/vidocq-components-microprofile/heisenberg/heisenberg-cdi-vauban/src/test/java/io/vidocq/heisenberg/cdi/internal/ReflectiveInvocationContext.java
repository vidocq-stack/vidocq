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
 * Shared test utility: implements {@link InvocationContext} via reflection on a real
 * method of a target bean. Allows invoking {@link FaultToleranceInterceptor#around(InvocationContext)}
 * without depending on a full CDI container.
 *
 * <p>Used by all {@code *IntegrationTest} classes in {@code heisenberg-cdi-vauban}.
 * Conforms to AGENTS.md: no {@code setAccessible(true)} — intercepted methods must remain
 * accessible from the test package (package-private or public).</p>
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

