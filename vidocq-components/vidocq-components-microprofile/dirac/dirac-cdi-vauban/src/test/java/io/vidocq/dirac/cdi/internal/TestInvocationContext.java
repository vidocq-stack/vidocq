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
package io.vidocq.dirac.cdi.internal;

import jakarta.interceptor.InvocationContext;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Hand-written {@link InvocationContext} for interceptor unit tests (no mocking library):
 * either a method invocation or a constructor interception.
 */
final class TestInvocationContext implements InvocationContext {
    private final Object target;
    private final Method method;
    private final Constructor<?> constructor;
    private final Callable<Object> action;
    private final Map<String, Object> contextData = new HashMap<>();
    private Object[] parameters = new Object[0];
    private int proceedCount;

    private TestInvocationContext(Object target, Method method, Constructor<?> constructor, Callable<Object> action) {
        this.target = target;
        this.method = method;
        this.constructor = constructor;
        this.action = action;
    }

    static TestInvocationContext invocation(Object target, Method method, Callable<Object> action) {
        return new TestInvocationContext(target, method, null, action);
    }

    static TestInvocationContext construction(Object target, Constructor<?> constructor) {
        return new TestInvocationContext(target, null, constructor, () -> null);
    }

    int proceedCount() {
        return proceedCount;
    }

    @Override
    public Object getTarget() {
        return target;
    }

    @Override
    public Object getTimer() {
        return null;
    }

    @Override
    public Method getMethod() {
        return method;
    }

    @Override
    public Constructor<?> getConstructor() {
        return constructor;
    }

    @Override
    public Object[] getParameters() {
        return parameters.clone();
    }

    @Override
    public void setParameters(Object[] params) {
        this.parameters = params.clone();
    }

    @Override
    public Map<String, Object> getContextData() {
        return contextData;
    }

    @Override
    public Object proceed() throws Exception {
        proceedCount++;
        return action.call();
    }
}
