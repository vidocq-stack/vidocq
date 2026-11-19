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
package io.vidocq.runtime.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An application class that stayed in the boot layer, with its package not exported to the runtime, is
 * reported with what to do instead of a bare IllegalAccessException (vidocq#201, BUG-20260815-01).
 */
@DisplayName("Access to an application class the runtime cannot instantiate")
class BootLayerAccessTest {

    @Test
    @DisplayName("a package not exported to the runtime is reported with both ways out")
    void unexportedPackage() throws Exception {
        Class<?> hidden = Class.forName("jdk.internal.misc.VM");

        var problem = Vidocq.inaccessibleApplicationClass(hidden, Vidocq.class.getModule());

        assertTrue(problem != null
                && problem.contains("jdk.internal.misc.VM")
                && problem.contains("exports jdk.internal.misc to io.vidocq.runtime.core;")
                && problem.contains("CDI bean"), String.valueOf(problem));
    }

    @Test
    @DisplayName("an exported package is fine")
    void exportedPackage() {
        assertNull(Vidocq.inaccessibleApplicationClass(String.class, Vidocq.class.getModule()));
    }
}
