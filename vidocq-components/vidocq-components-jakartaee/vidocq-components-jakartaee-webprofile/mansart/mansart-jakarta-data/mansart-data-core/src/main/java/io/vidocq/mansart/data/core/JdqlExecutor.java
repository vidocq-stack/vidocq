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
package io.vidocq.mansart.data.core;

import io.vidocq.mansart.data.dialect.Attribute;
import io.vidocq.mansart.data.dialect.Pagination;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.OrderBy;
import io.vidocq.mansart.data.dialect.Where;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks a parsed {@link JdqlAst.Stmt} and executes it against a {@link RepositoryRuntime}, used
 * by {@link RuntimeRepositoryProxy} when a {@code @Query}-annotated method is invoked.
 *
 * <p>Mirror of the compile-time JdqlParser code emitter: same lowering of predicates to
 * {@link Where}, same Order resolution, same dispatch by return type, same dynamic In(Collection)
 * with empty short-circuit. Aggregate / projection / UPDATE / DELETE statements are honoured.
 *
 * <p>What an execution takes from its call — the return type the result is dispatched to, the element type of a
 * projection, the index of each named parameter — is a {@link CallShape}: a {@code @Query} method's, or one built
 * from the statement itself when a tool runs a statement given as text.
 */
// M8-2 — promoted from package-private to public so generated repository impls in user packages
// can call the shared dispatchMultiProjection helper. The other static methods remain
// package-private (visibility narrowed where possible).
public final class JdqlExecutor {

    private static final String LENGTH = "LENGTH";

    private JdqlExecutor() {}

    /**
     * What executing a statement takes from its call.
     *
     * @param returnType the type the result is dispatched to: a {@code @Query} method's return type
     * @param element    the element type of a projection: the return type's type argument or array component
     * @param names      the index, in the call's arguments, of each named parameter
     * @param convert    whether a value compared to an attribute, or assigned to one, is converted to the attribute's
     *                   type ({@link JdqlValues}); never for a {@code @Query} method, whose parameters type its values
     */
    record CallShape(Class<?> returnType, Class<?> element, Map<String, Integer> names, boolean convert) {

        /** The shape of a {@code @Query} method: its return type, its projection element, its parameter names. */
        static CallShape of(Method method) {
            return new CallShape(method.getReturnType(), projectionElement(method), nameToIndexFor(method), false);
        }
    }

    /**
     * M8-1f — public entry point used by compile-time generated repository impls when the JDQL
     * grammar requires features not yet emitted as static Java by {@link io.vidocq.mansart.data.processor.JdqlParser}
     * (currently: UPDATE with arithmetic/scalar-function SET RHS). Re-parses the JDQL string at
     * each call — acceptable cost since the alternative is a few hundred LOC of static emitter
     * code duplicating the runtime AST walker. Caching of the parsed {@link JdqlAst.Stmt} per
     * (jdql, entity) pair is a future optimisation.
     *
     * <p>The compile-time emitter passes everything the executor needs to wire up: the raw JDQL
     * source, the call-site {@code Method} (for return-type dispatch and {@code @Param}/parameter-name
     * resolution), and the entity model.
     */
    public static Object executeJdql(String jdql, Method method, EntityModel<?> model,
                                     RepositoryRuntime runtime, Object[] args) {
        Map<String, Attribute<?, ?>> attrIndex = new HashMap<>();
        for (Attribute<?, ?> a : model.attributes()) attrIndex.put(a.name(), a);
        java.util.Set<String> attrNames = attrIndex.keySet();
        JdqlAst.Stmt stmt = JdqlAst.parse(jdql, attrNames, model.entityClass().getSimpleName());
        return execute(stmt, CallShape.of(method), model, attrIndex, runtime, args);
    }

    /**
     * Runs one JDQL statement given as text, as a tool does — the Vidocq dev console, for one — rather than a
     * {@code @Query} method: the statement is parsed against {@code model}, its named parameters take their values
     * from {@code parameters}, and it runs on {@code runtime} as a {@code @Query} method's statement would, in
     * whatever transaction the caller has begun.
     *
     * <p>A named parameter {@code :name} takes {@code parameters.get("name")}. A value compared to an attribute — a
     * parameter, a literal, or a value of an {@code UPDATE … SET} — is converted to the attribute's Java type when it
     * is a {@code String} and the attribute is not text: an enum by constant name, the {@code java.time} types by ISO
     * parsing, the numeric types exactly ({@code BigDecimal} and {@code BigInteger} included), a {@code Boolean} from
     * {@code true} or {@code false}, a {@code UUID} by {@link java.util.UUID#fromString}. A number is converted
     * exactly to a numeric attribute's type, a collection (for {@code IN :names}) element by element; a value of the
     * right type is kept. Every value is checked before anything runs.
     *
     * <p>The result is read whole: {@code run} returns all that the statement selects, and the caller cuts;
     * {@link #run(String, Map, EntityModel, RepositoryRuntime, int)} reads at most a number of rows instead.
     *
     * @param jdql       one statement: {@code FROM …}, {@code SELECT … FROM …}, {@code UPDATE … SET …} or
     *                   {@code DELETE FROM …}, naming {@code model}'s entity by its simple or its full class name
     * @param parameters the value of each named parameter, by its name without the colon; {@code null} for none
     * @param model      the model of the entity the statement names, such as {@link EntityModels#of} gives
     * @param runtime    the runtime of the data store to run it on
     * @return the entities, the rows of a projection, the rows counted or changed, or an aggregate's value
     * @throws MansartDataException when the statement does not parse; when it uses a positional parameter
     *                              ({@code ?1}), a named parameter that has no value, or when a value is given that
     *                              no parameter uses; when a value does not convert ({@code :status: not a Status});
     *                              and as a {@code @Query} method's statement throws when it runs
     */
    public static JdqlResult run(String jdql, Map<String, ?> parameters, EntityModel<?> model,
                                 RepositoryRuntime runtime) {
        return run(jdql, parameters, model, runtime, Pagination.NONE);
    }

