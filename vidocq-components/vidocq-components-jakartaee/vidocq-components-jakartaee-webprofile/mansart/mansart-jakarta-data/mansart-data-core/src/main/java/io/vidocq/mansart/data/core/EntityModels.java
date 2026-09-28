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

import io.vidocq.mansart.data.dialect.EntityModel;

import java.lang.reflect.Field;

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
 * needs the target table name and PK column to materialise the JOIN clause. Public through
 * {@link #of(Class)} only, for tools that show what Mansart knows, such as the Vidocq dev console.
 *
 * <p>Cached per-class. Thread-safe.
 */
public final class EntityModels {

    private EntityModels() {}

    /**
     * One model per class, kept with the class itself rather than in a map: a class whose loader is dropped, such as
     * the entities of an application layer a dev reload replaces, takes its model with it instead of staying
     * reachable from here.
     */
    private static final ClassValue<EntityModel<?>> CACHE = new ClassValue<>() {
        @Override
        protected EntityModel<?> computeValue(Class<?> entityType) {
            return resolve(entityType);
        }
    };

    /**
     * The model Mansart uses for {@code entityType}: its generated metamodel, or one built at run time.
     *
     * <p>The generated {@code <pkg>._<EntitySimpleName>.$MODEL} wins; without it, the model is built from the
     * class's fields and its {@code jakarta.persistence} mapping annotations, read by name. The model is cached with
     * the class: a second call returns the same instance, and the model goes away with the class's loader.
     *
     * <p>The model's attributes carry the {@link java.lang.invoke.MethodHandle getter and setter} Mansart reads and
     * writes the entity's fields with, built with a lookup the entity's package opens to Mansart; the generated
     * {@code $MODEL} is public and carries the same. A caller reads the model to describe the entity, as the
     * Vidocq dev console does, and does not use those handles.
     *
     * @param entityType the entity class
     * @param <E>        the entity type
     * @return its model, never {@code null}
     * @throws MansartDataException when Mansart cannot map {@code entityType}: no id field, no no-arg constructor,
     *                              a package not open to {@code io.vidocq.mansart.data.core}, or a generated
     *                              metamodel without a public static {@code $MODEL} field; the initialisation of a
     *                              generated metamodel may also throw its own error
     */
    @SuppressWarnings("unchecked")
    public static <E> EntityModel<E> of(Class<E> entityType) {
        return (EntityModel<E>) lookup(entityType);
    }

    static EntityModel<?> lookup(Class<?> entityType) {
        return CACHE.get(entityType);
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
