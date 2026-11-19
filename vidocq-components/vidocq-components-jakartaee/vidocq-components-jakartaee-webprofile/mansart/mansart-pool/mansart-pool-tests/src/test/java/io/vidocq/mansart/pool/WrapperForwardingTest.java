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
package io.vidocq.mansart.pool;

import io.vidocq.mansart.pool.core.MansartDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every JDBC method of a borrowed connection and of its statements reaches the driver unchanged, and only the
 * calls that may leave work pending, or hand out a way to run SQL the pool cannot see, make the release roll
 * the connection back (Vidocq/noether#45). The driver behind the pool records every call and answers with a
 * sample value, so the test also checks that a statement hands the driver's answer back.
 */
class WrapperForwardingTest {

    /** Methods the parameterized tests leave to {@link ReleaseRollbackTest} and the dedicated tests below. */
    private static final Set<String> NOT_FORWARDED = Set.of("close", "getConnection", "unwrap", "isWrapperFor");

    private RecordingDriver driver;
    private MansartDataSource ds;

    @BeforeEach
    void setUp() throws SQLException {
        driver = new RecordingDriver();
        DriverManager.registerDriver(driver);
        ds = MansartDataSource.of(PoolConfig.builder()
                .jdbcUrl(RecordingDriver.PREFIX + UUID.randomUUID())
                .maxSize(1)
                .build());
    }

    @AfterEach
    void tearDown() throws SQLException {
        ds.close();
        DriverManager.deregisterDriver(driver);
    }

    static Stream<Method> statementMethods() {
        return methods(Statement.class);
    }

    static Stream<Method> preparedStatementMethods() {
        return methods(PreparedStatement.class);
    }

    static Stream<Method> connectionMethods() {
        return methods(Connection.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("statementMethods")
    void aStatementForwardsEveryCall(Method method) throws Exception {
        Connection c = ds.getConnection();
        Statement s = c.createStatement();

        Object[] args = arguments(method);
        Object answer = invoke(method, s, args);

        assertThat(driver.statementCalls.getLast()).isEqualTo(new Call(method, args));
        assertThat(answer).isEqualTo(sample(method.getReturnType()));
        assertThat(rolledBackOnRelease(c)).as("rolled back on release").isEqualTo(isExecute(method));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("preparedStatementMethods")
    void aPreparedStatementForwardsEveryCall(Method method) throws Exception {
        Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement("SQL");

        Object[] args = arguments(method);
        Object answer = invoke(method, ps, args);

        assertThat(driver.statementCalls.getLast()).isEqualTo(new Call(method, args));
        assertThat(answer).isEqualTo(sample(method.getReturnType()));
        assertThat(rolledBackOnRelease(c)).as("rolled back on release").isEqualTo(isExecute(method));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("connectionMethods")
    void aConnectionForwardsEveryCall(Method method) throws Exception {
        Connection c = ds.getConnection();

        Object[] args = arguments(method);
        invoke(method, c, args);

        assertThat(driver.connectionCalls.getLast()).isEqualTo(new Call(method, args));
        assertThat(rolledBackOnRelease(c)).as("rolled back on release").isEqualTo(forcesRollback(method));
    }

    @Test
    void aStatementUnwrapsToItselfWithoutLosingTrack() throws SQLException {
        Connection c = ds.getConnection();
        Statement s = c.createStatement();
        PreparedStatement ps = c.prepareStatement("SQL");

        assertThat(s.isWrapperFor(Statement.class)).isTrue();
        assertThat(s.unwrap(Statement.class)).isSameAs(s);
        assertThat(s.isWrapperFor(CallableStatement.class)).isTrue();   // the driver's statement is one
        assertThat(ps.isWrapperFor(PreparedStatement.class)).isTrue();
        assertThat(ps.unwrap(PreparedStatement.class)).isSameAs(ps);
        assertThat(ps.isWrapperFor(RecordingDriver.class)).isTrue();   // the driver's own isWrapperFor answers

        assertThat(rolledBackOnRelease(c)).isFalse();
    }

    @Test
    void aStatementUnwrappedThroughTheDriverIsUntracked() throws SQLException {
        Connection c = ds.getConnection();
        Statement s = c.createStatement();

        assertThat(s.unwrap(RecordingDriver.class)).isNull();   // the driver's own unwrap answers

        assertThat(rolledBackOnRelease(c)).isTrue();
    }

    /* ---- expectations ---- */

    /** A statement marks the connection on its {@code execute*} calls, and on nothing else. */
    private static boolean isExecute(Method method) {
        return method.getName().startsWith("execute");
    }

    /**
     * The connection calls after which the release rolls back: creating a large object (work may be pending), and
     * every call handing out a driver object that can run SQL out of the wrapper's sight. The sample concurrency
     * {@code 0} is not {@code CONCUR_READ_ONLY}, so the statements created with one are untracked.
     */
    private static boolean forcesRollback(Method method) {
        Class<?>[] p = method.getParameterTypes();
        return switch (method.getName()) {
            case "createClob", "createBlob", "createNClob", "createSQLXML", "getMetaData", "prepareCall" -> true;
            case "createStatement" -> p.length >= 2;
            case "prepareStatement" -> p.length >= 3 && p[1] == int.class && p[2] == int.class;
            default -> false;
        };
    }

    /* ---- helpers ---- */

    private boolean rolledBackOnRelease(Connection c) throws SQLException {
        int before = driver.rollbacks();
        c.close();
        return driver.rollbacks() > before;
    }

    private static Stream<Method> methods(Class<?> type) {
        return Arrays.stream(type.getMethods())
                .filter(m -> !Modifier.isStatic(m.getModifiers()) && !NOT_FORWARDED.contains(m.getName()))
                .sorted(Comparator.comparing(Method::toGenericString));
    }

    private static Object[] arguments(Method method) {
        return Arrays.stream(method.getParameterTypes()).map(WrapperForwardingTest::zero).toArray();
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Exception {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    /** The argument passed for a parameter of {@code type}. */
    private static Object zero(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class)    return (byte) 0;
        if (type == short.class)   return (short) 0;
        if (type == int.class)     return 0;
        if (type == long.class)    return 0L;
        if (type == float.class)   return 0f;
        if (type == double.class)  return 0d;
        return null;
    }

    /** What the recording driver's statements answer for a method returning {@code type}. */
    private static Object sample(Class<?> type) {
        if (type == boolean.class) return true;
        if (type == int.class)     return 7;
        if (type == long.class)    return 7L;
        if (type == String.class)  return "answer";
        return null;
    }

    record Call(Method method, List<Object> args) {
        Call(Method method, Object[] args) {
            this(method, args == null ? List.of() : Arrays.asList(args));
        }
    }

    /**
     * A driver for {@code jdbc:recording:} URLs whose connections and statements are dynamic proxies (test code
     * only) that record every call. The connection stays out of auto-commit, so the pool's release decides
     * whether to roll back.
     */
    static final class RecordingDriver implements Driver {

        static final String PREFIX = "jdbc:recording:";

        final List<Call> connectionCalls = new CopyOnWriteArrayList<>();
        final List<Call> statementCalls  = new CopyOnWriteArrayList<>();

        int rollbacks() {
            return (int) connectionCalls.stream()
                    .filter(c -> c.method().getName().equals("rollback") && c.args().isEmpty())
                    .count();
        }

        @Override
        public Connection connect(String url, Properties info) {
            if (!acceptsURL(url)) {
                return null;
            }
            return (Connection) Proxy.newProxyInstance(WrapperForwardingTest.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) return identity(proxy, method, args);
                        connectionCalls.add(new Call(method, args));
                        return switch (method.getName()) {
                            case "createStatement", "prepareStatement", "prepareCall" -> statement();
                            case "getAutoCommit", "isClosed" -> false;
                            case "isValid" -> true;
                            default -> zero(method.getReturnType());
                        };
                    });
        }

        /** One proxy type for every statement kind: a callable statement is a prepared one, and a statement. */
        private CallableStatement statement() {
            return (CallableStatement) Proxy.newProxyInstance(WrapperForwardingTest.class.getClassLoader(),
                    new Class<?>[]{CallableStatement.class}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) return identity(proxy, method, args);
                        statementCalls.add(new Call(method, args));
                        return sample(method.getReturnType());
                    });
        }

        /** {@code equals}, {@code hashCode} and {@code toString} of a proxy: identity, never recorded. */
        private static Object identity(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "equals"   -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default         -> "recording " + proxy.getClass().getInterfaces()[0].getSimpleName();
            };
        }

        @Override public boolean acceptsURL(String url)                                 { return url.startsWith(PREFIX); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion()                                          { return 1; }
        @Override public int getMinorVersion()                                          { return 0; }
        @Override public boolean jdbcCompliant()                                        { return false; }
        @Override public Logger getParentLogger()                                       { return Logger.getGlobal(); }
    }
}
