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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.telemetry;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Writes the {@code Delegating*} classes of the main sources from the {@code java.sql} interfaces of the running JDK:
 * one method per interface method, delegating to the wrapped object, so that no one types the ~400 of them by hand
 * and no dynamic proxy is needed. {@code execute*} calls go through {@link JdbcExecution}, and the statements a
 * connection creates come back wrapped. {@code JdbcDelegatesTest} checks the committed classes against this output.
 */
final class JdbcDelegatesGenerator {

    static final String PACKAGE = JdbcDelegatesGenerator.class.getPackageName();

    private static final Set<String> EXECUTES = Set.of("execute", "executeQuery", "executeUpdate",
            "executeLargeUpdate", "executeBatch", "executeLargeBatch");

    private final String licence;

    JdbcDelegatesGenerator(String licence) {
        this.licence = licence;
    }

    /** Simple class name to source, for the four generated classes. */
    Map<String, String> generate() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("DelegatingConnection", connection());
        out.put("DelegatingStatement", statement("DelegatingStatement", Statement.class));
        out.put("DelegatingPreparedStatement", statement("DelegatingPreparedStatement", PreparedStatement.class));
        out.put("DelegatingCallableStatement", statement("DelegatingCallableStatement", CallableStatement.class));
        return out;
    }

    private String header(String className, Class<?> iface, String role) {
        return licence + "package " + PACKAGE + ";\n\n"
                + "/**\n"
                + " * " + role + "\n"
                + " *\n"
                + " * <p>Generated from {@link " + iface.getName() + "} by {@code JdbcDelegatesGenerator} (test sources): do\n"
                + " * not edit. Regenerate with {@code ./mvnw -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/\n"
                + " * vidocq-runtime-mansart-pool-telemetry-extension test -Dtest=JdbcDelegatesTest -Dvidocq.jdbc.delegates.write=true}.</p>\n"
                + " */\n"
                + "@SuppressWarnings({\"deprecation\", \"removal\"})\n"
                + "final class " + className + " implements " + iface.getName() + " {\n\n";
    }

    private String connection() {
        StringBuilder src = new StringBuilder(header("DelegatingConnection", Connection.class,
                "A {@link java.sql.Connection} that hands out traced statements and delegates everything else."));
        src.append("""
                    private final java.sql.Connection delegate;
                    private final JdbcExecution execution;

                    DelegatingConnection(java.sql.Connection delegate, JdbcExecution execution) {
                        this.delegate = delegate;
                        this.execution = execution;
                    }
                """);
        for (Method m : methods(Connection.class)) {
            String call = "delegate." + m.getName() + "(" + args(m) + ")";
            String body = switch (m.getName()) {
                case "createStatement" -> "return new DelegatingStatement(" + call + ", execution, this, null);";
                case "prepareStatement" -> "return new DelegatingPreparedStatement(" + call + ", execution, this, p0);";
                case "prepareCall" -> "return new DelegatingCallableStatement(" + call + ", execution, this, p0);";
                default -> wrapperOr(m, call);
            };
            src.append(method(m, body));
        }
        return src.append("}\n").toString();
    }

    private String statement(String className, Class<? extends Statement> iface) {
        StringBuilder src = new StringBuilder(header(className, iface,
                "A {@link " + iface.getName() + "} whose {@code execute*} calls go through {@link JdbcExecution}."));
        src.append("""
                    private final %1$s delegate;
                    private final JdbcExecution execution;
                    private final java.sql.Connection connection;
                    private final String sql;
                    private final java.util.List<String> batch = new java.util.ArrayList<>();

                    %2$s(%1$s delegate, JdbcExecution execution, java.sql.Connection connection, String sql) {
                        this.delegate = delegate;
                        this.execution = execution;
                        this.connection = connection;
                        this.sql = sql;
                    }

                    /** The SQL a batch runs: the statements added to a plain statement's batch, else the prepared SQL. */
                    private String batchSql() {
                        return batch.isEmpty() ? sql : String.join("; ", batch);
                    }
                """.formatted(iface.getName(), className));
        for (Method m : methods(iface)) {
            String call = "delegate." + m.getName() + "(" + args(m) + ")";
            Class<?>[] params = m.getParameterTypes();
            boolean sqlFirst = params.length > 0 && params[0] == String.class;
            String body;
            if (EXECUTES.contains(m.getName())) {
                boolean batchCall = m.getName().endsWith("Batch");
                String sqlExpr = batchCall ? "batchSql()" : sqlFirst ? "p0" : "sql";
                String prepared = batchCall ? "batch.isEmpty()" : String.valueOf(!sqlFirst);
                String run = "return execution.execute(" + sqlExpr + ", " + prepared + ", () -> " + call + ");";
                body = batchCall ? "try {\n            " + run + "\n        } finally {\n            batch.clear();\n        }"
                        : run;
            } else if (m.getName().equals("addBatch") && sqlFirst) {
                body = "batch.add(p0);\n        " + call + ";";
            } else if (m.getName().equals("clearBatch")) {
                body = "batch.clear();\n        " + call + ";";
            } else if (m.getName().equals("getConnection") && params.length == 0) {
                body = "return connection;";
            } else {
                body = wrapperOr(m, call);
            }
            src.append(method(m, body));
        }
        return src.append("}\n").toString();
    }

    /** {@code unwrap}/{@code isWrapperFor} see the wrapped object too; any other method delegates. */
    private static String wrapperOr(Method m, String call) {
        if (m.getName().equals("unwrap")) {
            return "return p0.isInstance(delegate) ? p0.cast(delegate) : " + call + ";";
        }
        if (m.getName().equals("isWrapperFor")) {
            return "return p0.isInstance(delegate) || " + call + ";";
        }
        return m.getReturnType() == void.class ? call + ";" : "return " + call + ";";
    }

    private static String method(Method m, String body) {
        StringBuilder s = new StringBuilder("\n    @Override\n");
        if (m.isAnnotationPresent(Deprecated.class)) {
            s.append("    @Deprecated\n");
        }
        s.append("    public ");
        TypeVariable<Method>[] tvs = m.getTypeParameters();
        if (tvs.length > 0) {
            s.append('<').append(Arrays.stream(tvs).map(TypeVariable::getName).collect(Collectors.joining(", ")))
                    .append("> ");
        }
        s.append(type(m.getGenericReturnType())).append(' ').append(m.getName()).append('(');
        Type[] params = m.getGenericParameterTypes();
        List<String> decl = new ArrayList<>();
        for (int i = 0; i < params.length; i++) {
            decl.add(type(params[i]) + " p" + i);
        }
        s.append(String.join(", ", decl)).append(')');
        Class<?>[] thrown = m.getExceptionTypes();
        if (thrown.length > 0) {
            s.append(" throws ").append(Arrays.stream(thrown).map(Class::getName).collect(Collectors.joining(", ")));
        }
        return s.append(" {\n        ").append(body).append("\n    }\n").toString();
    }

    private static String args(Method m) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < m.getParameterCount(); i++) {
            names.add("p" + i);
        }
        return String.join(", ", names);
    }

    static String type(Type t) {
        return switch (t) {
            case Class<?> c when c.isArray() -> type(c.getComponentType()) + "[]";
            case Class<?> c -> c.getName().replace('$', '.');
            case ParameterizedType p -> type(p.getRawType()) + Arrays.stream(p.getActualTypeArguments())
                    .map(JdbcDelegatesGenerator::type).collect(Collectors.joining(", ", "<", ">"));
            case TypeVariable<?> v -> v.getName();
            case WildcardType w when w.getLowerBounds().length > 0 -> "? super " + type(w.getLowerBounds()[0]);
            case WildcardType w when w.getUpperBounds()[0] == Object.class -> "?";
            case WildcardType w -> "? extends " + type(w.getUpperBounds()[0]);
            case GenericArrayType g -> type(g.getGenericComponentType()) + "[]";
            default -> throw new IllegalArgumentException("unexpected type " + t);
        };
    }

    /**
     * Every instance method of {@code iface} and its super-interfaces, once per signature (the most specific
     * declaration), sorted by name and parameters so the output is stable.
     */
    static List<Method> methods(Class<?> iface) {
        Map<String, Method> bySignature = new LinkedHashMap<>();
        for (Method m : iface.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            String key = m.getName() + Arrays.toString(m.getParameterTypes());
            bySignature.merge(key, m, (a, b) -> a.getDeclaringClass().isAssignableFrom(b.getDeclaringClass()) ? b : a);
        }
        return bySignature.values().stream()
                .sorted(Comparator.comparing(Method::getName)
                        .thenComparing(m -> Arrays.toString(m.getParameterTypes())))
                .toList();
    }
}
