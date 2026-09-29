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

import io.vidocq.mansart.data.core.JdqlExecutor;
import io.vidocq.mansart.data.core.JdqlResult;
import io.vidocq.mansart.data.core.RepositoryRuntime;
import io.vidocq.mansart.data.dialect.EntityModel;

import java.util.Map;

/**
 * Runs one JDQL statement for the <i>JDQL</i> tab: Mansart's own {@link JdqlExecutor#run} outside tests
 * ({@link #MANSART}), a recording fake in them, so that the tab's tests need no database.
 */
@FunctionalInterface
interface JdqlRunner {

    /** {@link JdqlExecutor#run} on the {@link RepositoryRuntime} bean the call resolved. */
    JdqlRunner MANSART = (jdql, parameters, model, runtime) ->
            JdqlExecutor.run(jdql, parameters, model, (RepositoryRuntime) runtime);

    /**
     * Runs {@code jdql}, in whatever transaction the caller has begun.
     *
     * @param jdql       the statement, stripped
     * @param parameters its named parameters, by name; {@code null} values included
     * @param model      the model of the entity it names
     * @param runtime    the {@code RepositoryRuntime} bean
     * @return its result
     */
    JdqlResult run(String jdql, Map<String, Object> parameters, EntityModel<?> model, Object runtime);
}
