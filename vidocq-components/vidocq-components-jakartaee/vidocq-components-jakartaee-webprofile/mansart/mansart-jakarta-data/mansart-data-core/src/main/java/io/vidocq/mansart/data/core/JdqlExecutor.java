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
        return switch (stmt.kind) {
            case SELECT -> new JdqlResult.Entities((List<?>) result);
            case COUNT, UPDATE, DELETE -> new JdqlResult.Count(((Number) result).longValue());
            case AGGREGATE -> new JdqlResult.Value(result);
            case PROJECT -> new JdqlResult.Rows(List.of(stmt.scalarAttr), columnRows((List<?>) result));
            case PROJECT_MULTI -> new JdqlResult.Rows(stmt.projectAttrs, projectedRows(result));
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
                i++;
                while (i < n) {
                    if (jdql.charAt(i) == '\'') {
                        // '' inside a literal is one quote
                        if (i + 1 < n && jdql.charAt(i + 1) == '\'') { i += 2; continue; }
                        i++;
                        break;
                    }
                    i++;
                }
            } else if (c == ':' || c == '?' || Character.isDigit(c)) {
                i++;
                while (i < n && isWordPart(jdql.charAt(i))) i++;
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && isWordPart(jdql.charAt(i))) i++;
                out.add(jdql.substring(start, i));
            } else {
                i++;
            }
        }
        return out;
    }

    private static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }

    /** The named parameters {@code stmt} uses, each once, in the order they appear; a positional one is refused. */
    private static List<String> parameterNames(JdqlAst.Stmt stmt) {
        List<JdqlAst.ArgRef> refs = new ArrayList<>();
        for (JdqlAst.SetAssign sa : stmt.setAssignments) collectArgs(sa.value(), refs);
        if (stmt.where != null) collectArgs(stmt.where, refs);
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (JdqlAst.ArgRef ref : refs) {
            if (ref.isLiteral) continue;
            if (!ref.isNamed()) {
                throw new MansartDataException("positional parameters are not supported here; use :name");
            }
            names.add(ref.named);
        }
        return List.copyOf(names);
    }

    private static void collectArgs(JdqlAst.Pred p, List<JdqlAst.ArgRef> out) {
        switch (p) {
            case JdqlAst.Cmp c -> out.add(c.arg());
            case JdqlAst.FnCmp c -> out.add(c.arg());
            case JdqlAst.IsNull ignored -> { /* no value */ }
            case JdqlAst.FnIsNull ignored -> { /* no value */ }
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
            case JdqlAst.And and -> and.children().forEach(c -> collectArgs(c, out));
            case JdqlAst.Or or -> or.children().forEach(c -> collectArgs(c, out));
            case JdqlAst.Not not -> collectArgs(not.child(), out);
        }
    }

    private static void collectArgs(JdqlAst.Expr e, List<JdqlAst.ArgRef> out) {
        switch (e) {
            case JdqlAst.ExprArg a -> out.add(a.arg());
            case JdqlAst.ExprAttr ignored -> { /* an attribute, no value */ }
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
        return switch (stmt.kind) {
            case SELECT -> new CallShape(List.class, model.entityClass(), names, true);
            case PROJECT -> new CallShape(List.class,
                    boxed(lookup(model, attrIndex, stmt.scalarAttr).javaType()), names, true);
            case PROJECT_MULTI -> new CallShape(List.class, Object[].class, names, true);
            case AGGREGATE -> new CallShape(
                    aggregateType(stmt.aggregateOp, lookup(model, attrIndex, stmt.scalarAttr).javaType()),
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
            case "SUM" -> integral ? Long.class : floating ? Double.class : type;
            default -> type;
        };
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
    @SuppressWarnings({"rawtypes", "unchecked"})
    static Object execute(JdqlAst.Stmt stmt, CallShape shape, EntityModel<?> model,
                          Map<String, Attribute<?, ?>> attrIndex,
                          RepositoryRuntime runtime, Object[] args, Pagination pagination) {
        OrderBy orderBy = buildOrderBy(stmt.orderBy, model, attrIndex);
        // M7-21 — fold any Sort/Order control args into the OrderBy. The query's own ORDER BY
        // (from JDQL) takes precedence (declared first); runtime Sort args extend it.
        orderBy = appendRuntimeSorts(orderBy, args, attrIndex);

        return switch (stmt.kind) {
            case SELECT, COUNT -> {
                BuiltWhere bw = buildWhere(stmt.where, model, attrIndex, args, shape);
                if (stmt.kind == JdqlAst.Stmt.Kind.COUNT) {
                    yield bw.where == Where.ALWAYS_FALSE ? 0L
                            : runtime.countWhere((EntityModel) model, bw.where, bw.args);
                }
                Class<?> rt = shape.returnType();
                boolean optional = rt == java.util.Optional.class;
                boolean stream   = rt == java.util.stream.Stream.class;
                boolean list     = java.util.List.class.isAssignableFrom(rt)
                                 || java.util.Collection.class == rt
                                 || Iterable.class == rt;
                boolean array    = rt.isArray();
                boolean longRet  = rt == long.class || rt == Long.class;
                boolean boolRet  = rt == boolean.class || rt == Boolean.class;
                boolean pageRet  = jakarta.data.page.Page.class.isAssignableFrom(rt);
                boolean cursorRet = jakarta.data.page.CursoredPage.class.isAssignableFrom(rt);
                if (bw.where == Where.ALWAYS_FALSE) {
                    if (optional) yield java.util.Optional.empty();
                    if (stream)   yield java.util.stream.Stream.empty();
                    if (list)     yield java.util.List.of();
                    if (longRet)  yield 0L;
                    if (boolRet)  yield false;
                    throw new MansartDataException("@Query empty IN with single-entity return");
                }
                if (longRet)  yield runtime.countWhere((EntityModel) model, bw.where, bw.args);
                if (boolRet)  yield runtime.existsWhere((EntityModel) model, bw.where, bw.args);
                if (cursorRet || pageRet) {
                    jakarta.data.page.PageRequest pr = findPageRequest(args);
                    if (pr == null) {
                        throw new MansartDataException("@Query returning Page/CursoredPage requires a PageRequest argument");
                    }
                    if (cursorRet) yield runtime.queryCursored((EntityModel) model, bw.where, orderBy, pr, bw.args);
                    yield runtime.queryPage((EntityModel) model, bw.where, orderBy, pr, bw.args);
                }
                if (optional) yield runtime.queryOne((EntityModel) model, bw.where, bw.args);
                List<Object> data = runtime.queryList((EntityModel) model, bw.where, orderBy, pagination, bw.args);
                jakarta.data.Limit lim2 = findLimit(args);
                if (lim2 != null) {
                    int from = Math.max(0, (int) (lim2.startAt() - 1));
                    int to = Math.min(data.size(), from + (int) lim2.maxResults());
                    data = (from >= data.size()) ? new ArrayList<>()
                                                  : new ArrayList<>(data.subList(from, to));
                }
                if (stream) yield data.stream();
                if (list)   yield data;
                if (array)  {
                    Object arr = java.lang.reflect.Array.newInstance(rt.getComponentType(), data.size());
                    for (int i = 0; i < data.size(); i++) java.lang.reflect.Array.set(arr, i, data.get(i));
                    yield arr;
                }
                java.util.Optional<?> one = runtime.queryOne((EntityModel) model, bw.where, bw.args);
                if (one.isEmpty()) {
                    throw new jakarta.data.exceptions.EmptyResultException(
                            "@Query returned no result for " + model.entityClass().getSimpleName());
                }
                yield one.get();
            }
            case AGGREGATE -> {
                BuiltWhere bw = buildWhere(stmt.where, model, attrIndex, args, shape);
                Attribute<?, ?> attr = lookup(model, attrIndex, stmt.scalarAttr);
                Class<?> rt = shape.returnType();
                Class<?> boxed = boxed(rt);
                Object v = runtime.aggregate((EntityModel) model, stmt.aggregateOp, attr, boxed, bw.where, bw.args);
                if (v == null && rt.isPrimitive()) yield zeroFor(rt);
                yield v;
            }
            case PROJECT_MULTI -> {
                BuiltWhere bw = buildWhere(stmt.where, model, attrIndex, args, shape);
                java.util.List<Attribute<?, ?>> attrs = new ArrayList<>(stmt.projectAttrs.size());
                for (String name : stmt.projectAttrs) {
                    attrs.add(lookup(model, attrIndex, name));
                }
                Class<?> rt = shape.returnType();
                List<Object[]> rows = runtime.projectColumns((EntityModel) model, attrs,
                        bw.where, orderBy, pagination, bw.args);
                jakarta.data.Limit lim = findLimit(args);
                if (lim != null) {
                    int from = Math.max(0, (int) (lim.startAt() - 1));
                    int to = Math.min(rows.size(), from + (int) lim.maxResults());
                    rows = (from >= rows.size()) ? java.util.List.of()
                                                  : new ArrayList<>(rows.subList(from, to));
                }
                yield dispatchMultiProjection(rt, shape.element(), rows, attrs);
            }
            case PROJECT -> {
                BuiltWhere bw = buildWhere(stmt.where, model, attrIndex, args, shape);
                Attribute<?, ?> attr = lookup(model, attrIndex, stmt.scalarAttr);
                Class<?> rt = shape.returnType();
                Class<?> elem = shape.element();
                List<Object> col = runtime.projectColumn((EntityModel) model, attr,
                        (Class) elem, bw.where, orderBy, pagination, bw.args);
                // Apply a Limit argument (Jakarta Data control parameter) by slicing.
                jakarta.data.Limit lim = findLimit(args);
                if (lim != null) {
                    int from = Math.max(0, (int) (lim.startAt() - 1));
                    int to = Math.min(col.size(), from + (int) lim.maxResults());
                    col = (from >= col.size()) ? java.util.List.of()
                                               : new ArrayList<>(col.subList(from, to));
                }
                if (jakarta.data.page.Page.class.isAssignableFrom(rt)) {
                    jakarta.data.page.PageRequest pr2 = findPageRequest(args);
                    if (pr2 == null) {
                        throw new MansartDataException("@Query projection returning Page requires a PageRequest argument");
                    }
                    int pageSize = pr2.size();
                    int offset = (int) ((pr2.page() - 1) * pageSize);
                    int from = Math.min(offset, col.size());
                    int to   = Math.min(offset + pageSize, col.size());
                    java.util.List<Object> pageContent = new ArrayList<>(col.subList(from, to));
                    yield new MansartPage<>(pageContent, pr2, col.size());
                }
                if (java.util.List.class.isAssignableFrom(rt)
                        || java.util.Collection.class == rt
                        || Iterable.class == rt) yield col;
                if (rt == java.util.stream.Stream.class) yield col.stream();
                if (rt == java.util.Optional.class) yield col.isEmpty()
                        ? java.util.Optional.empty()
                        : java.util.Optional.ofNullable(col.get(0));
                if (rt.isArray()) {
                    Object arr = java.lang.reflect.Array.newInstance(rt.getComponentType(), col.size());
                    for (int i = 0; i < col.size(); i++) java.lang.reflect.Array.set(arr, i, col.get(i));
                    yield arr;
                }
                if (col.isEmpty()) {
                    throw new jakarta.data.exceptions.EmptyResultException(
                            "Projection of " + stmt.scalarAttr + " returned no result");
                }
                yield col.get(0);
            }
            case UPDATE -> {
                StringBuilder setSql = new StringBuilder();
                List<Object> setVals = new ArrayList<>();
                List<Class<?>> setTypes = new ArrayList<>();
                boolean firstSet = true;
                for (JdqlAst.SetAssign sa : stmt.setAssignments) {
                    // SET LHS doesn't allow path expressions (cannot UPDATE through a join). Stay
                    // on the flat attrIndex and reject paths explicitly.
                    if (sa.attr().indexOf('.') >= 0) {
                        throw new MansartDataException("UPDATE SET LHS cannot be a path expression: " + sa.attr());
                    }
                    Attribute<?, ?> a = attrIndex.get(sa.attr());
                    if (a == null) throw new MansartDataException("Unknown attribute: " + sa.attr());
                    if (!firstSet) setSql.append(", ");
                    setSql.append('"').append(a.columnName()).append("\" = ");
                    renderExpr(setSql, sa.value(), attrIndex, args, shape, setVals, setTypes, a.javaType());
                    firstSet = false;
                }
                BuiltWhere bw = buildWhere(stmt.where, model, attrIndex, args, shape);
                long n = runtime.executeUpdateRaw((EntityModel) model, setSql.toString(),
                        setVals, setTypes, bw.where, bw.args);
                yield wrapLongResult(shape.returnType(), n);
            }
            case DELETE -> {
                BuiltWhere bw = buildWhere(stmt.where, model, attrIndex, args, shape);
                long n = runtime.deleteWhere((EntityModel) model, bw.where, bw.args);
                yield wrapLongResult(shape.returnType(), n);
            }
        };
    }

    private static Object wrapLongResult(Class<?> rt, long n) {
        if (rt == void.class) return null;
        if (rt == boolean.class || rt == Boolean.class) return n > 0;
        if (rt == int.class || rt == Integer.class) return (int) n;
        return n;
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
            case JdqlAst.Cmp c -> {
                a = lookup(model, attrIndex, c.attr());
                argsOut.add(resolveArg(c.arg(), callArgs, shape, a.javaType()));
                return cmpOf(a, c.op());
            }
            case JdqlAst.FnCmp c -> {
                a = lookup(model, attrIndex, c.attr());
                argsOut.add(resolveArg(c.arg(), callArgs, shape, comparedType(c.fn(), a)));
                return new Where.Func(c.fn(), cmpOf(a, c.op()));
            }
            case JdqlAst.IsNull n -> {
                a = lookup(model, attrIndex, n.attr());
                return n.negated() ? new Where.IsNotNull(a) : new Where.IsNull(a);
            }
            case JdqlAst.FnIsNull n -> {
                a = lookup(model, attrIndex, n.attr());
                return new Where.Func(n.fn(), n.negated() ? new Where.IsNotNull(a) : new Where.IsNull(a));
            }
            case JdqlAst.Between b -> {
                a = lookup(model, attrIndex, b.attr());
                argsOut.add(resolveArg(b.lo(), callArgs, shape, a.javaType()));
                argsOut.add(resolveArg(b.hi(), callArgs, shape, a.javaType()));
                return new Where.Between(a);
            }
            case JdqlAst.FnBetween b -> {
                a = lookup(model, attrIndex, b.attr());
                argsOut.add(resolveArg(b.lo(), callArgs, shape, comparedType(b.fn(), a)));
                argsOut.add(resolveArg(b.hi(), callArgs, shape, comparedType(b.fn(), a)));
                return new Where.Func(b.fn(), new Where.Between(a));
            }
            case JdqlAst.In in -> {
                a = lookup(model, attrIndex, in.attr());
                return buildIn(a, in.args(), in.collection(), callArgs, shape, argsOut, alwaysFalse, null);
            }
            case JdqlAst.FnIn in -> {
                a = lookup(model, attrIndex, in.attr());
                return buildIn(a, in.args(), in.collection(), callArgs, shape, argsOut, alwaysFalse, in.fn());
            }
            case JdqlAst.And and -> {
                List<Where> cs = new ArrayList<>(and.children().size());
                for (JdqlAst.Pred c : and.children()) cs.add(build(c, model, attrIndex, callArgs, shape, argsOut, alwaysFalse));
                return new Where.And(cs);
            }
            case JdqlAst.Or or -> {
                List<Where> cs = new ArrayList<>(or.children().size());
                for (JdqlAst.Pred c : or.children()) cs.add(build(c, model, attrIndex, callArgs, shape, argsOut, alwaysFalse));
                return new Where.Or(cs);
            }
            case JdqlAst.Not n -> {
                return new Where.Not(build(n.child(), model, attrIndex, callArgs, shape, argsOut, alwaysFalse));
            }
        }
    }

    /**
     * The type a value compared to {@code fn(attr)} is bound as, as {@link WhereBinder} binds it: an {@code Integer}
     * for {@code LENGTH}, which counts characters, else the attribute's own.
     */
    private static Class<?> comparedType(String fn, Attribute<?, ?> a) {
        return "LENGTH".equals(fn) ? Integer.class : a.javaType();
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

    private static Where buildIn(Attribute<?, ?> a, List<JdqlAst.ArgRef> argRefs, boolean collection,
                                 Object[] callArgs, CallShape shape,
                                 List<Object> argsOut, boolean[] alwaysFalse, String wrapFn) {
        Class<?> target = wrapFn == null ? a.javaType() : comparedType(wrapFn, a);
        Where inner;
        if (collection) {
            Object v = resolveArg(argRefs.get(0), callArgs, shape, target);
            if (v instanceof Collection<?> col) {
                if (col.isEmpty()) {
                    alwaysFalse[0] = true;
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
        if (ref.isLiteral) {
            Object value = resolveLiteral(ref.literal);
            return shape.convert() ? JdqlValues.convert(value, target, literalLabel(ref.literal)) : value;
        }
        Object value;
        if (ref.isNamed()) {
            Integer i = shape.names().get(ref.named);
            if (i == null) throw new MansartDataException("@Query references :" + ref.named
                    + " but the method has no parameter with that name (compile with -parameters or use ?N)");
            value = args[i];
        } else {
            value = args[ref.positional - 1];
        }
        return shape.convert()
                ? JdqlValues.convert(value, target, ref.isNamed() ? ":" + ref.named : "?" + ref.positional)
                : value;
    }

    /** How an error names a literal: a text in quotes, {@code 'LOST'}, anything else as it reads. */
    private static String literalLabel(Object literal) {
        return literal instanceof String text ? "'" + text + "'" : String.valueOf(literal);
    }

    /**
     * Lift a JDQL literal token to a runtime value. Strings whose form is
     * {@code pkg.Class.MEMBER} (≥ 2 dots, last segment uppercase) are loaded as enum constants;
     * strings without dots are returned verbatim; numbers/booleans pass through.
     */
    private static Object resolveLiteral(Object lit) {
        if (lit instanceof String s && s.indexOf('.') > 0) {
            int last = s.lastIndexOf('.');
            String member = s.substring(last + 1);
            if (!member.isEmpty() && Character.isUpperCase(member.charAt(0))) {
                String enumFqn = s.substring(0, last);
                try {
                    Class<?> raw = loadClassForEnum(enumFqn);
                    if (raw != null && raw.isEnum()) {
                        @SuppressWarnings({"rawtypes", "unchecked"})
                        Enum<?> v = Enum.valueOf((Class) raw, member);
                        return v;
                    }
                } catch (Exception ignored) { /* fall through — treat as plain string */ }
            }
        }
        return lit;
    }

    private static Class<?> loadClassForEnum(String fqn) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = JdqlExecutor.class.getClassLoader();
        // Try as-is, then progressively replace inner-class dot separators with '$'
        try { return Class.forName(fqn, false, cl); } catch (ClassNotFoundException ignored) {}
        StringBuilder sb = new StringBuilder(fqn);
        for (int i = sb.length() - 1; i > 0; i--) {
            if (sb.charAt(i) != '.') continue;
            sb.setCharAt(i, '$');
            try { return Class.forName(sb.toString(), false, cl); } catch (ClassNotFoundException ignored) {}
        }
        return null;
    }

    /* ---- helpers ---- */

    /**
     * M7-24 — render a JDQL value expression (supports + - * /, attribute references, and arg
     * references) into a SQL fragment. Bindings flow into {@code outVals} / {@code outTypes}
     * in the order the placeholders appear, paired with {@code targetType} for the dialect to
     * coerce against (the SET column's Java type).
     */
    @SuppressWarnings("unchecked")
    private static void renderExpr(StringBuilder sb, JdqlAst.Expr expr,
                                   Map<String, Attribute<?, ?>> attrIndex,
                                   Object[] callArgs, CallShape shape,
                                   List<Object> outVals, List<Class<?>> outTypes,
                                   Class<?> targetType) {
        switch (expr) {
            case JdqlAst.ExprAttr a -> {
                Attribute<?, ?> attr = attrIndex.get(a.attr());
                if (attr == null) throw new MansartDataException("Unknown attribute in SET: " + a.attr());
                sb.append('"').append(attr.columnName()).append('"');
            }
            case JdqlAst.ExprArg ar -> {
                outVals.add(resolveArg(ar.arg(), callArgs, shape, targetType));
                outTypes.add(targetType);
                sb.append('?');
            }
            case JdqlAst.ExprBin bin -> {
                sb.append('(');
                renderExpr(sb, bin.left(), attrIndex, callArgs, shape, outVals, outTypes, targetType);
                sb.append(' ').append(bin.op()).append(' ');
                renderExpr(sb, bin.right(), attrIndex, callArgs, shape, outVals, outTypes, targetType);
                sb.append(')');
            }
            // M8-1 — render scalar functions in SET RHS. LENGTH → CHAR_LENGTH (SQL-portable).
            // CONCAT renders as fn(arg1, arg2, ...) per SQL standard (works on H2 and PG).
            // Bound parameters in fn args inherit the SET column's targetType — sufficient for
            // the common case where all CONCAT pieces are strings.
            case JdqlAst.ExprFunc fn -> {
                String sqlFn = "LENGTH".equals(fn.name()) ? "CHAR_LENGTH" : fn.name();
                Class<?> innerType = "LENGTH".equals(fn.name()) ? Integer.class : targetType;
                sb.append(sqlFn).append('(');
                List<JdqlAst.Expr> args = fn.args();
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) sb.append(", ");
                    renderExpr(sb, args.get(i), attrIndex, callArgs, shape, outVals, outTypes, innerType);
                }
                sb.append(')');
            }
        }
    }

    private static jakarta.data.page.PageRequest findPageRequest(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof jakarta.data.page.PageRequest pr) return pr;
        }
        return null;
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

    private static jakarta.data.Limit findLimit(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof jakarta.data.Limit l) return l;
        }
        return null;
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
            for (var ann : params[i].getDeclaredAnnotations()) {
                if (ann.annotationType().getName().equals("jakarta.data.repository.Param")) {
                    try {
                        var v = ann.annotationType().getMethod("value").invoke(ann);
                        if (v instanceof String s && !s.isEmpty()) {
                            map.put(s, i);
                        }
                    } catch (ReflectiveOperationException ignored) { /* fall through */ }
                }
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

    private static Object zeroFor(Class<?> c) {
        if (c == long.class)   return 0L;
        if (c == double.class) return 0.0;
        if (c == float.class)  return 0.0f;
        return 0;
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
        if (java.util.List.class.isAssignableFrom(rt) || java.util.Collection.class == rt || Iterable.class == rt) {
            if (!isRecordTarget) return rows;
            return mapRowsToRecords(rows, elem, attrs);
        }
        if (rt == java.util.stream.Stream.class) {
            return isRecordTarget ? mapRowsToRecords(rows, elem, attrs).stream() : rows.stream();
        }
        if (rt.isArray()) {
            Class<?> comp = rt.getComponentType();
            if (comp == Object[].class) {
                Object[][] arr = new Object[rows.size()][];
                for (int i = 0; i < rows.size(); i++) arr[i] = rows.get(i);
                return arr;
            }
            if (comp.isRecord()) {
                java.util.List<Object> recs = mapRowsToRecords(rows, comp, attrs);
                Object arr = java.lang.reflect.Array.newInstance(comp, recs.size());
                for (int i = 0; i < recs.size(); i++) java.lang.reflect.Array.set(arr, i, recs.get(i));
                return arr;
            }
            throw new MansartDataException("Multi-projection array return must be Object[][] or RecordType[], got: " + rt);
        }
        if (rt == java.util.Optional.class) {
            if (rows.isEmpty()) return java.util.Optional.empty();
            return isRecordTarget ? java.util.Optional.of(mapRowToRecord(rows.get(0), elem, attrs))
                                  : java.util.Optional.of(rows.get(0));
        }
        if (rt.isRecord()) {
            if (rows.isEmpty()) {
                throw new jakarta.data.exceptions.EmptyResultException(
                        "Multi-projection returned no result for " + rt.getSimpleName());
            }
            return mapRowToRecord(rows.get(0), rt, attrs);
        }
        throw new MansartDataException("Unsupported multi-projection return type: " + rt);
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

    private static Class<?> projectionElement(Method m) {
        Class<?> rt = m.getReturnType();
        if (rt.isArray()) return rt.getComponentType();
        java.lang.reflect.Type genericRt = m.getGenericReturnType();
        if (genericRt instanceof java.lang.reflect.ParameterizedType pt) {
            java.lang.reflect.Type[] args = pt.getActualTypeArguments();
            if (args.length == 1 && args[0] instanceof Class<?> c) return c;
        }
        return rt;
    }
}
