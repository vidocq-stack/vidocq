package io.vidocq.mansart.data.core;

import io.vidocq.mansart.data.dialect.Attribute;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.OrderBy;
import io.vidocq.mansart.data.dialect.Where;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M7-1/M7-2 runtime fallback for {@code @Repository} interfaces that have no compile-time
 * {@code *Impl}. Uses {@code java.lang.reflect.Proxy}, with one pre-built dispatcher per method
 * (computed at proxy creation, cached for the proxy's lifetime). {@code java.lang.classfile}
 * bytecode generation lands in M7-3.
 *
 * <p>Supports:
 * <ul>
 *   <li>Inherited {@code BasicRepository}/{@code CrudRepository} methods (M7-1)</li>
 *   <li>Derived queries: {@code findByX}, {@code countByX}, {@code existsByX},
 *       {@code deleteByX}, with comparators / OrderBy / In(Collection) (M7-2)</li>
 * </ul>
 *
 * <p>NOT yet supported (UnsupportedOperationException):
 * <ul>
 *   <li>{@code @Query} JDQL (M7-3)</li>
 *   <li>Lifecycle annotations: {@code @Insert}/{@code @Update}/{@code @Delete}/{@code @Save} (M7-3)</li>
 *   <li>Default methods on the interface (M7-3)</li>
 *   <li>{@code PageRequest}-trailing derived queries (M7-3)</li>
 * </ul>
 */
public final class RuntimeRepositoryProxy {

    private RuntimeRepositoryProxy() {}

    public static <R> R create(Class<R> repoInterface, RepositoryRuntime runtime) {
        EntityAndKey ek = resolveEntityAndKey(repoInterface);
        if (ek == null) {
            throw new MansartDataException(repoInterface.getName()
                    + " does not extend BasicRepository<E, K> / CrudRepository<E, K> — runtime path needs the entity type.");
        }
        EntityModel<?> model = RuntimeEntityModelBuilder.build(ek.entity, ek.key);
        // M7-7 — runtime-discovered repos own their schema bootstrap. The compile-time path
        // leaves DDL to user code; this is a runtime-only convenience so deployments lacking
        // APT (e.g. the TCK jar) still get a working table.
        try { runtime.ensureTable(model); }
        catch (RuntimeException ignored) { /* table may already exist or be user-managed */ }
        return create(repoInterface, model, runtime);
    }

    @SuppressWarnings("unchecked")
    public static <R> R create(Class<R> repoInterface, EntityModel<?> model, RepositoryRuntime runtime) {
        Map<Method, Dispatcher> dispatchers = buildDispatchers(repoInterface, model);
        InvocationHandler handler = (proxy, method, args) -> {
            // M7-8 — interface default methods: route through the standard JDK shim so
            // user-supplied helpers compose with the runtime-generated dispatchers.
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            Dispatcher d = dispatchers.get(method);
            if (d == null) {
                throw unsupported(method, "no Mansart dispatcher built for this method");
            }
            return d.invoke(runtime, model, args);
        };
        return (R) Proxy.newProxyInstance(repoInterface.getClassLoader(),
                new Class<?>[]{ repoInterface }, handler);
    }

    private record EntityAndKey(Class<?> entity, Class<?> key) {}

    private static EntityAndKey resolveEntityAndKey(Class<?> repoInterface) {
        for (Type t : repoInterface.getGenericInterfaces()) {
            EntityAndKey ek = fromGenericInterface(t);
            if (ek != null) return ek;
        }
        for (Class<?> parent : repoInterface.getInterfaces()) {
            EntityAndKey ek = resolveEntityAndKey(parent);
            if (ek != null) return ek;
        }
        return inferFromMethods(repoInterface);
    }

    /**
     * M7-6 — when the repo extends no parameterised {@code BasicRepository<E,K>}, infer the
     * entity from method signatures: the parameter of an {@code @Insert}/{@code @Update}/
     * {@code @Delete}/{@code @Save} method (singular or {@code List<E>}/{@code E[]}/varargs),
     * or the return type of a {@code findBy*} / {@code @Find} method.
     */
    private static EntityAndKey inferFromMethods(Class<?> repoInterface) {
        for (Method m : allInterfaceMethods(repoInterface)) {
            if (m.getDeclaringClass() == Object.class) continue;
            for (Type pt : m.getGenericParameterTypes()) {
                Class<?> e = entityFromCarrier(pt);
                if (e != null) return new EntityAndKey(e, null);
            }
            Class<?> r = entityFromCarrier(m.getGenericReturnType());
            if (r != null) return new EntityAndKey(r, null);
        }
        return null;
    }

    private static Class<?> entityFromCarrier(Type t) {
        if (t instanceof Class<?> c) {
            if (c.isArray()) return entityCandidate(c.getComponentType());
            return entityCandidate(c);
        }
        if (t instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length == 1 && args[0] instanceof Class<?> arg) {
                return entityCandidate(arg);
            }
        }
        return null;
    }

    private static Class<?> entityCandidate(Class<?> c) {
        if (c == null || c.isPrimitive() || c.isInterface() || c.isEnum() || c.isAnnotation()) return null;
        if (c == Object.class || c == String.class) return null;
        if (Number.class.isAssignableFrom(c) || Boolean.class == c || Character.class == c) return null;
        if (c.getName().startsWith("java.")) return null;
        if (c.getName().startsWith("jakarta.data.")) return null;
        return c;
    }

    private static EntityAndKey fromGenericInterface(Type t) {
        if (!(t instanceof ParameterizedType pt)) return null;
        Type raw = pt.getRawType();
        if (!(raw instanceof Class<?> rawClass)) return null;
        String fqn = rawClass.getName();
        if (fqn.equals("jakarta.data.repository.BasicRepository")
                || fqn.equals("jakarta.data.repository.CrudRepository")
                || fqn.equals("jakarta.data.repository.DataRepository")) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length >= 2 && args[0] instanceof Class<?> entity) {
                Class<?> key = (args[1] instanceof Class<?> k) ? k : null;
                return new EntityAndKey(entity, key);
            }
        }
        return null;
    }

    /* ---- dispatchers built once at proxy creation ---- */

    @FunctionalInterface
    private interface Dispatcher {
        Object invoke(RepositoryRuntime runtime, EntityModel<?> model, Object[] args);
    }

    private static Map<Method, Dispatcher> buildDispatchers(Class<?> repoInterface, EntityModel<?> model) {
        // HashMap (not IdentityHashMap): java.lang.reflect.Proxy may invoke with a different
        // Method instance than the one we cached from getDeclaredMethods.
        Map<Method, Dispatcher> out = new HashMap<>();
        java.util.Set<String> attributeNames = new java.util.LinkedHashSet<>();
        for (Attribute<?, ?> a : model.attributes()) attributeNames.add(a.name());

        for (Method m : allInterfaceMethods(repoInterface)) {
            if (m.isDefault() || m.getDeclaringClass() == Object.class) continue;
            Dispatcher d = jdqlDispatcher(m, model, attributeNames);
            if (d == null) d = lifecycleDispatcher(m, model);
            if (d == null) d = inheritedDispatcher(m);
            if (d == null) d = derivedDispatcher(m, model, attributeNames);
            if (d == null) {
                d = (rt, em, args) -> { throw unsupported(m,
                        "compile-time Impl missing AND not handled by runtime fallback (no @Query, no lifecycle, "
                      + "no inherited match, no parseable derived name)."); };
            }
            out.put(m, d);
        }
        return out;
    }

    private static List<Method> allInterfaceMethods(Class<?> itf) {
        List<Method> out = new ArrayList<>();
        collectMethods(itf, out);
        return out;
    }

    private static void collectMethods(Class<?> itf, List<Method> out) {
        for (Method m : itf.getDeclaredMethods()) out.add(m);
        for (Class<?> parent : itf.getInterfaces()) collectMethods(parent, out);
    }

    /* ---- @Query dispatcher (M7-3) ---- */

    private static Dispatcher jdqlDispatcher(Method m, EntityModel<?> model,
                                             java.util.Set<String> attributeNames) {
        String jdql = readQueryAnnotationValue(m);
        if (jdql == null) return null;
        String entitySimple = model.entityClass().getSimpleName();
        JdqlAst.Stmt stmt;
        try {
            stmt = JdqlAst.parse(jdql, attributeNames, entitySimple);
        } catch (JdqlAst.ParseException e) {
            throw new MansartDataException("@Query parse error on " + m.getName() + ": "
                    + e.getMessage() + " — " + jdql, e);
        }
        Map<String, Attribute<?, ?>> attrIdx = new java.util.HashMap<>();
        for (Attribute<?, ?> a : model.attributes()) attrIdx.put(a.name(), a);
        Map<String, Integer> nameToIdx = JdqlExecutor.nameToIndexFor(m);
        return (rt, em, args) -> JdqlExecutor.execute(stmt, m, em, attrIdx, rt,
                args == null ? new Object[0] : args, nameToIdx);
    }

    private static String readQueryAnnotationValue(Method m) {
        for (java.lang.annotation.Annotation a : m.getDeclaredAnnotations()) {
            if (a.annotationType().getName().equals("jakarta.data.repository.Query")) {
                try {
                    return (String) a.annotationType().getMethod("value").invoke(a);
                } catch (ReflectiveOperationException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /* ---- Lifecycle dispatchers (@Insert/@Update/@Delete/@Save) — M7-3 ---- */

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Dispatcher lifecycleDispatcher(Method m, EntityModel<?> model) {
        if (m.getParameterCount() != 1) return null;
        // Only fire for methods declared on the user's repository — Jakarta Data's BasicRepository
        // .save / .delete carry @Save / @Delete in the spec but with upsert / silent-delete
        // semantics, handled by inheritedDispatcher.
        String declFqn = m.getDeclaringClass().getName();
        if (declFqn.startsWith("jakarta.data.")) return null;
        boolean isVoid = m.getReturnType() == void.class;
        if (hasJakartaAnnotation(m, "jakarta.data.repository.Insert")) {
            return (rt, em, args) -> {
                Object out = rt.insertStrict((EntityModel) em, args[0]);
                return isVoid ? null : out;
            };
        }
        if (hasJakartaAnnotation(m, "jakarta.data.repository.Update")) {
            return (rt, em, args) -> {
                Object out = rt.updateStrict((EntityModel) em, args[0]);
                return isVoid ? null : out;
            };
        }
        if (hasJakartaAnnotation(m, "jakarta.data.repository.Save")) {
            return (rt, em, args) -> {
                Object out = rt.save((EntityModel) em, args[0]);
                return isVoid ? null : out;
            };
        }
        if (hasJakartaAnnotation(m, "jakarta.data.repository.Delete")) {
            return (rt, em, args) -> { rt.deleteStrict((EntityModel) em, args[0]); return null; };
        }
        return null;
    }

    private static boolean hasJakartaAnnotation(Method m, String fqn) {
        for (java.lang.annotation.Annotation a : m.getDeclaredAnnotations()) {
            if (a.annotationType().getName().equals(fqn)) return true;
        }
        return false;
    }

    /* ---- Inherited BasicRepository/CrudRepository methods ---- */

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Dispatcher inheritedDispatcher(Method m) {
        return switch (m.getName()) {
            case "save"        -> (rt, em, args) -> rt.save((EntityModel) em, args[0]);
            case "saveAll"     -> (rt, em, args) -> {
                List<Object> out = new ArrayList<>();
                for (Object e : (Iterable<?>) args[0]) out.add(rt.save((EntityModel) em, e));
                return out;
            };
            case "findById"    -> (rt, em, args) -> rt.findById((EntityModel) em, args[0]);
            case "findAll" -> (rt, em, args) -> {
                if (args == null || args.length == 0) {
                    return rt.findAll((EntityModel) em).stream();
                }
                if (args.length == 2 && args[0] instanceof jakarta.data.page.PageRequest pr) {
                    return rt.queryPage((EntityModel) em,
                            Where.ALWAYS_TRUE,
                            rt.toOrderBy((EntityModel) em, (jakarta.data.Order) args[1]),
                            pr);
                }
                throw unsupported(m, "unsupported findAll arity");
            };
            case "deleteById"  -> (rt, em, args) -> { rt.deleteById((EntityModel) em, args[0]); return null; };
            case "delete"      -> (rt, em, args) -> { rt.delete((EntityModel) em, args[0]); return null; };
            case "deleteAll" -> (rt, em, args) -> {
                if (args == null || args.length == 0) {
                    for (Object e : rt.findAll((EntityModel) em)) rt.delete((EntityModel) em, e);
                } else {
                    for (Object e : (Iterable<?>) args[0]) rt.delete((EntityModel) em, e);
                }
                return null;
            };
            case "count"       -> (rt, em, args) -> rt.count((EntityModel) em);
            case "existsById"  -> (rt, em, args) -> rt.existsById((EntityModel) em, args[0]);
            default            -> null;
        };
    }

    /* ---- Derived queries (findByX, countByX, deleteByX, existsByX, …) ---- */

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Dispatcher derivedDispatcher(Method m, EntityModel<?> model,
                                                java.util.Set<String> attributeNames) {
        QueryMethodParser.QueryDescriptor desc = QueryMethodParser.parse(m.getName(), attributeNames);
        if (desc == null) return null;

        // Map predicate-attribute names to Attribute<?, ?> instances at proxy-build time.
        Map<String, Attribute<?, ?>> attrIndex = new java.util.HashMap<>();
        for (Attribute<?, ?> a : model.attributes()) attrIndex.put(a.name(), a);

        OrderBy orderBy = buildOrderBy(desc.orderBy(), attrIndex);
        boolean returnsStream = m.getReturnType() == java.util.stream.Stream.class;
        boolean returnsOptional = m.getReturnType() == java.util.Optional.class;
        boolean returnsList = java.util.List.class.isAssignableFrom(m.getReturnType())
                || java.util.Collection.class == m.getReturnType()
                || Iterable.class == m.getReturnType();
        boolean isVoid = m.getReturnType() == void.class;
        boolean returnsLong = m.getReturnType() == long.class || m.getReturnType() == Long.class;
        boolean returnsInt  = m.getReturnType() == int.class || m.getReturnType() == Integer.class;

        return (rt, em, callArgs) -> {
            // Build Where + args at call-time so In(Collection) takes the actual list size.
            BuiltWhere built = buildWhere(desc, attrIndex, callArgs);
            Where w = built.where;
            Object[] xs = built.args;

            return switch (desc.op()) {
                case FIND -> {
                    if (w == Where.ALWAYS_FALSE) {
                        yield emptyForReturn(returnsStream, returnsOptional, returnsList);
                    }
                    if (returnsOptional) yield rt.queryOne((EntityModel) em, w, xs);
                    List<Object> list = rt.queryList((EntityModel) em, w, orderBy, xs);
                    if (returnsStream) yield list.stream();
                    if (returnsList)   yield list;
                    java.util.Optional<?> oneOpt = rt.queryOne((EntityModel) em, w, xs);
                    if (oneOpt.isEmpty()) throw new MansartDataException("No result");
                    yield oneOpt.get();
                }
                case FIND_ONE -> rt.queryOne((EntityModel) em, w, xs);
                case COUNT    -> rt.countWhere((EntityModel) em, w, xs);
                case EXISTS   -> rt.existsWhere((EntityModel) em, w, xs);
                case DELETE -> {
                    long n = rt.deleteWhere((EntityModel) em, w, xs);
                    if (isVoid)     yield null;
                    if (returnsInt) yield (int) n;
                    if (returnsLong) yield n;
                    yield n;
                }
            };
        };
    }

    private static OrderBy buildOrderBy(List<QueryMethodParser.Order> orders,
                                        Map<String, Attribute<?, ?>> attrIndex) {
        if (orders == null || orders.isEmpty()) return OrderBy.NONE;
        List<OrderBy.Order> out = new ArrayList<>(orders.size());
        for (QueryMethodParser.Order o : orders) {
            Attribute<?, ?> a = attrIndex.get(o.attribute());
            if (a == null) throw new MansartDataException("Unknown attribute in OrderBy: " + o.attribute());
            out.add(o.asc() ? OrderBy.Order.asc(a) : OrderBy.Order.desc(a));
        }
        return new OrderBy(out);
    }

    private record BuiltWhere(Where where, Object[] args) {}

    @SuppressWarnings("unchecked")
    private static BuiltWhere buildWhere(QueryMethodParser.QueryDescriptor desc,
                                         Map<String, Attribute<?, ?>> attrIndex, Object[] callArgs) {
        List<Where> parts = new ArrayList<>(desc.predicates().size());
        List<Object> args = new ArrayList<>();
        int paramIdx = 0;
        for (QueryMethodParser.Predicate p : desc.predicates()) {
            Attribute<?, ?> a = attrIndex.get(p.attribute());
            if (a == null) throw new MansartDataException("Unknown attribute: " + p.attribute());
            switch (p.comparator()) {
                case EQ        -> { parts.add(new Where.Eq(a));    args.add(callArgs[paramIdx++]); }
                case NOT_EQ    -> { parts.add(new Where.NotEq(a)); args.add(callArgs[paramIdx++]); }
                case LT        -> { parts.add(new Where.Lt(a));    args.add(callArgs[paramIdx++]); }
                case LTE       -> { parts.add(new Where.Lte(a));   args.add(callArgs[paramIdx++]); }
                case GT        -> { parts.add(new Where.Gt(a));    args.add(callArgs[paramIdx++]); }
                case GTE       -> { parts.add(new Where.Gte(a));   args.add(callArgs[paramIdx++]); }
                case LIKE      -> { parts.add(new Where.Like(a));  args.add(callArgs[paramIdx++]); }
                case BETWEEN   -> {
                    parts.add(new Where.Between(a));
                    args.add(callArgs[paramIdx++]);
                    args.add(callArgs[paramIdx++]);
                }
                case IS_NULL    -> parts.add(new Where.IsNull(a));
                case IS_NOT_NULL -> parts.add(new Where.IsNotNull(a));
                case CONTAINS    -> {
                    parts.add(new Where.Like(a));
                    args.add("%" + callArgs[paramIdx++] + "%");
                }
                case STARTS_WITH -> {
                    parts.add(new Where.Like(a));
                    args.add(callArgs[paramIdx++] + "%");
                }
                case ENDS_WITH   -> {
                    parts.add(new Where.Like(a));
                    args.add("%" + callArgs[paramIdx++]);
                }
                case TRUE  -> { parts.add(new Where.Eq(a)); args.add(Boolean.TRUE); }
                case FALSE -> { parts.add(new Where.Eq(a)); args.add(Boolean.FALSE); }
                case EMPTY     -> parts.add(new Where.IsNull(a));
                case NOT_EMPTY -> parts.add(new Where.IsNotNull(a));
                case IN -> {
                    Object v = callArgs[paramIdx++];
                    if (v instanceof Collection<?> col) {
                        if (col.isEmpty()) {
                            // Whole derived query reduces to FALSE — caller short-circuits.
                            return new BuiltWhere(Where.ALWAYS_FALSE, new Object[0]);
                        }
                        parts.add(new Where.In(a, col.size()));
                        for (Object e : col) args.add(e);
                    } else {
                        parts.add(new Where.In(a, 1));
                        args.add(v);
                    }
                }
            }
            if (p.negated()) {
                Where last = parts.remove(parts.size() - 1);
                parts.add(new Where.Not(last));
            }
        }
        Where w;
        if (parts.isEmpty()) w = Where.ALWAYS_TRUE;
        else if (parts.size() == 1) w = parts.get(0);
        else w = (desc.combinator() == QueryMethodParser.Combinator.OR)
                ? new Where.Or(parts) : new Where.And(parts);
        return new BuiltWhere(w, args.toArray());
    }

    private static Object emptyForReturn(boolean stream, boolean optional, boolean list) {
        if (optional) return java.util.Optional.empty();
        if (stream)   return java.util.stream.Stream.empty();
        if (list)     return java.util.List.of();
        return null;
    }

    private static UnsupportedOperationException unsupported(Method m, String reason) {
        return new UnsupportedOperationException(
                "Mansart runtime proxy: '" + m.getName() + "' — " + reason);
    }
}
