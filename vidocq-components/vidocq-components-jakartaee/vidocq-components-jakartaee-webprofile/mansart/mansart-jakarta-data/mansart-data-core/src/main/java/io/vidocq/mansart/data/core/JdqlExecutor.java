package io.vidocq.mansart.data.core;

import io.vidocq.mansart.data.dialect.Attribute;
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
 */
final class JdqlExecutor {

    private JdqlExecutor() {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    static Object execute(JdqlAst.Stmt stmt, Method method, EntityModel<?> model,
                          Map<String, Attribute<?, ?>> attrIndex,
                          RepositoryRuntime runtime, Object[] args, Map<String, Integer> nameToIdx) {
        OrderBy orderBy = buildOrderBy(stmt.orderBy, attrIndex);

        return switch (stmt.kind) {
            case SELECT, COUNT -> {
                BuiltWhere bw = buildWhere(stmt.where, attrIndex, args, nameToIdx);
                if (stmt.kind == JdqlAst.Stmt.Kind.COUNT) {
                    yield bw.where == Where.ALWAYS_FALSE ? 0L
                            : runtime.countWhere((EntityModel) model, bw.where, bw.args);
                }
                Class<?> rt = method.getReturnType();
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
                List<Object> data = runtime.queryList((EntityModel) model, bw.where, orderBy, bw.args);
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
                BuiltWhere bw = buildWhere(stmt.where, attrIndex, args, nameToIdx);
                Attribute<?, ?> attr = attrIndex.get(stmt.scalarAttr);
                if (attr == null) throw new MansartDataException("Unknown attribute: " + stmt.scalarAttr);
                Class<?> rt = method.getReturnType();
                Class<?> boxed = boxed(rt);
                Object v = runtime.aggregate((EntityModel) model, stmt.aggregateOp, attr, boxed, bw.where, bw.args);
                if (v == null && rt.isPrimitive()) yield zeroFor(rt);
                yield v;
            }
            case PROJECT -> {
                BuiltWhere bw = buildWhere(stmt.where, attrIndex, args, nameToIdx);
                Attribute<?, ?> attr = attrIndex.get(stmt.scalarAttr);
                if (attr == null) throw new MansartDataException("Unknown attribute: " + stmt.scalarAttr);
                Class<?> rt = method.getReturnType();
                Class<?> elem = projectionElement(method);
                List<Object> col = runtime.projectColumn((EntityModel) model, attr,
                        (Class) elem, bw.where, orderBy, bw.args);
                // Apply a Limit argument (Jakarta Data control parameter) by slicing.
                jakarta.data.Limit lim = findLimit(args);
                if (lim != null) {
                    int from = Math.max(0, (int) (lim.startAt() - 1));
                    int to = Math.min(col.size(), from + (int) lim.maxResults());
                    col = (from >= col.size()) ? java.util.List.of()
                                               : new ArrayList<>(col.subList(from, to));
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
                List<Attribute<?, ?>> setAttrs = new ArrayList<>(stmt.setAssignments.size());
                List<Object> setVals = new ArrayList<>(stmt.setAssignments.size());
                for (JdqlAst.SetAssign sa : stmt.setAssignments) {
                    Attribute<?, ?> a = attrIndex.get(sa.attr());
                    if (a == null) throw new MansartDataException("Unknown attribute: " + sa.attr());
                    setAttrs.add(a);
                    setVals.add(resolveArg(sa.arg(), args, nameToIdx));
                }
                BuiltWhere bw = buildWhere(stmt.where, attrIndex, args, nameToIdx);
                Object[] all = new Object[setVals.size() + bw.args.length];
                for (int i = 0; i < setVals.size(); i++) all[i] = setVals.get(i);
                System.arraycopy(bw.args, 0, all, setVals.size(), bw.args.length);
                long n = runtime.executeUpdate((EntityModel) model, setAttrs, bw.where, all);
                yield wrapLongResult(method, n);
            }
            case DELETE -> {
                BuiltWhere bw = buildWhere(stmt.where, attrIndex, args, nameToIdx);
                long n = runtime.deleteWhere((EntityModel) model, bw.where, bw.args);
                yield wrapLongResult(method, n);
            }
        };
    }

    private static Object wrapLongResult(Method m, long n) {
        Class<?> rt = m.getReturnType();
        if (rt == void.class) return null;
        if (rt == int.class || rt == Integer.class) return (int) n;
        return n;
    }

    private static OrderBy buildOrderBy(List<JdqlAst.Order> orders, Map<String, Attribute<?, ?>> attrIndex) {
        if (orders == null || orders.isEmpty()) return OrderBy.NONE;
        List<OrderBy.Order> out = new ArrayList<>(orders.size());
        for (JdqlAst.Order o : orders) {
            Attribute<?, ?> a = attrIndex.get(o.attr());
            if (a == null) throw new MansartDataException("Unknown OrderBy attribute: " + o.attr());
            out.add(o.asc() ? OrderBy.Order.asc(a) : OrderBy.Order.desc(a));
        }
        return new OrderBy(out);
    }

    private record BuiltWhere(Where where, Object[] args) {}

    private static BuiltWhere buildWhere(JdqlAst.Pred p, Map<String, Attribute<?, ?>> attrIndex,
                                         Object[] callArgs, Map<String, Integer> nameToIdx) {
        if (p == null) return new BuiltWhere(Where.ALWAYS_TRUE, new Object[0]);
        List<Object> argsOut = new ArrayList<>();
        boolean[] alwaysFalse = { false };
        Where w = build(p, attrIndex, callArgs, nameToIdx, argsOut, alwaysFalse);
        if (alwaysFalse[0]) return new BuiltWhere(Where.ALWAYS_FALSE, new Object[0]);
        return new BuiltWhere(w, argsOut.toArray());
    }

    private static Where build(JdqlAst.Pred p, Map<String, Attribute<?, ?>> attrIndex,
                               Object[] callArgs, Map<String, Integer> nameToIdx,
                               List<Object> argsOut, boolean[] alwaysFalse) {
        Attribute<?, ?> a;
        switch (p) {
            case JdqlAst.Cmp c -> {
                a = lookup(attrIndex, c.attr());
                argsOut.add(resolveArg(c.arg(), callArgs, nameToIdx));
                return switch (c.op()) {
                    case EQ -> new Where.Eq(a);
                    case NE -> new Where.NotEq(a);
                    case LT -> new Where.Lt(a);
                    case LTE -> new Where.Lte(a);
                    case GT -> new Where.Gt(a);
                    case GTE -> new Where.Gte(a);
                    case LIKE -> new Where.Like(a);
                };
            }
            case JdqlAst.IsNull n -> {
                a = lookup(attrIndex, n.attr());
                return n.negated() ? new Where.IsNotNull(a) : new Where.IsNull(a);
            }
            case JdqlAst.Between b -> {
                a = lookup(attrIndex, b.attr());
                argsOut.add(resolveArg(b.lo(), callArgs, nameToIdx));
                argsOut.add(resolveArg(b.hi(), callArgs, nameToIdx));
                return new Where.Between(a);
            }
            case JdqlAst.In in -> {
                a = lookup(attrIndex, in.attr());
                if (in.collection()) {
                    Object v = resolveArg(in.args().get(0), callArgs, nameToIdx);
                    if (v instanceof Collection<?> col) {
                        if (col.isEmpty()) {
                            alwaysFalse[0] = true;
                            return Where.ALWAYS_FALSE;
                        }
                        for (Object e : col) argsOut.add(e);
                        return new Where.In(a, col.size());
                    }
                    argsOut.add(v);
                    return new Where.In(a, 1);
                }
                for (JdqlAst.ArgRef r : in.args()) argsOut.add(resolveArg(r, callArgs, nameToIdx));
                return new Where.In(a, in.args().size());
            }
            case JdqlAst.And and -> {
                List<Where> cs = new ArrayList<>(and.children().size());
                for (JdqlAst.Pred c : and.children()) cs.add(build(c, attrIndex, callArgs, nameToIdx, argsOut, alwaysFalse));
                return new Where.And(cs);
            }
            case JdqlAst.Or or -> {
                List<Where> cs = new ArrayList<>(or.children().size());
                for (JdqlAst.Pred c : or.children()) cs.add(build(c, attrIndex, callArgs, nameToIdx, argsOut, alwaysFalse));
                return new Where.Or(cs);
            }
            case JdqlAst.Not n -> {
                return new Where.Not(build(n.child(), attrIndex, callArgs, nameToIdx, argsOut, alwaysFalse));
            }
        }
    }

    private static Attribute<?, ?> lookup(Map<String, Attribute<?, ?>> idx, String name) {
        Attribute<?, ?> a = idx.get(name);
        if (a == null) throw new MansartDataException("Unknown attribute: " + name);
        return a;
    }

    private static Object resolveArg(JdqlAst.ArgRef ref, Object[] args, Map<String, Integer> nameToIdx) {
        if (ref.isLiteral) return resolveLiteral(ref.literal);
        if (ref.isNamed()) {
            Integer i = nameToIdx.get(ref.named);
            if (i == null) throw new MansartDataException("@Query references :" + ref.named
                    + " but the method has no parameter with that name (compile with -parameters or use ?N)");
            return args[i];
        }
        return args[ref.positional - 1];
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
     * Maps named parameters to method-arg indices. Resolution order:
     * <ol>
     *   <li>{@code @jakarta.data.repository.Param("name")} on the parameter (authoritative).</li>
     *   <li>{@code Parameter.getName()} when {@code -parameters} is honoured by javac.</li>
     * </ol>
     * Both are read so deployments compiled without {@code -parameters} (e.g. the official
     * Jakarta Data TCK jar built with maven-compiler-plugin 4.0.0-beta-4 — see BUG-20260505-01,
     * M7-9) still resolve {@code @Query} {@code :name} bindings via {@code @Param}.
     */
    private static jakarta.data.page.PageRequest findPageRequest(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof jakarta.data.page.PageRequest pr) return pr;
        }
        return null;
    }

    private static jakarta.data.Limit findLimit(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof jakarta.data.Limit l) return l;
        }
        return null;
    }

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
