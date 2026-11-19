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
package io.vidocq.kerckhoffs.core.internal.context;

import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The built-in {@code Principal} bean (Jakarta Security 4.0, section 1.2.4): injected once, it stands for the caller
 * of whichever request is being served when it is asked.
 */
class CurrentCallerPrincipalTest {

    private Principal caller;

    private final CurrentCallerPrincipal current = new CurrentCallerPrincipal(() -> Optional.ofNullable(caller));

    @Test
    void withoutACallerItHasNoName() {
        assertNull(current.getName());
    }

    @Test
    void itFollowsTheCallerOfTheMoment() {
        caller = () -> "reza";
        assertEquals("reza", current.getName());

        caller = () -> "alex";
        assertEquals("alex", current.getName(), "the next request has its own caller");
    }

    @Test
    void itIsComparedByName() {
        caller = () -> "reza";

        assertEquals("reza", current.toString());
        assertEquals("reza".hashCode(), current.hashCode());
        assertEquals(current, new CurrentCallerPrincipal(() -> Optional.of(caller)));
        assertNotEquals(current, new CurrentCallerPrincipal(Optional::empty));
        assertNotEquals(null, current);
    }
}
