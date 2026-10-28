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
package io.vidocq.runtime.extensions.jakartaee.web.foy.live;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServletMountsTest {

    @Test
    void eachBootSeesOnlyItsOwnMounts() {
        Object first = new Object();
        Object second = new Object();
        ServletMount web = new ServletMount("vidocq.servlet", "default", "/", () -> 2);

        ServletMounts.register(first, web);

        assertEquals(List.of(web), ServletMounts.of(first));
        assertEquals(List.of(), ServletMounts.of(second));
        ServletMounts.forget(first);
        assertEquals(List.of(), ServletMounts.of(first));
    }

    @Test
    void theListReadIsACopy() {
        Object boot = new Object();
        ServletMounts.register(boot, new ServletMount("a", "default", "/", () -> 0));
        List<ServletMount> read = ServletMounts.of(boot);

        ServletMounts.register(boot, new ServletMount("b", "default", "/b", () -> 0));

        assertEquals(1, read.size());
        assertThrows(UnsupportedOperationException.class, () -> read.add(null));
        ServletMounts.forget(boot);
    }
}