    /**
     * {@link #run(String, Map, EntityModel, RepositoryRuntime)}, reading at most {@code maxRows} entities or rows of a
     * projection, in the statement's order: a SQL {@code LIMIT}, so that no more is ever read from the database. A
     * count, an aggregate, an {@code UPDATE} and a {@code DELETE} are not affected. For a caller that shows or writes a
     * bounded result, such as a dev console.
     *
     * @param maxRows the most entities or rows read, at least 1
     * @throws IllegalArgumentException when {@code maxRows} is less than 1
     */
    public static JdqlResult run(String jdql, Map<String, ?> parameters, EntityModel<?> model,
                                 RepositoryRuntime runtime, int maxRows) {
        if (maxRows < 1) {
            throw new IllegalArgumentException("maxRows: at least 1, not " + maxRows);
        }
        return run(jdql, parameters, model, runtime, new Pagination.Offset(0, maxRows));
    }

    private static JdqlResult run(String jdql, Map<String, ?> parameters, EntityModel<?> model,
                                  RepositoryRuntime runtime, Pagination pagination) {
        java.util.Objects.requireNonNull(jdql, "jdql");
        java.util.Objects.requireNonNull(model, "model");
        java.util.Objects.requireNonNull(runtime, "runtime");
        Map<String, ?> given = parameters == null ? Map.of() : parameters;
        Map<String, Attribute<?, ?>> attrIndex = new HashMap<>();
        for (Attribute<?, ?> a : model.attributes()) attrIndex.put(a.name(), a);
        // the parser compares the entity it reads with this name: the full class name when the statement uses it
        String entityName = target(jdql).filter(model.entityClass().getName()::equals)
                .orElse(model.entityClass().getSimpleName());
        JdqlAst.Stmt stmt;
        try {
            stmt = JdqlAst.parse(jdql, attrIndex.keySet(), entityName);
        } catch (JdqlAst.ParseException e) {
            throw new MansartDataException(e.getMessage(), e);
        }
        List<String> used = parameterNames(stmt);
        for (String name : used) {
            if (!given.containsKey(name)) throw new MansartDataException(":" + name + ": no value given");
        }
        for (String name : new java.util.TreeSet<>(given.keySet())) {
            if (!used.contains(name)) throw new MansartDataException(":" + name + ": not used by the statement");
        }
        Map<String, Integer> names = new HashMap<>();
        Object[] args = new Object[used.size()];
        for (int i = 0; i < used.size(); i++) {
            names.put(used.get(i), i);
            args[i] = given.get(used.get(i));
        }
        Object result = execute(stmt, shapeOf(stmt, model, attrIndex, names), model, attrIndex, runtime, args,
                pagination);
        return switch (stmt.kind()) {
            case SELECT -> new JdqlResult.Entities((List<?>) result);
            case COUNT, UPDATE, DELETE -> new JdqlResult.Count(
                    ((Number) java.util.Objects.requireNonNull(result)).longValue());
            case AGGREGATE -> new JdqlResult.Value(result);
            case PROJECT -> new JdqlResult.Rows(List.of(stmt.scalarAttr()),
                    columnRows((List<?>) java.util.Objects.requireNonNull(result)));
            case PROJECT_MULTI -> new JdqlResult.Rows(stmt.projectAttrs(), projectedRows(result));
        };
    }

    /**
     * Whether {@code jdql} is an {@code UPDATE} or a {@code DELETE}, read from its first keyword, without parsing it.
     *
     * @param jdql a statement, or {@code null}
     */
    public static boolean isWrite(String jdql) {
        List<String> words = words(jdql);
        return !words.isEmpty()
                && (words.getFirst().equalsIgnoreCase("UPDATE") || words.getFirst().equalsIgnoreCase("DELETE"));
    }

    /**
     * The entity {@code jdql} names: the identifier after {@code UPDATE}, or after its first {@code FROM}
     * ({@code SELECT … FROM}, {@code DELETE FROM}), a dotted full class name kept whole; read without parsing, string
     * literals and parameters skipped.
     *
     * @param jdql a statement, or {@code null}
     * @return the name as written; empty when the statement names none, such as {@code WHERE price > :min}
     */
    public static java.util.Optional<String> target(String jdql) {
        List<String> words = words(jdql);
        if (words.isEmpty()) return java.util.Optional.empty();
        if (words.getFirst().equalsIgnoreCase("UPDATE")) {
            return words.size() > 1 && !isClauseKeyword(words.get(1))
                    ? java.util.Optional.of(words.get(1)) : java.util.Optional.empty();
        }
        for (int i = 0; i + 1 < words.size(); i++) {
            if (words.get(i).equalsIgnoreCase("FROM")) {
                String next = words.get(i + 1);
                return isClauseKeyword(next) ? java.util.Optional.empty() : java.util.Optional.of(next);
            }
        }
        return java.util.Optional.empty();
    }

    /** The keywords that may follow {@code UPDATE} or {@code FROM} where an entity name is left out. */
    private static boolean isClauseKeyword(String word) {
        return switch (word.toUpperCase(java.util.Locale.ROOT)) {
            case "SET", "WHERE", "ORDER", "FROM" -> true;
            default -> false;
        };
    }

    /**
     * The words of {@code jdql} — keywords and identifiers, a dotted name kept whole — in order, without its string
     * literals, its parameters ({@code :name}, {@code ?1}) and its numbers.
     */
    private static List<String> words(String jdql) {
        List<String> out = new ArrayList<>();
        if (jdql == null) return out;
        int i = 0;
        int n = jdql.length();
        while (i < n) {
            char c = jdql.charAt(i);
            if (c == '\'') {
                i = endOfLiteral(jdql, i + 1);
            } else if (c == ':' || c == '?' || Character.isDigit(c)) {
                i = endOfWord(jdql, i + 1);
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                i = endOfWord(jdql, i);
                out.add(jdql.substring(start, i));
            } else {
                i++;
            }
        }
        return out;
    }

    /** The index past the word part starting at {@code from}. */
    private static int endOfWord(String jdql, int from) {
        int i = from;
        while (i < jdql.length() && isWordPart(jdql.charAt(i))) i++;
        return i;
    }

