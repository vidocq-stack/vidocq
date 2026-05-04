package io.vidocq.mansart.data.core;

import io.vidocq.mansart.data.dialect.EntityModel;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * M7-1 runtime fallback for {@code @Repository} interfaces that have no compile-time
 * {@code *Impl} (e.g. TCK-supplied entities, ad-hoc usages without APT). Implements the
 * inherited {@code BasicRepository}/{@code CrudRepository} methods via {@code java.lang
 * .reflect.Proxy} dispatching to {@link RepositoryRuntime}.
 *
 * <p>Derived query methods, {@code @Query} JDQL, and {@code @Insert}/{@code @Update}/
 * {@code @Delete} lifecycle annotations are NOT supported on the runtime path yet — they
 * land in M7-2 (bytecode generation via {@code java.lang.classfile}, the runtime equivalent
 * of {@code mansart-data-processor.RepositoryWriter}).
 */
public final class RuntimeRepositoryProxy {

    private RuntimeRepositoryProxy() {}

    /**
     * Build a proxy for {@code repoInterface}, resolving the entity from the
     * {@code BasicRepository<T, K>} (or {@code CrudRepository<T, K>}) supertype.
     */
    public static <R> R create(Class<R> repoInterface, RepositoryRuntime runtime) {
        Class<?> entityClass = resolveEntityClass(repoInterface);
        if (entityClass == null) {
            throw new MansartDataException(repoInterface.getName()
                    + " does not extend BasicRepository<E, K> / CrudRepository<E, K> — runtime path needs the entity type.");
        }
        EntityModel<?> model = RuntimeEntityModelBuilder.build(entityClass);
        return create(repoInterface, model, runtime);
    }

    @SuppressWarnings("unchecked")
    public static <R> R create(Class<R> repoInterface, EntityModel<?> model, RepositoryRuntime runtime) {
        InvocationHandler handler = new Handler(repoInterface, model, runtime);
        return (R) Proxy.newProxyInstance(repoInterface.getClassLoader(),
                new Class<?>[]{ repoInterface }, handler);
    }

    private static Class<?> resolveEntityClass(Class<?> repoInterface) {
        for (Type t : repoInterface.getGenericInterfaces()) {
            Class<?> e = entityFromGenericInterface(t);
            if (e != null) return e;
        }
        for (Class<?> parent : repoInterface.getInterfaces()) {
            Class<?> e = resolveEntityClass(parent);
            if (e != null) return e;
        }
        return null;
    }

    private static Class<?> entityFromGenericInterface(Type t) {
        if (!(t instanceof ParameterizedType pt)) return null;
        Type raw = pt.getRawType();
        if (!(raw instanceof Class<?> rawClass)) return null;
        String fqn = rawClass.getName();
        if (fqn.equals("jakarta.data.repository.BasicRepository")
                || fqn.equals("jakarta.data.repository.CrudRepository")
                || fqn.equals("jakarta.data.repository.DataRepository")) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length >= 2 && args[0] instanceof Class<?> entity) return entity;
        }
        return null;
    }

    /* ---- dispatch ---- */

    @SuppressWarnings({"rawtypes", "unchecked"})
    private record Handler(Class<?> itf, EntityModel<?> model, RepositoryRuntime runtime)
            implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.isDefault()) {
                throw new UnsupportedOperationException(
                        "Default methods on @Repository not yet supported on the runtime path");
            }
            EntityModel m = model;
            switch (method.getName()) {
                case "save":
                    return runtime.save(m, args[0]);
                case "saveAll": {
                    List<Object> out = new ArrayList<>();
                    for (Object e : (Iterable<?>) args[0]) out.add(runtime.save(m, e));
                    return out;
                }
                case "findById":
                    return runtime.findById(m, args[0]);
                case "findAll":
                    if (args == null || args.length == 0) {
                        return runtime.findAll(m).stream();
                    }
                    if (args.length == 2) { // findAll(PageRequest, Order<T>)
                        return runtime.queryPage(m,
                                io.vidocq.mansart.data.dialect.Where.ALWAYS_TRUE,
                                runtime.toOrderBy(m, (jakarta.data.Order) args[1]),
                                (jakarta.data.page.PageRequest) args[0]);
                    }
                    throw unsupported(method);
                case "deleteById":
                    runtime.deleteById(m, args[0]);
                    return null;
                case "delete":
                    runtime.delete(m, args[0]);
                    return null;
                case "deleteAll":
                    if (args == null || args.length == 0) {
                        for (Object e : runtime.findAll(m)) runtime.delete(m, e);
                    } else {
                        for (Object e : (Iterable<?>) args[0]) runtime.delete(m, e);
                    }
                    return null;
                case "count":
                    return runtime.count(m);
                case "existsById":
                    return runtime.existsById(m, args[0]);
                default:
                    throw unsupported(method);
            }
        }

        private static UnsupportedOperationException unsupported(Method m) {
            return new UnsupportedOperationException(
                    "Mansart M7-1 runtime proxy: '" + m.getName() + "' not supported. "
                  + "Derived queries / @Query / lifecycle annotations land in M7-2 "
                  + "(java.lang.classfile bytecode generation).");
        }
    }
}
