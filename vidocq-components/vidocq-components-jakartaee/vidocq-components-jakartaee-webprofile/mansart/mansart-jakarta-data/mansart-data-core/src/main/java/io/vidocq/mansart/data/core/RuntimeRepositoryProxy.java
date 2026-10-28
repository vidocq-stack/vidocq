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

    private static final String JAKARTA_DATA = "jakarta.data.";

    private RuntimeRepositoryProxy() {}

    public static <R> R create(Class<R> repoInterface, RepositoryRuntime runtime) {
        EntityAndKey ek = resolveEntityAndKey(repoInterface);
        if (ek == null) {
            throw new MansartDataException(repoInterface.getName()
                    + " does not extend BasicRepository<E, K> / CrudRepository<E, K> — runtime path needs the entity type.");
        }
        EntityModel<?> primary = RuntimeEntityModelBuilder.build(ek.entity, ek.key);
        // M7-7 — auto-create schema for the primary entity so deployments lacking APT (TCK jar)
        // still get a working table. M7-23 also bootstraps tables for any secondary entity that
        // method signatures reveal (e.g. MultipleEntityRepo touches both Box and Coordinate).
        try { runtime.ensureTable(primary); }
        catch (RuntimeException _) { /* table may already exist or be user-managed */ }
        Map<Class<?>, EntityModel<?>> models = buildSecondaryModels(repoInterface, primary, runtime);
        return create(repoInterface, primary, models, runtime);
    }

    public static <R> R create(Class<R> repoInterface, EntityModel<?> model, RepositoryRuntime runtime) {
        return create(repoInterface, model, java.util.Map.of(model.entityClass(), model), runtime);
    }

    @SuppressWarnings("unchecked")
    private static <R> R create(Class<R> repoInterface, EntityModel<?> primary,
                                Map<Class<?>, EntityModel<?>> models, RepositoryRuntime runtime) {
        Map<Method, EntityModel<?>> methodModel = mapMethodToModel(repoInterface, primary, models);
        Map<Method, Dispatcher> dispatchers = buildDispatchersMulti(repoInterface, methodModel, primary);

        // M7-25 — try a generated hidden class first (Class-File API). Falls back to
        // java.lang.reflect.Proxy if generation throws — keeps the runtime path resilient
        // while we ship and validate the bytecode emitter.
        try {
            return (R) createGenerated(repoInterface, methodModel, dispatchers, primary, runtime);
        } catch (Throwable _) {
            // Swallow and fall through to the Proxy path. The generator is the canonical
            // runtime impl; java.lang.reflect.Proxy stays as a safety net for shapes not yet
            // supported by RuntimeRepositoryClassGenerator.
        }

        InvocationHandler handler = (proxy, method, args) -> {
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            Dispatcher d = dispatchers.get(method);
            if (d == null) {
                throw unsupported(method, "no Mansart dispatcher built for this method");
            }
            EntityModel<?> em = methodModel.getOrDefault(method, primary);
            return d.invoke(runtime, em, args);
        };
        return (R) Proxy.newProxyInstance(repoInterface.getClassLoader(),
                new Class<?>[]{ repoInterface }, handler);
    }

    private static Object createGenerated(Class<?> repoInterface,
                                          Map<Method, EntityModel<?>> methodModel,
                                          Map<Method, Dispatcher> dispatchers,
                                          EntityModel<?> primary,
                                          RepositoryRuntime runtime) throws ReflectiveOperationException {
        var gen = RuntimeRepositoryClassGenerator.generate(repoInterface);
        List<Method> ordered = gen.methodsByIndex();
        MansartCallback.Dispatcher[] dispArr = new MansartCallback.Dispatcher[ordered.size()];
        EntityModel<?>[] modelArr = new EntityModel<?>[ordered.size()];
        for (int i = 0; i < ordered.size(); i++) {
            Method m = ordered.get(i);
            Dispatcher inner = dispatchers.get(m);
            if (inner == null) {
                throw new MansartDataException("Generator referenced method without dispatcher: " + m);
            }
            // Adapt the local Dispatcher functional interface to MansartCallback.Dispatcher
            // (same signature; just two distinct type names so they don't collide with the
            // existing compile-time *Impl path).
            dispArr[i] = (rt, em, args) -> inner.invoke(rt, em, args);
            modelArr[i] = methodModel.getOrDefault(m, primary);
        }
        MansartCallback callback = new MansartCallback(dispArr, modelArr, runtime);
        return gen.implClass().getConstructor(MansartCallback.class).newInstance(callback);
    }

    /**
     * M7-23 — scans every method of the repo for entity types other than the primary one
     * (lifecycle param types, {@code @Find} return types, {@code @Query "FROM <Entity>"} or
     * {@code "UPDATE <Entity>"} clauses) and builds a separate {@link EntityModel} for each.
     * Each model is also schema-bootstrapped via {@code ensureTable}.
     */
    private static Map<Class<?>, EntityModel<?>> buildSecondaryModels(
            Class<?> repoInterface, EntityModel<?> primary, RepositoryRuntime runtime) {
        Map<Class<?>, EntityModel<?>> out = new java.util.HashMap<>();
        out.put(primary.entityClass(), primary);
        for (Method m : allInterfaceMethods(repoInterface)) {
            if (m.getDeclaringClass() == Object.class || m.getDeclaringClass().getName().startsWith(JAKARTA_DATA)) {
                continue;
            }
            for (Type pt : m.getGenericParameterTypes()) {
                Class<?> e = entityFromCarrier(pt);
                if (e != null && !out.containsKey(e)) addModel(out, e, runtime);
            }
            Class<?> r = entityFromCarrier(m.getGenericReturnType());
            if (r != null && !out.containsKey(r)) addModel(out, r, runtime);
        }
        return out;
    }

    private static void addModel(Map<Class<?>, EntityModel<?>> out, Class<?> entityClass,
                                 RepositoryRuntime runtime) {
        try {
            EntityModel<?> em = RuntimeEntityModelBuilder.build(entityClass);
            out.put(entityClass, em);
            try { runtime.ensureTable(em); }
            catch (RuntimeException _) { /* schema may already exist */ }
        } catch (RuntimeException _) {
            // Not a Mansart-mappable entity (e.g. value object) — skip silently.
        }
    }

    /**
     * Picks, for each repo method, the {@link EntityModel} it operates on. Resolution: the
     * single entity referenced in the method's parameter or return types — if there's only one,
     * use it; otherwise fall back to the primary model.
     */
    private static Map<Method, EntityModel<?>> mapMethodToModel(Class<?> repoInterface,
                                                                EntityModel<?> primary,
                                                                Map<Class<?>, EntityModel<?>> models) {
        Map<Method, EntityModel<?>> out = new HashMap<>();
        for (Method m : allInterfaceMethods(repoInterface)) {
            if (m.isDefault() || m.getDeclaringClass() == Object.class) continue;
            EntityModel<?> chosen = modelOfParameters(m, primary, models);
            if (chosen == primary) {
                Class<?> e = entityFromCarrier(m.getGenericReturnType());
                if (e != null && models.containsKey(e)) chosen = models.get(e);
            }
            // Last resort: extract the simple entity name from a @Query "FROM X", "UPDATE X",
            // or "DELETE FROM X" clause and match it against the registered models. Covers
            // resizeAll(int, int) where the entity is only implied by "UPDATE Box SET …".
            if (chosen == primary) chosen = modelNamedInQuery(m, primary, models);
            out.put(m, chosen);
        }
        return out;
    }

    /** The model of the first parameter that carries a registered entity; {@code primary} when none does. */
    private static EntityModel<?> modelOfParameters(Method m, EntityModel<?> primary,
                                                    Map<Class<?>, EntityModel<?>> models) {
        for (Type pt : m.getGenericParameterTypes()) {
            Class<?> e = entityFromCarrier(pt);
            if (e != null && models.containsKey(e)) return models.get(e);
        }
        return primary;
    }

    /** The model whose entity the method's {@code @Query} names; {@code primary} when it names none of them. */
    private static EntityModel<?> modelNamedInQuery(Method m, EntityModel<?> primary,
                                                    Map<Class<?>, EntityModel<?>> models) {
        String entityName = entityNameFromQueryAnnotation(m);
        if (entityName == null) return primary;
        for (var entry : models.entrySet()) {
            if (entry.getKey().getSimpleName().equals(entityName)) return entry.getValue();
        }
        return primary;
    }

    private static String entityNameFromQueryAnnotation(Method m) {
        String jdql = readQueryAnnotationValue(m);
        if (jdql == null) return null;
        String s = jdql.trim();
        // Tokenize gently: skip "SELECT …" prefix, then look for FROM / UPDATE / DELETE FROM.
        String[] words = s.split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String w = words[i].toUpperCase(java.util.Locale.ROOT);
            if ("FROM".equals(w) || "UPDATE".equals(w)) {
                if (i + 1 < words.length) {
                    String next = words[i + 1].replaceAll("[^a-zA-Z0-9_$]", "");
                    if (!next.isEmpty() && Character.isUpperCase(next.charAt(0))) return next;
                }
            }
        }
        return null;
    }

    /**
     * M7-23 build dispatchers using each method's chosen {@link EntityModel} (instead of a
     * single shared one). Without this, derived/JDQL/lifecycle dispatchers were resolving
     * attribute names against the WRONG entity for multi-entity repos.
     */
    private static Map<Method, Dispatcher> buildDispatchersMulti(Class<?> repoInterface,
                                                                 Map<Method, EntityModel<?>> methodModel,
                                                                 EntityModel<?> primary) {
        Map<Method, Dispatcher> out = new HashMap<>();
        for (Method m : allInterfaceMethods(repoInterface)) {
            if (m.isDefault() || m.getDeclaringClass() == Object.class) continue;
            out.put(m, dispatcherFor(m, methodModel.getOrDefault(m, primary)));
        }
        return out;
    }

    /** The first dispatcher that handles {@code m}: {@code @Query}, {@code @Find}, lifecycle, inherited, derived. */
    private static Dispatcher dispatcherFor(Method m, EntityModel<?> em) {
        java.util.Set<String> attributeNames = new java.util.LinkedHashSet<>();
        for (Attribute<?, ?> a : em.attributes()) attributeNames.add(a.name());
        Dispatcher d = jdqlDispatcher(m, em, attributeNames);
        if (d == null) d = findAnnotationDispatcher(m, em, attributeNames);
        if (d == null) d = lifecycleDispatcher(m);
        if (d == null) d = inheritedDispatcher(m);
        if (d == null) d = derivedDispatcher(m, em, attributeNames);
        if (d == null) {
            d = (_, _, _) -> { throw unsupported(m,
                    "compile-time Impl missing AND not handled by runtime fallback (no @Query, no lifecycle, "
                  + "no inherited match, no parseable derived name)."); };
        }
        return d;
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
        if (c.getName().startsWith(JAKARTA_DATA)) return null;
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
        JdqlExecutor.CallShape shape = JdqlExecutor.CallShape.of(m);
        return (rt, em, args) -> JdqlExecutor.execute(stmt, shape, em, attrIdx, rt,
                args == null ? new Object[0] : args);
    }

    private static String readQueryAnnotationValue(Method m) {
        jakarta.data.repository.Query query = m.getDeclaredAnnotation(jakarta.data.repository.Query.class);
        return query == null ? null : query.value();
    }

    /* ---- Lifecycle dispatchers (@Insert/@Update/@Delete/@Save) — M7-3 ---- */

    private static Dispatcher lifecycleDispatcher(Method m) {
        if (m.getParameterCount() != 1) return null;
        // Only fire for methods declared on the user's repository — Jakarta Data's BasicRepository
        // .save / .delete carry @Save / @Delete in the spec but with upsert / silent-delete
        // semantics, handled by inheritedDispatcher.
        String declFqn = m.getDeclaringClass().getName();
        if (declFqn.startsWith(JAKARTA_DATA)) return null;
        LifecycleOperation operation = lifecycleOperation(m);
        if (operation == null) return null;
        // A delete hands nothing back, whatever the method declares.
        boolean discard = m.getReturnType() == void.class || hasJakartaAnnotation(m, "jakarta.data.repository.Delete");
        Class<?> returnType = m.getReturnType();
        // M7-10 — lifecycle methods can be batch-shaped: List<E> / Iterable<E> / E[] / varargs.
        // Detect this from the parameter type so the dispatcher iterates and calls the
        // single-entity runtime method per element instead of treating the carrier as one entity.
        boolean batch = isBatchShape(m.getParameterTypes()[0]);
        return (rt, em, args) -> {
            if (batch) return batchApply(args[0], e -> operation.apply(rt, em, e), discard, returnType);
            Object out = operation.apply(rt, em, args[0]);
            return discard ? null : out;
        };
    }

    /** What a lifecycle method does to one entity. */
    @FunctionalInterface
    private interface LifecycleOperation {
        Object apply(RepositoryRuntime runtime, EntityModel<?> model, Object entity);
    }

    /** The operation of {@code @Insert}, {@code @Update}, {@code @Save} or {@code @Delete}; {@code null} for none. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static LifecycleOperation lifecycleOperation(Method m) {
        if (hasJakartaAnnotation(m, "jakarta.data.repository.Insert")) {
            return (rt, em, e) -> rt.insertStrict((EntityModel) em, e);
        }
        if (hasJakartaAnnotation(m, "jakarta.data.repository.Update")) {
            return (rt, em, e) -> rt.updateStrict((EntityModel) em, e);
        }
        if (hasJakartaAnnotation(m, "jakarta.data.repository.Save")) {
            return (rt, em, e) -> rt.save((EntityModel) em, e);
        }
        if (hasJakartaAnnotation(m, "jakarta.data.repository.Delete")) {
            return (rt, em, e) -> {
                rt.deleteStrict((EntityModel) em, e);
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
                                     java.util.function.UnaryOperator<Object> op,
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
            case "saveAll"     -> (rt, em, args) -> batchApply(args[0], e -> rt.save((EntityModel) em, e), false,
                    List.class);
            case "findById"    -> (rt, em, args) -> rt.findById((EntityModel) em, args[0]);
            case "findAll"     -> (rt, em, args) -> findAll(m, rt, em, args);
            case "deleteById"  -> (rt, em, args) -> { rt.deleteById((EntityModel) em, args[0]); return null; };
            case "delete"      -> (rt, em, args) -> { rt.delete((EntityModel) em, args[0]); return null; };
            case "deleteAll"   -> RuntimeRepositoryProxy::deleteAll;
            case "count"       -> (rt, em, args) -> rt.count((EntityModel) em);
            case "existsById"  -> (rt, em, args) -> rt.existsById((EntityModel) em, args[0]);
            default            -> crudDispatcher(m.getName());
        };
    }

    /** The methods {@code CrudRepository} adds to {@code BasicRepository} (MANSART-017). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Dispatcher crudDispatcher(String name) {
        return switch (name) {
            case "insert"    -> (rt, em, args) -> rt.insertStrict((EntityModel) em, args[0]);
            case "update"    -> (rt, em, args) -> rt.updateStrict((EntityModel) em, args[0]);
            case "insertAll" -> (rt, em, args) -> batchApply(args[0], e -> rt.insertStrict((EntityModel) em, e),
                    false, List.class);
            case "updateAll" -> (rt, em, args) -> batchApply(args[0], e -> rt.updateStrict((EntityModel) em, e),
                    false, List.class);
            default          -> null;
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object findAll(Method m, RepositoryRuntime rt, EntityModel em, Object[] args) {
        if (args == null || args.length == 0) {
            return rt.findAll(em).stream();
        }
        if (args.length == 2 && args[0] instanceof jakarta.data.page.PageRequest pr) {
            return rt.queryPage(em, Where.ALWAYS_TRUE, rt.toOrderBy(em, (jakarta.data.Order) args[1]), pr);
        }
        throw unsupported(m, "unsupported findAll arity");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object deleteAll(RepositoryRuntime rt, EntityModel em, Object[] args) {
        Iterable<?> entities = args == null || args.length == 0 ? rt.findAll(em) : (Iterable<?>) args[0];
        for (Object e : entities) rt.delete(em, e);
        return null;
    }

    /* ---- @Find dispatcher (M7-8 push) ----
     * Jakarta Data {@code @jakarta.data.repository.Find}: every method parameter whose name
     * matches an entity attribute name is folded into a WHERE clause as {@code attr = ?}. The
     * usual control parameters (Limit, Sort, Order, PageRequest, Sort[]) are passed through to
     * the underlying queryList/queryPage call.
     */
    private static Dispatcher findAnnotationDispatcher(Method m, EntityModel<?> model,
                                                       java.util.Set<String> attributeNames) {
        // BasicRepository / CrudRepository / DataRepository methods carry @Find / @Save / etc. in
        // the spec, but their semantics are owned by the inheritedDispatcher (built-in K-keyed
        // ops). Skip those here so we don't route findById through the user-method @Find path.
        if (m.getDeclaringClass().getPackageName().startsWith("jakarta.data")
                || !m.isAnnotationPresent(jakarta.data.repository.Find.class)) {
            return null;
        }
        Map<String, Attribute<?, ?>> attrIndex = new java.util.HashMap<>();
        for (Attribute<?, ?> a : model.attributes()) attrIndex.put(a.name(), a);
        FindBindings bindings = FindBindings.of(m, model, attrIndex);
        int[] ctrl = locateControlParams(m);
        return (rt, em, callArgs) -> executeFind(m, rt, em, callArgs, bindings, ctrl, attrIndex);
    }

    /**
     * Which {@code @Find} parameters bind to which attributes; the others are control
     * arguments. {@code paramIndexes.get(k)} is the argument compared to {@code attributes.get(k)}.
     * {@code sorts} are the method's {@code @OrderBy}, applied before any dynamic sort (MANSART-018).
     */
    private record FindBindings(List<Integer> paramIndexes, List<Attribute<?, ?>> attributes,
                                List<jakarta.data.Sort<?>> sorts) {

        /**
         * Resolution order per Jakarta Data: {@code @By("attr")} or {@code @Param("attr")} &gt; parameter
         * name match &gt; positional (single attribute of matching type) for the remaining non-control
         * parameters.
         */
        static FindBindings of(Method m, EntityModel<?> model, Map<String, Attribute<?, ?>> attrIndex) {
            var params = m.getParameters();
            Class<?>[] paramTypes = m.getParameterTypes();
            List<Integer> paramIndexes = new ArrayList<>();
            List<Attribute<?, ?>> attributes = new ArrayList<>();
            java.util.Set<Integer> consumed = new java.util.HashSet<>();
            for (int i = 0; i < params.length; i++) {
                if (isControlParamType(paramTypes[i])) {
                    consumed.add(i);
                    continue;
                }
                String paramHint = readParamAnnotation(params[i]);
                if (jakarta.data.repository.By.ID.equals(paramHint)) paramHint = model.id().name();
                Attribute<?, ?> a = attrIndex.get(paramHint == null ? params[i].getName() : paramHint);
                if (a != null) { paramIndexes.add(i); attributes.add(a); consumed.add(i); }
            }
            // Positional fallback: any remaining non-control param is matched against the entity
            // attribute whose Java type is assignable from the parameter type (single-candidate
            // only, first-fit). Covers @Find(char ch, String hex) where the param names ch/hex
            // don't match thisCharacter/hexadecimal.
            for (int i = 0; i < params.length; i++) {
                Attribute<?, ?> match = consumed.contains(i) ? null
                        : singleAttributeOfType(model, paramTypes[i], attributes);
                if (match != null) { paramIndexes.add(i); attributes.add(match); }
            }
            return new FindBindings(List.copyOf(paramIndexes), List.copyOf(attributes), orderBySorts(m));
        }

        private static List<jakarta.data.Sort<?>> orderBySorts(Method m) {
            List<jakarta.data.Sort<?>> sorts = new ArrayList<>();
            for (var o : m.getAnnotationsByType(jakarta.data.repository.OrderBy.class)) {
                sorts.add(o.descending() ? jakarta.data.Sort.desc(o.value()) : jakarta.data.Sort.asc(o.value()));
            }
            return List.copyOf(sorts);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object executeFind(Method m, RepositoryRuntime rt, EntityModel em, Object[] callArgs,
                                      FindBindings bindings, int[] ctrl, Map<String, Attribute<?, ?>> attrIndex) {
        List<Where> parts = new ArrayList<>(bindings.attributes().size());
        Object[] xs = new Object[bindings.attributes().size()];
        for (int k = 0; k < xs.length; k++) {
            parts.add(new Where.Eq(bindings.attributes().get(k)));
            xs[k] = callArgs[bindings.paramIndexes().get(k)];
        }
        Where w = switch (parts.size()) {
            case 0 -> Where.ALWAYS_TRUE;
            case 1 -> parts.get(0);
            default -> new Where.And(parts);
        };
        List<OrderBy.Order> fixedOrder = new ArrayList<>();
        for (jakarta.data.Sort<?> sort : bindings.sorts()) addSortToOrder(sort, attrIndex, fixedOrder);
        OrderBy order = applyControlOrder(new OrderBy(fixedOrder), callArgs, ctrl, attrIndex, em, rt);
        jakarta.data.page.PageRequest pr = controlPage(callArgs, ctrl);
        Class<?> returnType = m.getReturnType();
        if (pr != null && jakarta.data.page.CursoredPage.class.isAssignableFrom(returnType)) {
            return rt.queryCursored(em, w, order, pr, xs);
        }
        if (pr != null && jakarta.data.page.Page.class.isAssignableFrom(returnType)) {
            return rt.queryPage(em, w, order, pr, xs);
        }
        if (returnType == java.util.Optional.class) return rt.queryOne(em, w, xs);
        return shapeFindResult(returnType, limited(rt.queryList(em, w, order, xs), callArgs, ctrl));
    }

    /** The {@code Limit} argument's window of {@code list}, or {@code list} itself without a limit. */
    private static List<Object> limited(List<Object> list, Object[] callArgs, int[] ctrl) {
        return window(list, controlLimitRange(callArgs, ctrl, 0));
    }

    /** {@code list} as the {@code @Find} method returns it: a stream, a list, an array or its first element. */
    private static Object shapeFindResult(Class<?> returnType, List<Object> list) {
        if (returnType == java.util.stream.Stream.class) return list.stream();
        if (List.class.isAssignableFrom(returnType) || returnType == java.util.Collection.class
                || returnType == Iterable.class) {
            return list;
        }
        if (returnType.isArray()) {
            Object arr = java.lang.reflect.Array.newInstance(returnType.getComponentType(), list.size());
            for (int i = 0; i < list.size(); i++) java.lang.reflect.Array.set(arr, i, list.get(i));
            return arr;
        }
        return list.isEmpty() ? null : list.get(0);
    }

    /* ---- Derived queries (findByX, countByX, deleteByX, existsByX, …) ---- */

    private static Dispatcher derivedDispatcher(Method m, EntityModel<?> model,
                                                java.util.Set<String> attributeNames) {
        int[] limitOut = new int[]{0};
        QueryMethodParser.QueryDescriptor desc = QueryMethodParser.parse(m.getName(), attributeNames, limitOut);
        if (desc == null) return null;

        // Map predicate-attribute names to Attribute<?, ?> instances at proxy-build time.
        Map<String, Attribute<?, ?>> attrIndex = new java.util.HashMap<>();
        for (Attribute<?, ?> a : model.attributes()) attrIndex.put(a.name(), a);

        // M7-12 — pre-locate Jakarta Data control parameter positions by type so the dispatcher
        // can read Limit/Sort/Order/PageRequest at call time and apply them to the query.
        DerivedPlan plan = new DerivedPlan(m, desc, attrIndex, buildOrderBy(desc.orderBy(), attrIndex),
                limitOut[0], List.of(locateControlParams(m)));
        return (rt, em, callArgs) -> executeDerived(plan, rt, em, callArgs);
    }

    /** A derived query method, resolved once at proxy creation. {@code ctrl}: see {@link #locateControlParams}. */
    private record DerivedPlan(Method method, QueryMethodParser.QueryDescriptor desc,
                               Map<String, Attribute<?, ?>> attrIndex, OrderBy baseOrderBy, int firstN,
                               List<int[]> ctrl) {
        int[] controls() {
            return ctrl.get(0);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object executeDerived(DerivedPlan plan, RepositoryRuntime rt, EntityModel em, Object[] callArgs) {
        BuiltWhere built = buildWhere(plan.desc(), plan.attrIndex(), callArgs);
        Where w = built.where;
        Object[] xs = built.args;
        Class<?> returnType = plan.method().getReturnType();
        return switch (plan.desc().op()) {
            case FIND     -> derivedFind(plan, rt, em, callArgs, w, xs);
            case FIND_ONE -> derivedFirst(returnType, em,
                    rt.queryList(em, w, derivedOrder(plan, rt, em, callArgs), xs));
            case COUNT    -> rt.countWhere(em, w, xs);
            case EXISTS   -> rt.existsWhere(em, w, xs);
            case DELETE   -> {
                long n = rt.deleteWhere(em, w, xs);
                if (returnType == void.class) yield null;
                yield returnType == int.class || returnType == Integer.class ? (Object) (int) n : (Object) n;
            }
        };
    }

    /** The derived {@code OrderBy} extended with the Sort/Order/Sort[] control arguments. */
    private static OrderBy derivedOrder(DerivedPlan plan, RepositoryRuntime rt, EntityModel<?> em, Object[] callArgs) {
        return applyControlOrder(plan.baseOrderBy(), callArgs, plan.controls(), plan.attrIndex(), em, rt);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object derivedFind(DerivedPlan plan, RepositoryRuntime rt, EntityModel em, Object[] callArgs,
                                      Where w, Object[] xs) {
        Class<?> returnType = plan.method().getReturnType();
        boolean returnsList = List.class.isAssignableFrom(returnType)
                || java.util.Collection.class == returnType || Iterable.class == returnType;
        if (w == Where.ALWAYS_FALSE) {
            return emptyForReturn(returnType == java.util.stream.Stream.class,
                    returnType == java.util.Optional.class, returnsList);
        }
        OrderBy order = derivedOrder(plan, rt, em, callArgs);
        jakarta.data.page.PageRequest pageReq = controlPage(callArgs, plan.controls());
        if (pageReq != null) {
            return jakarta.data.page.CursoredPage.class.isAssignableFrom(returnType)
                    ? rt.queryCursored(em, w, order, pageReq, xs)
                    : rt.queryPage(em, w, order, pageReq, xs);
        }
        if (returnType == java.util.Optional.class) return rt.queryOne(em, w, xs);
        if (returnType.isArray() || returnsList || returnType == java.util.stream.Stream.class) {
            int[] limR = controlLimitRange(callArgs, plan.controls(), plan.firstN());
            return shapeFindResult(returnType, window(rt.queryList(em, w, order, xs), limR));
        }
        java.util.Optional<?> one = rt.queryOne(em, w, xs);
        if (one.isEmpty()) {
            throw new jakarta.data.exceptions.EmptyResultException("No result for " + em.entityClass().getSimpleName());
        }
        return one.get();
    }

    /** {@code [start, count]} window of {@code list}; the whole list when {@code count} is not positive. */
    private static List<Object> window(List<Object> list, int[] range) {
        if (range[1] <= 0) return list;
        int from = Math.max(0, range[0]);
        if (from >= list.size()) return new ArrayList<>();
        return new ArrayList<>(list.subList(from, Math.min(list.size(), from + range[1])));
    }

    /**
     * {@code findFirstBy} / {@code findOneBy}: the FIRST match, no {@code NonUniqueResultException} on several hits;
     * no match is an empty {@code Optional}, or {@code EmptyResultException} for a single entity (MANSART-019).
     */
    private static Object derivedFirst(Class<?> returnType, EntityModel<?> em, List<Object> found) {
        boolean optional = returnType == java.util.Optional.class;
        if (found.isEmpty()) {
            if (optional) return java.util.Optional.empty();
            throw new jakarta.data.exceptions.EmptyResultException("No result for " + em.entityClass().getSimpleName());
        }
        return optional ? java.util.Optional.of(found.get(0)) : found.get(0);
    }

    private static boolean isControlParamType(Class<?> p) {
        return p == jakarta.data.Limit.class
                || p == jakarta.data.Sort.class
                || p == jakarta.data.Order.class
                || p == jakarta.data.page.PageRequest.class
                || p == jakarta.data.Sort[].class;
    }

    /** The attribute a {@code @Find} parameter names with {@code @By} (MANSART-018) or {@code @Param}, or {@code null}. */
    private static String readParamAnnotation(java.lang.reflect.Parameter param) {
        jakarta.data.repository.By by = param.getDeclaredAnnotation(jakarta.data.repository.By.class);
        if (by != null && !by.value().isEmpty()) return by.value();
        jakarta.data.repository.Param p = param.getDeclaredAnnotation(jakarta.data.repository.Param.class);
        return p == null || p.value().isEmpty() ? null : p.value();
    }

    private static Attribute<?, ?> singleAttributeOfType(EntityModel<?> model, Class<?> paramType,
                                                         List<Attribute<?, ?>> alreadyMatched) {
        Class<?> wanted = boxedClass(paramType);
        Attribute<?, ?> only = null;
        int hits = 0;
        for (Attribute<?, ?> a : model.attributes()) {
            if (alreadyMatched.contains(a)) continue;
            Class<?> t = boxedClass(a.javaType());
            if (t == wanted) { only = a; hits++; }
        }
        return hits == 1 ? only : null;
    }

    private static Class<?> boxedClass(Class<?> c) {
        if (c == boolean.class) return Boolean.class;
        if (c == byte.class)    return Byte.class;
        if (c == short.class)   return Short.class;
        if (c == int.class)     return Integer.class;
        if (c == long.class)    return Long.class;
        if (c == float.class)   return Float.class;
        if (c == double.class)  return Double.class;
        if (c == char.class)    return Character.class;
        return c;
    }

    /* ---- M7-12 — Jakarta Data control parameters ---- */

    /**
     * Returns indices of Limit / Sort / Sort[] / Order / PageRequest parameters in the given
     * method signature, packed into a single int[]: index 0..4 are
     * {@code limit, firstSort, sortArr, order, pageRequest}; index 5+ holds any additional
     * {@code jakarta.data.Sort} parameter positions (a method can declare {@code Sort, Sort}
     * for multi-key ordering instead of {@code Sort[]} varargs).
     */
    private static int[] locateControlParams(Method m) {
        int[] base = { -1, -1, -1, -1, -1 };
        java.util.List<Integer> extraSorts = new ArrayList<>();
        Class<?>[] pts = m.getParameterTypes();
        for (int i = 0; i < pts.length; i++) {
            Class<?> p = pts[i];
            if (p == jakarta.data.Limit.class)                     base[0] = i;
            else if (p == jakarta.data.Sort[].class)               base[2] = i;
            else if (p == jakarta.data.Sort.class) {
                if (base[1] < 0) base[1] = i;
                else extraSorts.add(i);
            }
            else if (p == jakarta.data.Order.class)                base[3] = i;
            else if (p == jakarta.data.page.PageRequest.class)     base[4] = i;
        }
        int[] r = new int[5 + extraSorts.size()];
        System.arraycopy(base, 0, r, 0, 5);
        for (int k = 0; k < extraSorts.size(); k++) r[5 + k] = extraSorts.get(k);
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
        // Extra Sort parameters (positions packed in ctrl[5..]) — happens when a method
        // declares Sort, Sort, Sort... explicitly instead of Sort[] varargs.
        for (int k = 5; k < ctrl.length; k++) {
            if (ctrl[k] >= 0 && callArgs[ctrl[k]] instanceof jakarta.data.Sort<?> s) {
                addSortToOrder(s, attrIndex, out);
            }
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

    private static BuiltWhere buildWhere(QueryMethodParser.QueryDescriptor desc,
                                         Map<String, Attribute<?, ?>> attrIndex, Object[] callArgs) {
        List<Where> parts = new ArrayList<>(desc.predicates().size());
        List<Object> args = new ArrayList<>();
        ArgCursor values = new ArgCursor(callArgs);
        for (QueryMethodParser.Predicate p : desc.predicates()) {
            Attribute<?, ?> a = attrIndex.get(p.attribute());
            if (a == null) throw new MansartDataException("Unknown attribute: " + p.attribute());
            Where w = predicate(p.comparator(), a, values, args);
            if (w == null) {
                // An empty IN: the whole derived query reduces to FALSE — caller short-circuits.
                return new BuiltWhere(Where.ALWAYS_FALSE, new Object[0]);
            }
            // M7-27 — IgnoreCase now wraps the predicate so the dialect emits
            // LOWER(col) <op> LOWER(?). Done BEFORE the Not wrapping so the SQL renders as
            // NOT (LOWER(col) <op> LOWER(?)) which is the expected semantics. Wraps any
            // single-attribute comparator on a String column (Eq, NotEq, Lt, Lte, Gt, Gte,
            // Like, Between, In) — the dialect rejects the unsupported ones.
            if (p.ignoreCase()) w = new Where.IgnoreCase(w);
            if (p.negated()) w = new Where.Not(w);
            parts.add(w);
        }
        Where w;
        if (parts.isEmpty()) w = Where.ALWAYS_TRUE;
        else if (parts.size() == 1) w = parts.get(0);
        else w = (desc.combinator() == QueryMethodParser.Combinator.OR)
                ? new Where.Or(parts) : new Where.And(parts);
        return new BuiltWhere(w, args.toArray());
    }

    /** The call's arguments, taken in order by the predicates that bind a value. */
    private static final class ArgCursor {
        private final Object[] values;
        private int next;

        ArgCursor(Object[] values) {
            this.values = values;
        }

        Object next() {
            return values[next++];
        }
    }

    /**
     * The predicate {@code comparator} puts on {@code a}, the values it binds added to {@code args};
     * {@code null} for an {@code IN} over an empty collection, which no row can match.
     */
    private static Where predicate(QueryMethodParser.Comparator comparator, Attribute<?, ?> a,
                                   ArgCursor values, List<Object> args) {
        return switch (comparator) {
            case EQ          -> bindValue(new Where.Eq(a), values.next(), args);
            case NOT_EQ      -> bindValue(new Where.NotEq(a), values.next(), args);
            case LT          -> bindValue(new Where.Lt(a), values.next(), args);
            case LTE         -> bindValue(new Where.Lte(a), values.next(), args);
            case GT          -> bindValue(new Where.Gt(a), values.next(), args);
            case GTE         -> bindValue(new Where.Gte(a), values.next(), args);
            case LIKE        -> bindValue(new Where.Like(a), values.next(), args);
            case BETWEEN     -> {
                args.add(values.next());
                args.add(values.next());
                yield new Where.Between(a);
            }
            case IS_NULL, EMPTY         -> new Where.IsNull(a);
            case IS_NOT_NULL, NOT_EMPTY -> new Where.IsNotNull(a);
            case CONTAINS    -> bindValue(new Where.Like(a), "%" + values.next() + "%", args);
            case STARTS_WITH -> bindValue(new Where.Like(a), values.next() + "%", args);
            case ENDS_WITH   -> bindValue(new Where.Like(a), "%" + values.next(), args);
            case TRUE        -> bindValue(new Where.Eq(a), Boolean.TRUE, args);
            case FALSE       -> bindValue(new Where.Eq(a), Boolean.FALSE, args);
            case IN          -> inPredicate(a, values.next(), args);
        };
    }

    /** {@code where}, its value added to the bound arguments. */
    private static Where bindValue(Where where, Object value, List<Object> args) {
        args.add(value);
        return where;
    }

    private static Where inPredicate(Attribute<?, ?> a, Object value, List<Object> args) {
        if (!(value instanceof Collection<?> col)) {
            args.add(value);
            return new Where.In(a, 1);
        }
        if (col.isEmpty()) return null;
        args.addAll(col);
        return new Where.In(a, col.size());
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