    /** The index past the quote closing a string literal whose text starts at {@code from}; {@code ''} is one quote. */
    private static int endOfLiteral(String jdql, int from) {
        int n = jdql.length();
        int i = from;
        while (i < n) {
            if (jdql.charAt(i) != '\'') {
                i++;
            } else if (i + 1 < n && jdql.charAt(i + 1) == '\'') {
                i += 2;
            } else {
                return i + 1;
            }
        }
        return i;
    }

    private static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }

    /** The named parameters {@code stmt} uses, each once, in the order they appear; a positional one is refused. */
    private static List<String> parameterNames(JdqlAst.Stmt stmt) {
        List<JdqlAst.ArgRef> refs = new ArrayList<>();
        for (JdqlAst.SetAssign sa : stmt.setAssignments()) collectArgs(sa.value(), refs);
        if (stmt.where() != null) collectArgs(stmt.where(), refs);
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (JdqlAst.ArgRef ref : refs) {
            if (ref.isLiteral()) continue;
            if (!ref.isNamed()) {
                throw new MansartDataException("positional parameters are not supported here; use :name");
            }
            names.add(ref.named());
        }
        return List.copyOf(names);
    }

    private static void collectArgs(JdqlAst.Pred p, List<JdqlAst.ArgRef> out) {
        switch (p) {
            case JdqlAst.Cmp c -> out.add(c.arg());
            case JdqlAst.FnCmp c -> out.add(c.arg());
            case JdqlAst.IsNull _ -> { /* no value */ }
            case JdqlAst.FnIsNull _ -> { /* no value */ }
            case JdqlAst.Between b -> {
                out.add(b.lo());
                out.add(b.hi());
            }
            case JdqlAst.FnBetween b -> {
                out.add(b.lo());
                out.add(b.hi());
            }
            case JdqlAst.In in -> out.addAll(in.args());
            case JdqlAst.FnIn in -> out.addAll(in.args());
            case JdqlAst.And(var children) -> children.forEach(c -> collectArgs(c, out));
            case JdqlAst.Or(var children) -> children.forEach(c -> collectArgs(c, out));
            case JdqlAst.Not(var child) -> collectArgs(child, out);
        }
    }

    private static void collectArgs(JdqlAst.Expr e, List<JdqlAst.ArgRef> out) {
        switch (e) {
            case JdqlAst.ExprArg(var arg) -> out.add(arg);
            case JdqlAst.ExprAttr _ -> { /* an attribute, no value */ }
            case JdqlAst.ExprBin bin -> {
                collectArgs(bin.left(), out);
                collectArgs(bin.right(), out);
            }
            case JdqlAst.ExprFunc fn -> fn.args().forEach(arg -> collectArgs(arg, out));
        }
    }

    /**
     * The shape of a statement run as text: its result is a list (entities, a column, rows), a {@code long} (a count,
     * the rows changed) or an aggregate's value, and its values are converted.
     */
    private static CallShape shapeOf(JdqlAst.Stmt stmt, EntityModel<?> model,
                                     Map<String, Attribute<?, ?>> attrIndex, Map<String, Integer> names) {
        return switch (stmt.kind()) {
            case SELECT -> new CallShape(List.class, model.entityClass(), names, true);
            case PROJECT -> new CallShape(List.class,
                    boxed(lookup(model, attrIndex, stmt.scalarAttr()).javaType()), names, true);
            case PROJECT_MULTI -> new CallShape(List.class, Object[].class, names, true);
            case AGGREGATE -> new CallShape(
                    aggregateType(stmt.aggregateOp(), lookup(model, attrIndex, stmt.scalarAttr()).javaType()),
                    null, names, true);
            case COUNT, UPDATE, DELETE -> new CallShape(long.class, null, names, true);
        };
    }

    /**
     * The type an aggregate is read as: {@code MIN} and {@code MAX} the attribute's; {@code SUM} a {@code Long} over
     * an integral attribute, a {@code Double} over a floating one, else the attribute's; {@code AVG} a
     * {@code Double}, a {@code BigDecimal} over a {@code BigDecimal}.
     */
    private static Class<?> aggregateType(String op, Class<?> attribute) {
        Class<?> type = boxed(attribute);
        boolean integral = type == Long.class || type == Integer.class || type == Short.class || type == Byte.class;
        boolean floating = type == Double.class || type == Float.class;
        return switch (op) {
            case "AVG" -> type == java.math.BigDecimal.class ? java.math.BigDecimal.class : Double.class;
            case "SUM" -> sumType(type, integral, floating);
            default -> type;
        };
    }

    private static Class<?> sumType(Class<?> type, boolean integral, boolean floating) {
        if (integral) return Long.class;
        return floating ? Double.class : type;
    }

    /** A projected column as rows of one value. */
    private static List<Object[]> columnRows(List<?> column) {
        List<Object[]> rows = new ArrayList<>(column.size());
        for (Object value : column) rows.add(new Object[] {value});
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> projectedRows(Object rows) {
        return (List<Object[]>) rows;
    }

    /** Executes {@code stmt} as a call of {@code shape} makes it, with the call's {@code args}. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static Object execute(JdqlAst.Stmt stmt, CallShape shape, EntityModel<?> model,
                          Map<String, Attribute<?, ?>> attrIndex,
                          RepositoryRuntime runtime, Object[] args) {
        return execute(stmt, shape, model, attrIndex, runtime, args, Pagination.NONE);
    }

    /** {@link #execute(JdqlAst.Stmt, CallShape, EntityModel, Map, RepositoryRuntime, Object[])}, entities and rows read
     *  in {@code pagination}'s page (a SQL LIMIT for {@link #run(String, Map, EntityModel, RepositoryRuntime, int)}). */
    static Object execute(JdqlAst.Stmt stmt, CallShape shape, EntityModel<?> model,
                          Map<String, Attribute<?, ?>> attrIndex,
                          RepositoryRuntime runtime, Object[] args, Pagination pagination) {
        OrderBy orderBy = buildOrderBy(stmt.orderBy(), model, attrIndex);
        // M7-21 — fold any Sort/Order control args into the OrderBy. The query's own ORDER BY
        // (from JDQL) takes precedence (declared first); runtime Sort args extend it.
        orderBy = appendRuntimeSorts(orderBy, args, attrIndex);
        Execution call = new Execution(shape, model, runtime, args, orderBy, pagination);

        return switch (stmt.kind()) {
            case COUNT -> call.count(buildWhere(stmt.where(), model, attrIndex, args, shape));
            case SELECT -> call.select(buildWhere(stmt.where(), model, attrIndex, args, shape));
            case AGGREGATE -> call.aggregate(buildWhere(stmt.where(), model, attrIndex, args, shape),
                    stmt.aggregateOp(), lookup(model, attrIndex, stmt.scalarAttr()));
            case PROJECT_MULTI -> call.projectColumns(buildWhere(stmt.where(), model, attrIndex, args, shape),
                    lookupAll(model, attrIndex, stmt.projectAttrs()));
            case PROJECT -> call.projectColumn(buildWhere(stmt.where(), model, attrIndex, args, shape),
                    lookup(model, attrIndex, stmt.scalarAttr()), stmt.scalarAttr());
            case UPDATE -> call.update(renderSet(stmt.setAssignments(), attrIndex, args, shape),
                    buildWhere(stmt.where(), model, attrIndex, args, shape));
            case DELETE -> call.delete(buildWhere(stmt.where(), model, attrIndex, args, shape));
        };
    }

    private static List<Attribute<?, ?>> lookupAll(EntityModel<?> model, Map<String, Attribute<?, ?>> attrIndex,
                                                   List<String> names) {
        List<Attribute<?, ?>> attrs = new ArrayList<>(names.size());
        for (String name : names) attrs.add(lookup(model, attrIndex, name));
        return attrs;
    }

    /** The {@code SET} clause of an {@code UPDATE}: its SQL and the values it binds. */
    private record SetClause(String sql, SetValues bound) {}

    /** The values a {@code SET} clause binds, in placeholder order, with the type each is bound as. */
    private record SetValues(List<Object> values, List<Class<?>> types) {}

    private static SetClause renderSet(List<JdqlAst.SetAssign> assignments, Map<String, Attribute<?, ?>> attrIndex,
                                       Object[] args, CallShape shape) {
        StringBuilder setSql = new StringBuilder();
        SetValues bound = new SetValues(new ArrayList<>(), new ArrayList<>());
        for (JdqlAst.SetAssign sa : assignments) {
            // SET LHS doesn't allow path expressions (cannot UPDATE through a join). Stay
            // on the flat attrIndex and reject paths explicitly.
            if (sa.attr().indexOf('.') >= 0) {
                throw new MansartDataException("UPDATE SET LHS cannot be a path expression: " + sa.attr());
            }
            Attribute<?, ?> a = attrIndex.get(sa.attr());
            if (a == null) throw new MansartDataException("Unknown attribute: " + sa.attr());
            if (!setSql.isEmpty()) setSql.append(", ");
            setSql.append('"').append(a.columnName()).append("\" = ");
            renderExpr(setSql, sa.value(), attrIndex, args, shape, bound, a.javaType());
        }
        return new SetClause(setSql.toString(), bound);
    }

    /** One run of a statement: the call's shape, arguments, ordering and page, against one entity model. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class Execution {
        private final CallShape shape;
        private final EntityModel model;
        private final RepositoryRuntime runtime;
        private final Object[] args;
        private final OrderBy orderBy;
        private final Pagination pagination;

        /** The rows {@code limit} keeps of {@code rows} (all of them without a limit), in a new list. */
        private static <E> List<E> applyLimit(List<E> rows, jakarta.data.Limit limit) {
            if (limit == null) return rows;
            int from = Math.max(0, (int) (limit.startAt() - 1));
            int to = Math.min(rows.size(), from + limit.maxResults());
            return from >= rows.size() ? new ArrayList<>() : new ArrayList<>(rows.subList(from, to));
        }

        /** The value of an empty-IN query (no row can match), as the return type asks for it. */
        private static Object noMatch(Class<?> rt) {
            if (rt == java.util.Optional.class) return java.util.Optional.empty();
            if (rt == java.util.stream.Stream.class) return java.util.stream.Stream.empty();
            if (isListLike(rt)) return java.util.List.of();
            if (rt == long.class || rt == Long.class) return 0L;
            if (rt == boolean.class || rt == Boolean.class) return false;
            throw new MansartDataException("@Query empty IN with single-entity return");
        }

        private static Object wrapLongResult(Class<?> rt, long n) {
            if (rt == void.class) return null;
            if (rt == boolean.class || rt == Boolean.class) return n > 0;
            if (rt == int.class || rt == Integer.class) return (int) n;
            return n;
        }

        /** The {@code PageRequest} among a call's arguments {@code values} (empty for none), or {@code null}. */
        private static jakarta.data.page.PageRequest findPageRequest(Object[] values) {
            for (Object a : values) {
                if (a instanceof jakarta.data.page.PageRequest pr) return pr;
            }
            return null;
        }

        /** The {@code Limit} among a call's arguments {@code values} (empty for none), or {@code null}. */
        private static jakarta.data.Limit findLimit(Object[] values) {
            for (Object a : values) {
                if (a instanceof jakarta.data.Limit l) return l;
            }
            return null;
        }

        private static Object zeroFor(Class<?> c) {
            if (c == long.class)   return 0L;
            if (c == double.class) return 0.0;
            if (c == float.class)  return 0.0f;
            return 0;
        }

        Execution(CallShape shape, EntityModel<?> model, RepositoryRuntime runtime, Object[] args,
                  OrderBy orderBy, Pagination pagination) {
            this.shape = shape;
            this.model = model;
            this.runtime = runtime;
            this.args = args;
            this.orderBy = orderBy;
            this.pagination = pagination;
        }

        Object count(BuiltWhere bw) {
            return bw.where == Where.ALWAYS_FALSE ? 0L : runtime.countWhere(model, bw.where, bw.args);
        }

        Object select(BuiltWhere bw) {
            Class<?> rt = shape.returnType();
            if (bw.where == Where.ALWAYS_FALSE) return noMatch(rt);
            if (rt == long.class || rt == Long.class) return runtime.countWhere(model, bw.where, bw.args);
            if (rt == boolean.class || rt == Boolean.class) return runtime.existsWhere(model, bw.where, bw.args);
            boolean cursorRet = jakarta.data.page.CursoredPage.class.isAssignableFrom(rt);
            if (cursorRet || jakarta.data.page.Page.class.isAssignableFrom(rt)) {
                jakarta.data.page.PageRequest pr = findPageRequest(args);
                if (pr == null) {
                    throw new MansartDataException("@Query returning Page/CursoredPage requires a PageRequest argument");
                }
                if (cursorRet) return runtime.queryCursored(model, bw.where, orderBy, pr, bw.args);
                return runtime.queryPage(model, bw.where, orderBy, pr, bw.args);
            }
            if (rt == java.util.Optional.class) return runtime.queryOne(model, bw.where, bw.args);
            return entities(bw, rt);
        }

        private Object entities(BuiltWhere bw, Class<?> rt) {
            List<Object> data = applyLimit(runtime.queryList(model, bw.where, orderBy, pagination, bw.args),
                    findLimit(args));
            if (rt == java.util.stream.Stream.class) return data.stream();
            if (isListLike(rt)) return data;
            if (rt.isArray()) return toArray(rt.getComponentType(), data);
            java.util.Optional<?> one = runtime.queryOne(model, bw.where, bw.args);
            if (one.isEmpty()) {
                throw new jakarta.data.exceptions.EmptyResultException(
                        "@Query returned no result for " + model.entityClass().getSimpleName());
            }
            return one.get();
        }

        Object aggregate(BuiltWhere bw, String op, Attribute<?, ?> attr) {
            Class<?> rt = shape.returnType();
            Object v = runtime.aggregate(model, op, attr, boxed(rt), bw.where, bw.args);
            if (v == null && rt.isPrimitive()) return zeroFor(rt);
            return v;
        }

        Object projectColumns(BuiltWhere bw, List<Attribute<?, ?>> attrs) {
            List<Object[]> rows = applyLimit(
                    runtime.projectColumns(model, attrs, bw.where, orderBy, pagination, bw.args), findLimit(args));
            return dispatchMultiProjection(shape.returnType(), shape.element(), rows, attrs);
        }

        Object projectColumn(BuiltWhere bw, Attribute<?, ?> attr, String attrName) {
            Class<?> rt = shape.returnType();
            // Apply a Limit argument (Jakarta Data control parameter) by slicing.
            List<Object> col = applyLimit(
                    runtime.projectColumn(model, attr, (Class) shape.element(), bw.where, orderBy, pagination, bw.args),
                    findLimit(args));
            if (jakarta.data.page.Page.class.isAssignableFrom(rt)) return columnPage(col);
            if (isListLike(rt)) return col;
            if (rt == java.util.stream.Stream.class) return col.stream();
            if (rt == java.util.Optional.class) {
                return col.isEmpty() ? java.util.Optional.empty() : java.util.Optional.ofNullable(col.get(0));
            }
            if (rt.isArray()) return toArray(rt.getComponentType(), col);
            if (col.isEmpty()) {
                throw new jakarta.data.exceptions.EmptyResultException(
                        "Projection of " + attrName + " returned no result");
            }
            return col.get(0);
        }

        private Object columnPage(List<Object> col) {
            jakarta.data.page.PageRequest pr = findPageRequest(args);
            if (pr == null) {
                throw new MansartDataException("@Query projection returning Page requires a PageRequest argument");
            }
            int pageSize = pr.size();
            int offset = (int) ((pr.page() - 1) * pageSize);
            int from = Math.min(offset, col.size());
            int to   = Math.min(offset + pageSize, col.size());
            return new MansartPage<>(new ArrayList<>(col.subList(from, to)), pr, col.size());
        }

        Object update(SetClause set, BuiltWhere bw) {
            long n = runtime.executeUpdateRaw(model, set.sql(), set.bound().values(), set.bound().types(),
                    bw.where, bw.args);
            return wrapLongResult(shape.returnType(), n);
        }

        Object delete(BuiltWhere bw) {
            return wrapLongResult(shape.returnType(), runtime.deleteWhere(model, bw.where, bw.args));
        }
    }

    private static OrderBy buildOrderBy(List<JdqlAst.Order> orders, EntityModel<?> model,
                                        Map<String, Attribute<?, ?>> attrIndex) {
        if (orders == null || orders.isEmpty()) return OrderBy.NONE;
        List<OrderBy.Order> out = new ArrayList<>(orders.size());
        for (JdqlAst.Order o : orders) {
            Attribute<?, ?> a = lookup(model, attrIndex, o.attr());
            out.add(o.asc() ? OrderBy.Order.asc(a) : OrderBy.Order.desc(a));
        }
        return new OrderBy(out);
    }

    private record BuiltWhere(Where where, Object[] args) {}

    private static BuiltWhere buildWhere(JdqlAst.Pred p, EntityModel<?> model,
                                         Map<String, Attribute<?, ?>> attrIndex,
                                         Object[] callArgs, CallShape shape) {
        if (p == null) return new BuiltWhere(Where.ALWAYS_TRUE, new Object[0]);
        List<Object> argsOut = new ArrayList<>();
        boolean[] alwaysFalse = { false };
        Where w = build(p, model, attrIndex, callArgs, shape, argsOut, alwaysFalse);
        if (alwaysFalse[0]) return new BuiltWhere(Where.ALWAYS_FALSE, new Object[0]);
        return new BuiltWhere(w, argsOut.toArray());
    }

    private static Where build(JdqlAst.Pred p, EntityModel<?> model,
                               Map<String, Attribute<?, ?>> attrIndex,
                               Object[] callArgs, CallShape shape,
                               List<Object> argsOut, boolean[] alwaysFalse) {
        Attribute<?, ?> a;
        switch (p) {
            case JdqlAst.Cmp(var attr, var op, var arg) -> {
                a = lookup(model, attrIndex, attr);
                argsOut.add(resolveArg(arg, callArgs, shape, a.javaType()));
                return cmpOf(a, op);
            }
            case JdqlAst.FnCmp(var fn, var attr, var op, var arg) -> {
                a = lookup(model, attrIndex, attr);
                argsOut.add(resolveArg(arg, callArgs, shape, comparedType(fn, a)));
                return new Where.Func(fn, cmpOf(a, op));
            }
            case JdqlAst.IsNull(var attr, var negated) -> {
                a = lookup(model, attrIndex, attr);
                return negated ? new Where.IsNotNull(a) : new Where.IsNull(a);
            }
            case JdqlAst.FnIsNull(var fn, var attr, var negated) -> {
                a = lookup(model, attrIndex, attr);
                return new Where.Func(fn, negated ? new Where.IsNotNull(a) : new Where.IsNull(a));
            }
            case JdqlAst.Between(var attr, var lo, var hi) -> {
                a = lookup(model, attrIndex, attr);
                argsOut.add(resolveArg(lo, callArgs, shape, a.javaType()));
                argsOut.add(resolveArg(hi, callArgs, shape, a.javaType()));
                return new Where.Between(a);
            }
            case JdqlAst.FnBetween(var fn, var attr, var lo, var hi) -> {
                a = lookup(model, attrIndex, attr);
                argsOut.add(resolveArg(lo, callArgs, shape, comparedType(fn, a)));
                argsOut.add(resolveArg(hi, callArgs, shape, comparedType(fn, a)));
                return new Where.Func(fn, new Where.Between(a));
            }
            case JdqlAst.In(var attr, var args, var collection) -> {
                a = lookup(model, attrIndex, attr);
                return flagNoMatch(buildIn(a, args, collection, callArgs, shape, argsOut, null), alwaysFalse);
            }
            case JdqlAst.FnIn(var fn, var attr, var args, var collection) -> {
                a = lookup(model, attrIndex, attr);
                return flagNoMatch(buildIn(a, args, collection, callArgs, shape, argsOut, fn), alwaysFalse);
            }
            case JdqlAst.And(var children) -> {
                List<Where> cs = new ArrayList<>(children.size());
                for (JdqlAst.Pred c : children) cs.add(build(c, model, attrIndex, callArgs, shape, argsOut, alwaysFalse));
                return new Where.And(cs);
            }
            case JdqlAst.Or(var children) -> {
                List<Where> cs = new ArrayList<>(children.size());
                for (JdqlAst.Pred c : children) cs.add(build(c, model, attrIndex, callArgs, shape, argsOut, alwaysFalse));
                return new Where.Or(cs);
            }
            case JdqlAst.Not(var child) -> {
                return new Where.Not(build(child, model, attrIndex, callArgs, shape, argsOut, alwaysFalse));
            }
        }
    }

    /**
     * The type a value compared to {@code fn(attr)} is bound as, as {@link WhereBinder} binds it: an {@code Integer}
     * for {@code LENGTH}, which counts characters, else the attribute's own.
     */
    private static Class<?> comparedType(String fn, Attribute<?, ?> a) {
        return LENGTH.equals(fn) ? Integer.class : a.javaType();
    }

    private static Where cmpOf(Attribute<?, ?> a, JdqlAst.Op op) {
        return switch (op) {
            case EQ -> new Where.Eq(a);
            case NE -> new Where.NotEq(a);
            case LT -> new Where.Lt(a);
            case LTE -> new Where.Lte(a);
            case GT -> new Where.Gt(a);
            case GTE -> new Where.Gte(a);
            case LIKE -> new Where.Like(a);
        };
    }

    /** {@code where}, {@code alwaysFalse} set when it is the empty-IN {@link Where#ALWAYS_FALSE}. */
    private static Where flagNoMatch(Where where, boolean[] alwaysFalse) {
        if (where == Where.ALWAYS_FALSE) alwaysFalse[0] = true;
        return where;
    }

    /** The IN predicate, {@link Where#ALWAYS_FALSE} for an IN over an empty collection. */
    private static Where buildIn(Attribute<?, ?> a, List<JdqlAst.ArgRef> argRefs, boolean collection,
                                 Object[] callArgs, CallShape shape,
                                 List<Object> argsOut, String wrapFn) {
        Class<?> target = wrapFn == null ? a.javaType() : comparedType(wrapFn, a);
        Where inner;
        if (collection) {
            Object v = resolveArg(argRefs.get(0), callArgs, shape, target);
            if (v instanceof Collection<?> col) {
                if (col.isEmpty()) {
                    return Where.ALWAYS_FALSE;
                }
                for (Object e : col) argsOut.add(e);
                inner = new Where.In(a, col.size());
            } else {
                argsOut.add(v);
                inner = new Where.In(a, 1);
            }
        } else {
            for (JdqlAst.ArgRef r : argRefs) argsOut.add(resolveArg(r, callArgs, shape, target));
            inner = new Where.In(a, argRefs.size());
        }
        return wrapFn == null ? inner : new Where.Func(wrapFn, inner);
    }

    /**
     * M8-3 — flat name fast path for {@code attr}; dotted names ({@code book.author.name}) are
     * resolved via {@link PathResolver} which walks target metamodels and produces a
     * {@link io.vidocq.mansart.data.dialect.attribute.JoinedAttribute}.
     */
    private static Attribute<?, ?> lookup(EntityModel<?> model, Map<String, Attribute<?, ?>> idx, String name) {
        if (name.indexOf('.') < 0) {
            Attribute<?, ?> a = idx.get(name);
            if (a == null) throw new MansartDataException("Unknown attribute: " + name);
            return a;
        }
        return PathResolver.resolve(model, name);
    }

    /**
     * The value {@code ref} stands for in this call: a literal lifted by {@link #resolveLiteral}, or the argument of
     * a named or positional parameter; converted to {@code target}, the type of the attribute it meets, when the
     * shape says so ({@link JdqlValues}), as is otherwise.
     */
    private static Object resolveArg(JdqlAst.ArgRef ref, Object[] args, CallShape shape, Class<?> target) {
        if (ref.isLiteral()) {
            Object value = resolveLiteral(ref.literal(), target);
            return shape.convert() ? JdqlValues.convert(value, target, literalLabel(ref.literal())) : value;
        }
        Object value;
        if (ref.isNamed()) {
            Integer i = shape.names().get(ref.named());
            if (i == null) throw new MansartDataException("@Query references :" + ref.named()
                    + " but the method has no parameter with that name (compile with -parameters or use ?N)");
            value = args[i];
        } else {
            value = args[ref.positional() - 1];
        }
        return shape.convert() ? JdqlValues.convert(value, target, parameterLabel(ref)) : value;
    }

    /** How an error names a parameter: {@code :name} or {@code ?1}. */
    private static String parameterLabel(JdqlAst.ArgRef ref) {
        return ref.isNamed() ? ":" + ref.named() : "?" + ref.positional();
    }

    /** How an error names a literal: a text in quotes, {@code 'LOST'}, anything else as it reads. */
    private static String literalLabel(Object literal) {
        return literal instanceof String text ? "'" + text + "'" : String.valueOf(literal);
    }

    /**
     * Lift a JDQL literal token to a runtime value. A string of the form {@code pkg.Enum.CONSTANT} met by an attribute
     * whose type is an enum, and whose qualifier names that very enum (its canonical name, {@code pkg.Outer.Inner}, or
     * its binary name, {@code pkg.Outer$Inner}), is that enum's constant. No class is ever loaded from a name the
     * statement holds (MANSART-023): the enum is the one the entity model gives. Any other literal is returned verbatim.
     */
    private static Object resolveLiteral(Object lit, Class<?> target) {
        if (lit instanceof String s && target != null && target.isEnum()) {
            int last = s.lastIndexOf('.');
            if (last > 0 && namesEnum(s.substring(0, last), target)) {
                Object constant = enumConstant(target, s.substring(last + 1));
                if (constant != null) return constant;
            }
        }
        return lit;
    }

    /** Whether {@code qualifier} names {@code enumType}, by its canonical or its binary name. */
    private static boolean namesEnum(String qualifier, Class<?> enumType) {
        return qualifier.equals(enumType.getName()) || qualifier.equals(enumType.getCanonicalName());
    }

    /** The constant {@code name} of {@code enumType}, or {@code null} when it has none of that name. */
    private static Object enumConstant(Class<?> enumType, String name) {
        for (Object constant : enumType.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(name)) return constant;
        }
        return null;
    }

    /* ---- helpers ---- */

    /**
     * M7-24 — render a JDQL value expression (supports + - * /, attribute references, and arg
     * references) into a SQL fragment. Bindings flow into {@code out} in the order the
     * placeholders appear, paired with {@code targetType} for the dialect to coerce against
     * (the SET column's Java type).
     */
    private static void renderExpr(StringBuilder sb, JdqlAst.Expr expr,
                                   Map<String, Attribute<?, ?>> attrIndex,
                                   Object[] callArgs, CallShape shape,
                                   SetValues out, Class<?> targetType) {
        switch (expr) {
            case JdqlAst.ExprAttr(var name) -> {
                Attribute<?, ?> attr = attrIndex.get(name);
                if (attr == null) throw new MansartDataException("Unknown attribute in SET: " + name);
                sb.append('"').append(attr.columnName()).append('"');
            }
            case JdqlAst.ExprArg(var arg) -> {
                out.values().add(resolveArg(arg, callArgs, shape, targetType));
                out.types().add(targetType);
                sb.append('?');
            }
            case JdqlAst.ExprBin(var left, var op, var right) -> {
                sb.append('(');
                renderExpr(sb, left, attrIndex, callArgs, shape, out, targetType);
                sb.append(' ').append(op).append(' ');
                renderExpr(sb, right, attrIndex, callArgs, shape, out, targetType);
                sb.append(')');
            }
            // M8-1 — render scalar functions in SET RHS. LENGTH → CHAR_LENGTH (SQL-portable).
            // CONCAT renders as fn(arg1, arg2, ...) per SQL standard (works on H2 and PG).
            // Bound parameters in fn args inherit the SET column's targetType — sufficient for
            // the common case where all CONCAT pieces are strings.
            case JdqlAst.ExprFunc(var name, var args) -> {
                String sqlFn = LENGTH.equals(name) ? "CHAR_LENGTH" : name;
                Class<?> innerType = LENGTH.equals(name) ? Integer.class : targetType;
                sb.append(sqlFn).append('(');
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) sb.append(", ");
                    renderExpr(sb, args.get(i), attrIndex, callArgs, shape, out, innerType);
                }
                sb.append(')');
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static OrderBy appendRuntimeSorts(OrderBy base, Object[] args,
                                              Map<String, Attribute<?, ?>> attrIndex) {
        if (args == null) return base;
        java.util.List<OrderBy.Order> out = new ArrayList<>(base.orders());
        for (Object a : args) {
            if (a instanceof jakarta.data.Sort<?> s) {
                addSort(s, attrIndex, out);
            } else if (a instanceof jakarta.data.Sort[] arr) {
                for (jakarta.data.Sort<?> s : arr) addSort(s, attrIndex, out);
            } else if (a instanceof jakarta.data.Order<?> o) {
                for (jakarta.data.Sort<?> s : o.sorts()) addSort(s, attrIndex, out);
            }
        }
        return out.isEmpty() ? OrderBy.NONE : new OrderBy(out);
    }

    private static void addSort(jakarta.data.Sort<?> s, Map<String, Attribute<?, ?>> attrIndex,
                                java.util.List<OrderBy.Order> out) {
        Attribute<?, ?> a = attrIndex.get(s.property());
        if (a == null) {
            throw new MansartDataException("Sort references unknown attribute: " + s.property());
        }
        out.add(s.isAscending() ? OrderBy.Order.asc(a) : OrderBy.Order.desc(a));
    }

    /**
     * Maps named parameters to method-arg indices. Resolution order:
     * <ol>
     *   <li>{@code @jakarta.data.repository.Param("name")} on the parameter (authoritative).</li>
     *   <li>{@code Parameter.getName()} when {@code -parameters} is honoured by javac.</li>
     * </ol>
     * Both are read so deployments compiled without {@code -parameters} (e.g. the official
     * Jakarta Data TCK jar built with maven-compiler-plugin 4.0.0-beta-4 — see BUG-20260505-01,
     * M7-9) still resolve {@code @Query} {@code :name} bindings via {@code @Param}.
     */
    static Map<String, Integer> nameToIndexFor(Method m) {
        Map<String, Integer> map = new HashMap<>();
        java.lang.reflect.Parameter[] params = m.getParameters();
        for (int i = 0; i < params.length; i++) {
            jakarta.data.repository.Param param = params[i].getDeclaredAnnotation(jakarta.data.repository.Param.class);
            if (param != null && !param.value().isEmpty()) {
                map.put(param.value(), i);
            }
            // Also register the reflective name (arg0/arg1 when -parameters absent — harmless,
            // a well-named @Param always wins because it's set first).
            String n = params[i].getName();
            map.putIfAbsent(n, i);
        }
        return map;
    }

    private static Class<?> boxed(Class<?> c) {
        if (c == boolean.class) return Boolean.class;
        if (c == byte.class)    return Byte.class;
        if (c == short.class)   return Short.class;
        if (c == int.class)     return Integer.class;
        if (c == long.class)    return Long.class;
        if (c == float.class)   return Float.class;
        if (c == double.class)  return Double.class;
        return c;
    }

    /**
     * M8-2 — dispatch the result of a multi-column projection (one {@code Object[]} per row)
     * to the requested return type:
     * <ul>
     *   <li>{@code List<Object[]>}/{@code Collection}/{@code Iterable} → the rows directly</li>
     *   <li>{@code Stream<Object[]>} → {@code rows.stream()}</li>
     *   <li>{@code Object[][]} → 2-D array</li>
     *   <li>{@code Optional<Object[]>} → first row or empty</li>
     *   <li>Record types ({@code List<R>}, {@code Stream<R>}, {@code R[]}, {@code Optional<R>}, {@code R})
     *       — each row is mapped to a canonical record constructor whose component count matches
     *       the projected attribute count. Components are passed in JDQL declaration order.</li>
     * </ul>
     */
    public static Object dispatchMultiProjection(Class<?> rt, Class<?> elem, List<Object[]> rows,
                                                  java.util.List<Attribute<?, ?>> attrs) {
        boolean isRecordTarget = elem != null && elem != Object[].class && elem.isRecord();

        // Object[]-shaped returns
        if (isListLike(rt)) {
            return isRecordTarget ? mapRowsToRecords(rows, elem, attrs) : rows;
        }
        if (rt == java.util.stream.Stream.class) {
            return isRecordTarget ? mapRowsToRecords(rows, elem, attrs).stream() : rows.stream();
        }
        if (rt.isArray()) return multiProjectionArray(rt, rows, attrs);
        if (rt == java.util.Optional.class) return firstRow(rows, isRecordTarget ? elem : null, attrs);
        if (rt.isRecord()) return singleRecord(rt, rows, attrs);
        throw new MansartDataException("Unsupported multi-projection return type: " + rt);
    }

    /** The first row, mapped to {@code recordType} unless it is {@code null}; empty without rows. */
    private static java.util.Optional<Object> firstRow(List<Object[]> rows, Class<?> recordType,
                                                       java.util.List<Attribute<?, ?>> attrs) {
        if (rows.isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(recordType == null ? rows.get(0) : mapRowToRecord(rows.get(0), recordType, attrs));
    }

    /** The first row as a {@code recordType}; an {@link jakarta.data.exceptions.EmptyResultException} without rows. */
    private static Object singleRecord(Class<?> recordType, List<Object[]> rows, java.util.List<Attribute<?, ?>> attrs) {
        if (rows.isEmpty()) {
            throw new jakarta.data.exceptions.EmptyResultException(
                    "Multi-projection returned no result for " + recordType.getSimpleName());
        }
        return mapRowToRecord(rows.get(0), recordType, attrs);
    }

    /** {@code Object[][]} holding the rows, or {@code R[]} holding one record per row. */
    private static Object multiProjectionArray(Class<?> rt, List<Object[]> rows,
                                               java.util.List<Attribute<?, ?>> attrs) {
        Class<?> comp = rt.getComponentType();
        if (comp == Object[].class) return rows.toArray(new Object[0][]);
        if (comp.isRecord()) return toArray(comp, mapRowsToRecords(rows, comp, attrs));
        throw new MansartDataException("Multi-projection array return must be Object[][] or RecordType[], got: " + rt);
    }

    /** A {@code List}, a {@code Collection} or an {@code Iterable}: the return types a list is handed back as. */
    private static boolean isListLike(Class<?> rt) {
        return java.util.List.class.isAssignableFrom(rt) || java.util.Collection.class == rt || Iterable.class == rt;
    }

    /** An array of {@code component} holding {@code values} in order. */
    private static Object toArray(Class<?> component, List<?> values) {
        Object arr = java.lang.reflect.Array.newInstance(component, values.size());
        for (int i = 0; i < values.size(); i++) java.lang.reflect.Array.set(arr, i, values.get(i));
        return arr;
    }

    private static java.util.List<Object> mapRowsToRecords(List<Object[]> rows, Class<?> recordType,
                                                            java.util.List<Attribute<?, ?>> attrs) {
        java.util.List<Object> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) out.add(mapRowToRecord(row, recordType, attrs));
        return out;
    }

    private static Object mapRowToRecord(Object[] row, Class<?> recordType,
                                         java.util.List<Attribute<?, ?>> attrs) {
        java.lang.reflect.RecordComponent[] comps = recordType.getRecordComponents();
        if (comps.length != attrs.size()) {
            throw new MansartDataException("Record " + recordType.getSimpleName()
                    + " has " + comps.length + " components but projection selected " + attrs.size() + " attributes");
        }
        Class<?>[] paramTypes = new Class<?>[comps.length];
        for (int i = 0; i < comps.length; i++) paramTypes[i] = comps[i].getType();
        try {
            return recordType.getDeclaredConstructor(paramTypes).newInstance(row);
        } catch (ReflectiveOperationException e) {
            throw new MansartDataException("Failed to construct " + recordType.getName() + " from projection row", e);
        }
    }

    /** Boxed, so that the driver can convert a column to it (MANSART-015). */
    private static Class<?> projectionElement(Method m) {
        Class<?> rt = m.getReturnType();
        if (rt.isArray()) return boxed(rt.getComponentType());
        java.lang.reflect.Type genericRt = m.getGenericReturnType();
        if (genericRt instanceof java.lang.reflect.ParameterizedType pt) {
            java.lang.reflect.Type[] args = pt.getActualTypeArguments();
            if (args.length == 1 && args[0] instanceof Class<?> c) return c;
        }
        return boxed(rt);
    }
}
