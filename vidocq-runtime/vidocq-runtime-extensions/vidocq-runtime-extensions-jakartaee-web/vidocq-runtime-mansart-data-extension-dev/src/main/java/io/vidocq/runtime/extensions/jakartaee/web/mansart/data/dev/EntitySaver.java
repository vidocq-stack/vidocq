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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.mansart.data.core.RepositoryRuntime;
import io.vidocq.mansart.data.dialect.EntityModel;

/**
 * Saves one entity an import built (CSV spec §4): Mansart's own {@code RepositoryRuntime.save} outside tests
 * ({@link #MANSART}) — an insert when its id is {@code null}, the id generated, an upsert otherwise — a recording fake
 * in them, so that the tab's tests need no database.
 */
@FunctionalInterface
interface EntitySaver {

    /** {@code RepositoryRuntime.save} on the bean the call resolved. */
    EntitySaver MANSART = EntitySaver::mansart;

    /**
     * Saves {@code entity}, in whatever transaction the caller has begun.
     *
     * @param model   the model of its class
     * @param entity  the entity
     * @param runtime the {@code RepositoryRuntime} bean
     * @return what the save returned
     */
    Object save(EntityModel<?> model, Object entity, Object runtime);

    @SuppressWarnings("unchecked")
    private static Object mansart(EntityModel<?> model, Object entity, Object runtime) {
        return ((RepositoryRuntime) runtime).save((EntityModel<Object>) model, entity);
    }
}
