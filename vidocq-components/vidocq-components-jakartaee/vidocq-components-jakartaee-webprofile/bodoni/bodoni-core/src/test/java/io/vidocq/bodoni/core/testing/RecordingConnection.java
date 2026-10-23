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

package io.vidocq.bodoni.core.testing;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A recording {@link Connection} double: {@link #proxy()} delegates every call to a real
 * connection and logs the transaction-related ones ({@code getTransactionIsolation()},
 * {@code getAutoCommit()}, {@code setAutoCommit(b)}, {@code setTransactionIsolation(n)},
 * {@code commit()}, {@code rollback()}, {@code close()}) in call order, a call being logged
 * before it runs. The statements it creates, and the result sets those return, are proxies
 * too, so that {@link #failOn(String)} reaches them and {@link #unclosed()} can count them.
 */
public final class RecordingConnection implements InvocationHandler {

    private static final Set<String> LOGGED = Set.of("getTransactionIsolation", "getAutoCommit",
            "setAutoCommit", "setTransactionIsolation", "commit", "rollback", "close");

    private final Connection real;
    private final Connection proxy;
    private final List<String> log = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> failing = ConcurrentHashMap.newKeySet();
    private final List<Tracked> tracked = Collections.synchronizedList(new ArrayList<>());
    private volatile Integer isolation;

    RecordingConnection(Connection real) {
        this.real = real;
        this.proxy = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, this);
    }

    /** The connection handed to the code under test. */
    public Connection proxy() {
        return proxy;
    }

    /** The logged calls, in order, such as {@code setAutoCommit(false)}. */
    public List<String> log() {
        return List.copyOf(log);
    }

    /** Makes {@code getTransactionIsolation()} answer {@code level}; {@code setTransactionIsolation} updates it. */
    public RecordingConnection isolation(int level) {
        this.isolation = level;
        return this;
    }

    /**
     * Makes every method named {@code method}, on the connection, its statements and their
     * result sets, throw {@code SQLException("injected <method>")} from now on.
     */
    public RecordingConnection failOn(String method) {
        failing.add(method);
        return this;
    }

    /** The number of statements and result sets created through this connection and not closed. */
    public int unclosed() {
        synchronized (tracked) {
            return (int) tracked.stream().filter(t -> !t.closed).count();
        }
    }

    @Override
    public Object invoke(Object self, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (LOGGED.contains(name)) {
            log.add(name + "(" + (args == null ? "" : args[0]) + ")");
        }
        failIfAsked(name);
        if (isolation != null && name.equals("getTransactionIsolation")) {
            return isolation;
        }
        if (isolation != null && name.equals("setTransactionIsolation")) {
            isolation = (Integer) args[0];
        }
        Object result = call(real, method, args);
        if (result instanceof Statement statement) {
            return track(statement, method.getReturnType());
        }
        return result;
    }

    private void failIfAsked(String name) throws SQLException {
        if (failing.contains(name)) {
            throw new SQLException("injected " + name);
        }
    }

    private Object track(Object target, Class<?> type) {
        Tracked t = new Tracked(target);
        tracked.add(t);
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, t);
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** A statement or a result set: delegates, fails on demand, records its closing. */
    private final class Tracked implements InvocationHandler {

        private final Object target;
        private volatile boolean closed;

        Tracked(Object target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object self, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            failIfAsked(name);
            Object result = call(target, method, args);
            if (name.equals("close")) {
                closed = true;
            }
            if (target instanceof Statement && result instanceof ResultSet && method.getReturnType() == ResultSet.class) {
                return track(result, ResultSet.class);
            }
            return result;
        }
    }
}
