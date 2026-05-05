package io.vidocq.mansart.data.core;

import io.vidocq.mansart.data.dialect.EntityModel;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * M8-3 — central lookup of {@link EntityModel} by entity class. Resolution order:
 * <ol>
 *   <li>Compile-time generated metamodel: {@code <pkg>._<EntitySimpleName>.$MODEL} static.</li>
 *   <li>Fallback to {@link RuntimeEntityModelBuilder#build(Class)} for entities whose owning
 *       module didn't run APT (TCK harness, ad-hoc tests).</li>
 * </ol>
 *
 * <p>Used by {@link JdqlExecutor} and {@link io.vidocq.mansart.data.core.MethodNamePathResolver}
 * when resolving JDQL/method-name path expressions ({@code book.author.name}) — the dialect
 * needs the target table name and PK column to materialise the JOIN clause.
 *
 * <p>Cached per-class. Thread-safe.
 */
final class EntityModels {

    private EntityModels() {}

    private static final ConcurrentMap<Class<?>, EntityModel<?>> CACHE = new ConcurrentHashMap<>();

    static EntityModel<?> lookup(Class<?> entityType) {
        return CACHE.computeIfAbsent(entityType, EntityModels::resolve);
    }

    private static EntityModel<?> resolve(Class<?> entityType) {
        // Try the generated _<EntitySimpleName> class first.
        String pkg = entityType.getPackageName();
        String generatedName = pkg + (pkg.isEmpty() ? "" : ".") + "_" + entityType.getSimpleName();
        try {
            Class<?> meta = Class.forName(generatedName, true, entityType.getClassLoader());
            Field model = meta.getField("$MODEL");
            return (EntityModel<?>) model.get(null);
        } catch (ClassNotFoundException ignored) {
            // Fall through to runtime builder.
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new MansartDataException(
                    "Generated metamodel " + generatedName + " has no public static $MODEL field", e);
        }
        return RuntimeEntityModelBuilder.build(entityType);
    }
}
