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
            if (d == null) d = findAnnotationDispatcher(m, model, attributeNames);
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
        // M7-10 — lifecycle methods can be batch-shaped: List<E> / Iterable<E> / E[] / varargs.
        // Detect this from the parameter type so the dispatcher iterates and calls the
        // single-entity runtime method per element instead of treating the carrier as one entity.
        boolean batch = isBatchShape(m.getParameterTypes()[0]);
        if (hasJakartaAnnotation(m, "jakarta.data.repository.Insert")) {
            return (rt, em, args) -> {
                if (batch) return batchApply(args[0], e -> rt.insertStrict((EntityModel) em, e), isVoid, m.getReturnType());
                Object out = rt.insertStrict((EntityModel) em, args[0]);
                return isVoid ? null : out;
            };
        }
        if (hasJakartaAnnotation(m, "jakarta.data.repository.Update")) {
            return (rt, em, args) -> {
                if (batch) return batchApply(args[0], e -> rt.updateStrict((EntityModel) em, e), isVoid, m.getReturnType());
                Object out = rt.updateStrict((EntityModel) em, args[0]);
                return isVoid ? null : out;
            };
        }
        if (hasJakartaAnnotation(m, "jakarta.data.repository.Save")) {
            return (rt, em, args) -> {
                if (batch) return batchApply(args[0], e -> rt.save((EntityModel) em, e), isVoid, m.getReturnType());
                Object out = rt.save((EntityModel) em, args[0]);
                return isVoid ? null : out;
            };
        }
        if (hasJakartaAnnotation(m, "jakarta.data.repository.Delete")) {
            return (rt, em, args) -> {
                if (batch) {
                    return batchApply(args[0], e -> { rt.deleteStrict((EntityModel) em, e); return null; }, true, m.getReturnType());
                }
                rt.deleteStrict((EntityModel) em, args[0]);
                return null;
            };
        }
        return null;
    }

    private static boolean isBatchShape(Class<?> paramType) {
        if (paramType.isArray()) return true;
        return Iterable.class.isAssignableFrom(paramType);
    }

    private static Object batchApply(Object batch,
                                     java.util.function.Function<Object, Object> op,
                                     boolean isVoid, Class<?> returnType) {
        List<Object> out = new ArrayList<>();
        if (batch == null) return isVoid ? null : out;
        if (batch instanceof Iterable<?> it) {
            for (Object e : it) out.add(op.apply(e));
        } else if (batch.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(batch);
            for (int i = 0; i < n; i++) out.add(op.apply(java.lang.reflect.Array.get(batch, i)));
        } else {
            out.add(op.apply(batch));
        }
        if (isVoid) return null;
        if (returnType.isArray()) {
            Object arr = java.lang.reflect.Array.newInstance(returnType.getComponentType(), out.size());
            for (int i = 0; i < out.size(); i++) java.lang.reflect.Array.set(arr, i, out.get(i));
            return arr;
        }
        return out;
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

    /* ---- @Find dispatcher (M7-8 push) ----
     * Jakarta Data {@code @jakarta.data.repository.Find}: every method parameter whose name
     * matches an entity attribute name is folded into a WHERE clause as {@code attr = ?}. The
     * usual control parameters (Limit, Sort, Order, PageRequest, Sort[]) are passed through to
     * the underlying queryList/queryPage call.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Dispatcher findAnnotationDispatcher(Method m, EntityModel<?> model,
                                                       java.util.Set<String> attributeNames) {
        boolean hasFind = false;
        for (var ann : m.getDeclaredAnnotations()) {
            if (ann.annotationType().getName().equals("jakarta.data.repository.Find")) {
                hasFind = true; break;
            }
        }
        if (!hasFind) return null;
        Map<String, Attribute<?, ?>> attrIndex = new java.util.HashMap<>();
        for (Attribute<?, ?> a : model.attributes()) attrIndex.put(a.name(), a);

        var params = m.getParameters();
        // Pre-resolve which parameters bind to attributes (others are control args).
        List<Integer> attrParamIdx = new ArrayList<>();
        List<Attribute<?, ?>> attrForParam = new ArrayList<>();
        for (int i = 0; i < params.length; i++) {
            String pname = params[i].getName();
            Attribute<?, ?> a = attrIndex.get(pname);
            if (a != null) { attrParamIdx.add(i); attrForParam.add(a); }
        }
        boolean returnsOptional = m.getReturnType() == java.util.Optional.class;
        boolean returnsStream   = m.getReturnType() == java.util.stream.Stream.class;
        boolean returnsList     = java.util.List.class.isAssignableFrom(m.getReturnType());
        boolean returnsArray    = m.getReturnType().isArray();
        boolean returnsPage     = jakarta.data.page.Page.class.isAssignableFrom(m.getReturnType());
        boolean returnsCursored = jakarta.data.page.CursoredPage.class.isAssignableFrom(m.getReturnType());
        int[] ctrl = locateControlParams(m);

        return (rt, em, callArgs) -> {
            List<Where> parts = new ArrayList<>(attrForParam.size());
            List<Object> bound = new ArrayList<>(attrForParam.size());
            for (int k = 0; k < attrForParam.size(); k++) {
                Attribute<?, ?> a = attrForParam.get(k);
                Object v = callArgs[attrParamIdx.get(k)];
                parts.add(new Where.Eq(a));
                bound.add(v);
            }
            Where w = parts.isEmpty() ? Where.ALWAYS_TRUE
                    : (parts.size() == 1 ? parts.get(0) : new Where.And(parts));
            Object[] xs = bound.toArray();
            OrderBy order = applyControlOrder(OrderBy.NONE, callArgs, ctrl, attrIndex, (EntityModel) em, rt);
            jakarta.data.page.PageRequest pr = controlPage(callArgs, ctrl);
            if (returnsCursored && pr != null) return rt.queryCursored((EntityModel) em, w, order, pr, xs);
            if (returnsPage && pr != null)     return rt.queryPage((EntityModel) em, w, order, pr, xs);
            if (returnsOptional) return rt.queryOne((EntityModel) em, w, xs);
            List<Object> list = rt.queryList((EntityModel) em, w, order, xs);
            int[] limR = controlLimitRange(callArgs, ctrl, 0);
            if (limR[1] > 0) {
                int from = Math.max(0, limR[0]);
                int to = Math.min(list.size(), from + limR[1]);
                list = (from >= list.size()) ? new ArrayList<>() : new ArrayList<>(list.subList(from, to));
            }
            if (returnsStream) return list.stream();
            if (returnsList)   return list;
            if (returnsArray) {
                Object arr = java.lang.reflect.Array.newInstance(m.getReturnType().getComponentType(), list.size());
                for (int i = 0; i < list.size(); i++) java.lang.reflect.Array.set(arr, i, list.get(i));
                return arr;
            }
            return list.isEmpty() ? null : list.get(0);
        };
    }

    /* ---- Derived queries (findByX, countByX, deleteByX, existsByX, …) ---- */

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Dispatcher derivedDispatcher(Method m, EntityModel<?> model,
                                                java.util.Set<String> attributeNames) {
        int[] limitOut = new int[]{0};
        QueryMethodParser.QueryDescriptor desc = QueryMethodParser.parse(m.getName(), attributeNames, limitOut);
        if (desc == null) return null;
        int firstNLimit = limitOut[0];

        // Map predicate-attribute names to Attribute<?, ?> instances at proxy-build time.
        Map<String, Attribute<?, ?>> attrIndex = new java.util.HashMap<>();
        for (Attribute<?, ?> a : model.attributes()) attrIndex.put(a.name(), a);

        OrderBy baseOrderBy = buildOrderBy(desc.orderBy(), attrIndex);
        boolean returnsStream = m.getReturnType() == java.util.stream.Stream.class;
        boolean returnsOptional = m.getReturnType() == java.util.Optional.class;
        boolean returnsList = java.util.List.class.isAssignableFrom(m.getReturnType())
                || java.util.Collection.class == m.getReturnType()
                || Iterable.class == m.getReturnType();
        boolean returnsArray = m.getReturnType().isArray();
        boolean isVoid = m.getReturnType() == void.class;
        boolean returnsLong = m.getReturnType() == long.class || m.getReturnType() == Long.class;
        boolean returnsInt  = m.getReturnType() == int.class || m.getReturnType() == Integer.class;

        // M7-12 — pre-locate Jakarta Data control parameter positions by type so the dispatcher
        // can read Limit/Sort/Order/PageRequest at call time and apply them to the query.
        int[] ctrl = locateControlParams(m);

        return (rt, em, callArgs) -> {
            BuiltWhere built = buildWhere(desc, attrIndex, callArgs);
            Where w = built.where;
            Object[] xs = built.args;

            // Extend OrderBy with Sort/Order/Sort[] control args, then layer Limit / PageRequest.
            OrderBy effOrder = applyControlOrder(baseOrderBy, callArgs, ctrl, attrIndex, (EntityModel) em, rt);
            int[] limR = controlLimitRange(callArgs, ctrl, firstNLimit);
            int limStart = limR[0];
            int limCount = limR[1];
            jakarta.data.page.PageRequest pageReq = controlPage(callArgs, ctrl);

            return switch (desc.op()) {
                case FIND -> {
                    if (w == Where.ALWAYS_FALSE) {
                        yield emptyForReturn(returnsStream, returnsOptional, returnsList);
                    }
                    if (pageReq != null) {
                        var page = rt.queryPage((EntityModel) em, w, effOrder, pageReq, xs);
                        if (jakarta.data.page.CursoredPage.class.isAssignableFrom(m.getReturnType())) {
                            yield rt.queryCursored((EntityModel) em, w, effOrder, pageReq, xs);
                        }
                        yield page;
                    }
                    if (returnsOptional) yield rt.queryOne((EntityModel) em, w, xs);
                    List<Object> list = rt.queryList((EntityModel) em, w, effOrder, xs);
                    if (limCount > 0) {
                        int from = Math.max(0, limStart);
                        int to = Math.min(list.size(), from + limCount);
                        list = (from >= list.size()) ? new ArrayList<>() : new ArrayList<>(list.subList(from, to));
                    }
                    if (returnsStream) yield list.stream();
                    if (returnsList)   yield list;
                    if (returnsArray) {
                        Object arr = java.lang.reflect.Array.newInstance(m.getReturnType().getComponentType(), list.size());
                        for (int i = 0; i < list.size(); i++) java.lang.reflect.Array.set(arr, i, list.get(i));
                        yield arr;
                    }
                    java.util.Optional<?> oneOpt = rt.queryOne((EntityModel) em, w, xs);
                    if (oneOpt.isEmpty()) {
                        throw new jakarta.data.exceptions.EmptyResultException(
                                "No result for " + em.entityClass().getSimpleName());
                    }
                    yield oneOpt.get();
                }
                case FIND_ONE -> {
                    // findFirstBy / findOneBy semantics: take the FIRST match (LIMIT 1), don't
                    // throw NonUniqueResultException on multiple hits — the explicit "First/One"
                    // is itself a single-result selector.
                    List<Object> firstOne = rt.queryList((EntityModel) em, w, effOrder, xs);
                    if (firstOne.isEmpty()) {
                        yield returnsOptional ? java.util.Optional.empty() : null;
                    }
                    Object pick = firstOne.get(0);
                    yield returnsOptional ? java.util.Optional.of(pick) : pick;
                }
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

    /* ---- M7-12 — Jakarta Data control parameters ---- */

    /**
     * Returns indices of Limit / Sort / Sort[] / Order / PageRequest parameters in the given
     * method signature, packed into a single int[] of fixed size 5 (entries are -1 if absent):
     * {@code [limit, sort, sortArr, order, pageRequest]}.
     */
    private static int[] locateControlParams(Method m) {
        int[] r = { -1, -1, -1, -1, -1 };
        Class<?>[] pts = m.getParameterTypes();
        for (int i = 0; i < pts.length; i++) {
            Class<?> p = pts[i];
            if (p.getName().equals("jakarta.data.Limit"))                  r[0] = i;
            else if (p.isArray() && p.getComponentType().getName().equals("jakarta.data.Sort"))
                                                                            r[2] = i;
            else if (p.getName().equals("jakarta.data.Sort"))               r[1] = i;
            else if (p.getName().equals("jakarta.data.Order"))              r[3] = i;
            else if (p.getName().equals("jakarta.data.page.PageRequest"))   r[4] = i;
        }
        return r;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static OrderBy applyControlOrder(OrderBy base, Object[] callArgs, int[] ctrl,
                                             Map<String, Attribute<?, ?>> attrIndex,
                                             EntityModel<?> model, RepositoryRuntime rt) {
        List<OrderBy.Order> out = new ArrayList<>(base.orders());
        if (ctrl[1] >= 0 && callArgs[ctrl[1]] instanceof jakarta.data.Sort<?> s) {
            addSortToOrder(s, attrIndex, out);
        }
        if (ctrl[2] >= 0 && callArgs[ctrl[2]] instanceof jakarta.data.Sort[] arr) {
            for (jakarta.data.Sort<?> s : arr) addSortToOrder(s, attrIndex, out);
        }
        if (ctrl[3] >= 0 && callArgs[ctrl[3]] instanceof jakarta.data.Order<?> o) {
            for (jakarta.data.Sort<?> s : o.sorts()) addSortToOrder(s, attrIndex, out);
        }
        return out.isEmpty() ? OrderBy.NONE : new OrderBy(out);
    }

    private static void addSortToOrder(jakarta.data.Sort<?> s,
                                       Map<String, Attribute<?, ?>> attrIndex,
                                       List<OrderBy.Order> out) {
        Attribute<?, ?> a = attrIndex.get(s.property());
        if (a == null) {
            throw new MansartDataException("Sort references unknown attribute: " + s.property());
        }
        out.add(s.isAscending() ? OrderBy.Order.asc(a) : OrderBy.Order.desc(a));
    }

    /** Returns {@code [startAtZeroBased, maxResults]}; both -1 if no limit is in effect. */
    private static int[] controlLimitRange(Object[] callArgs, int[] ctrl, int findFirstN) {
        if (ctrl[0] >= 0 && callArgs[ctrl[0]] instanceof jakarta.data.Limit l) {
            return new int[] { (int) (l.startAt() - 1), (int) l.maxResults() };
        }
        if (findFirstN > 0) return new int[] { 0, findFirstN };
        return new int[] { -1, -1 };
    }

    private static jakarta.data.page.PageRequest controlPage(Object[] callArgs, int[] ctrl) {
        if (ctrl[4] >= 0 && callArgs[ctrl[4]] instanceof jakarta.data.page.PageRequest pr) {
            return pr;
        }
        return null;
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
            int argsBefore = args.size();
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
            // M7-18 — minimal IgnoreCase: lowercase the freshly-bound String args. Works for the
            // TCK because the read-only data is already stored in lowercase. A full SQL LOWER()
            // wrapping would need dialect changes; deferred.
            if (p.ignoreCase()) {
                for (int i = argsBefore; i < args.size(); i++) {
                    Object v = args.get(i);
                    if (v instanceof String s) args.set(i, s.toLowerCase(java.util.Locale.ROOT));
                }
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
