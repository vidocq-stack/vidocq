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
package io.vidocq.runtime.extensions.jakartaee.web.kerckhoffs;

import io.vidocq.kerckhoffs.cdi.IdentityStoreStartCheck;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Singleton;
import jakarta.security.enterprise.identitystore.IdentityStore;

/**
 * The start check of the identity stores, as a bean of this module: Vauban compiles it ahead of time, which it cannot
 * do for {@link IdentityStoreStartCheck}, a class of a module it does not process. It runs at start, whatever the
 * build froze.
 */
@Singleton
public class VidocqIdentityStoreStartCheck {

    private final IdentityStoreStartCheck check = new IdentityStoreStartCheck();

    void check(@Observes Startup startup, Instance<IdentityStore> stores) {
        check.check(startup, stores);
    }
}
