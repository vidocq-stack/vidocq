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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live;

import java.util.List;

/**
 * What Mansart Data knows about the application, read once per boot by the runtime extension: its entities with
 * their table and columns, its repositories with their methods. It holds names and texts only, never a class, a bean,
 * a class loader or a connection, so that the {@code -dev} panel holding it across a dev reload keeps nothing of the
 * previous boot alive.
 *
 * @param entities        the primary entities of the repositories, in name order, up to the limit
 * @param repositories    the repositories, in name order, up to the limit
 * @param entityCount     how many entities there are, those past the limit included
 * @param repositoryCount how many repositories there are, those past the limit included
 * @param methodCount     how many methods all the repositories declare
 */
public record MansartDataCatalogue(List<Entity> entities, List<Repository> repositories, int entityCount,
                                   int repositoryCount, int methodCount) {

    public MansartDataCatalogue {
        entities = List.copyOf(entities);
        repositories = List.copyOf(repositories);
    }

    /** How many entities are counted but not listed. */
    public int moreEntities() {
        return entityCount - entities.size();
    }

    /** How many repositories are counted but not listed. */
    public int moreRepositories() {
        return repositoryCount - repositories.size();
    }

    /** The listed repositories whose primary entity is {@code entity}, in name order. */
    public List<Repository> repositoriesOf(Entity entity) {
        return repositories.stream().filter(r -> entity.className().equals(r.entityClassName())).toList();
    }

    /** The listed repositories with no primary entity, in name order. */
    public List<Repository> otherRepositories() {
        return repositories.stream().filter(r -> r.entityClassName() == null).toList();
    }

    /**
     * An entity, as Mansart's own model describes it.
     *
     * @param name      its simple name, or its full name when another entity has the same simple name
     * @param className its full class name
     * @param table     {@code schema.table}, or the table alone; empty when its model could not be read
     * @param columns   the id first, then the version, then the others in model order; empty when its model could not
     *                  be read
     * @param failure   why its model could not be read, the class of the exception or {@code unusable model};
     *                  {@code null} when it was read
     */
    public record Entity(String name, String className, String table, List<Column> columns, String failure) {

        public Entity {
            columns = List.copyOf(columns);
        }
    }

    /**
     * One attribute of an entity.
     *
     * @param field    the attribute name
     * @param column   the column name
     * @param type     the simple name of its Java type
     * @param key      {@code id} ({@code id, generated}), {@code version}, {@code enum}, {@code → <Entity>},
     *                 {@code joined}, or empty
     * @param nullable whether the column accepts null
     * @param unique   whether the column is unique
     */
    public record Column(String field, String column, String type, String key, boolean nullable, boolean unique) {}

    /**
     * A repository interface.
     *
     * @param name            its simple name, or its full name when another repository has the same simple name
     * @param className       its full interface name
     * @param entityClassName the full class name of its primary entity, {@code null} when it has none
     * @param entityName      the catalogue's name of that entity, {@code null} when it has none
     * @param idType          the simple name of its id type, {@code null} when it has no primary entity
     * @param methods         its declared methods, in name order, up to the limit
     * @param methodCount     how many methods it declares
     * @param inherits        {@code inherits BasicRepository: delete, …}, or empty
     */
    public record Repository(String name, String className, String entityClassName, String entityName, String idType,
                             List<Method> methods, int methodCount, String inherits) {

        public Repository {
            methods = List.copyOf(methods);
        }

        /** How many declared methods are counted but not listed. */
        public int moreMethods() {
            return methodCount - methods.size();
        }
    }

    /**
     * A declared method of a repository.
     *
     * @param name       the method name
     * @param kind       {@code JDQL}, {@code @Find}, {@code @Insert}, {@code @Update}, {@code @Delete}, {@code @Save},
     *                   {@code derived} or {@code other}
     * @param query      the {@code @Query} text as written, cut after 1,000 characters; empty for the other kinds
     * @param parameters {@code name: Type}, comma-separated
     * @param returns    the generic return type in simple names
     */
    public record Method(String name, String kind, String query, String parameters, String returns) {}
}
