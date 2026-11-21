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

import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A container that cannot read this module by reflection (a Vidocq runtime extension compiled ahead of time) calls the
 * start check from a bean of its own: the check has to be public, as the interceptor of {@code @AutoApplySession} is.
 */
class IdentityStoreStartCheckContractTest {

    @Test
    void theCheckCanBeCalledFromAnotherModule() throws Exception {
        var check = IdentityStoreStartCheck.class.getDeclaredMethod("check", Startup.class, Instance.class);

        assertTrue(Modifier.isPublic(check.getModifiers()));
    }
}
