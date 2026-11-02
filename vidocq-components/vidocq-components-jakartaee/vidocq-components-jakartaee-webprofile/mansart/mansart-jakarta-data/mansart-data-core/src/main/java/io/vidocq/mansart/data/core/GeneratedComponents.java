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

/**
 * What the annotation processor generates in each package of entities or repositories (MANSART-038): a
 * {@code <package>._MansartComponents} class, declared in {@code META-INF/services}, that hands Mansart the entity
 * models and creates the repository implementations by plain calls compiled in the application's module. Mansart then
 * reaches them without reflection, so a named module needs neither {@code opens} nor {@code exports} for them. Without
 * it (no processor, a portable container that does not load it), Mansart falls back to reflection.
 */
public interface GeneratedComponents {

    /** The model of {@code entity}, or {@code null} when it is not an entity of this package. */
    EntityModel<?> model(Class<?> entity);

    /** A new implementation of {@code repository} on {@code runtime}, or {@code null} when it is not one of this package. */
    Object repository(Class<?> repository, RepositoryRuntime runtime);
}
