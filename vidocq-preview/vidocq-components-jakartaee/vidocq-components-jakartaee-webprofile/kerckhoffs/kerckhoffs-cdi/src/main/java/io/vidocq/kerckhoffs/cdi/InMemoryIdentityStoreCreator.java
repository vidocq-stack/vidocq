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
package io.vidocq.kerckhoffs.cdi;

import io.vidocq.kerckhoffs.core.internal.identitystore.InMemoryIdentityStore;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.security.enterprise.identitystore.IdentityStore;
import jakarta.security.enterprise.identitystore.InMemoryIdentityStoreDefinition;

/**
 * Creates the in-memory identity store of one {@link InMemoryIdentityStoreDefinition}, and warns that it exists: its
 * callers and their passwords are written in the code of the application, which the specification allows for
 * development and tests (PLAN.md section 6). The store is a singleton the start check creates, so the warning is given
 * once per definition, at start.
 */
public class InMemoryIdentityStoreCreator implements SyntheticBeanCreator<IdentityStore> {

    /** The logger of the warning an in-memory store gives at start. */
    static final String LOGGER_NAME = "io.vidocq.kerckhoffs.identitystore";
    private static final System.Logger LOGGER = System.getLogger(LOGGER_NAME);

    @Override
    public IdentityStore create(Instance<Object> lookup, Parameters params) {
        IdentityStore store = InMemoryIdentityStore.of(params.get("definition", InMemoryIdentityStoreDefinition.class));
        LOGGER.log(System.Logger.Level.WARNING, () -> "The in-memory identity store declared by "
                + params.get("origin", String.class) + " is in service: its callers and passwords are written in the"
                + " application's code. Use it for development and tests, never in production.");
        return store;
    }
}
