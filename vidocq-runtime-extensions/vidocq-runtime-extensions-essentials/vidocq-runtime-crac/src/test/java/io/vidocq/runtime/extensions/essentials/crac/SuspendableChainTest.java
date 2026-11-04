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
package io.vidocq.runtime.extensions.essentials.crac;


import io.vidocq.runtime.spi.Suspendable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SuspendableChainTest {

    private final List<String> calls = new ArrayList<>();

    private Suspendable recording(String name) {
        return new Suspendable() {
            @Override public void suspend() { calls.add("suspend " + name); }
            @Override public void resume() { calls.add("resume " + name); }
        };
    }

    private Suspendable failing(String name) {
        return new Suspendable() {
            @Override public void suspend() { calls.add("suspend " + name); throw new IllegalStateException(name); }
            @Override public void resume() { calls.add("resume " + name); throw new IllegalStateException(name); }
        };
    }

    @Test
    void suspendsInReverseStartOrder_resumesInStartOrder() {
        SuspendableChain chain = new SuspendableChain(List.of(recording("a"), recording("b"), recording("c")));

        chain.suspend();
        chain.resume();

        assertEquals(List.of("suspend c", "suspend b", "suspend a", "resume a", "resume b", "resume c"), calls);
    }

    @Test
    void aFailureDoesNotStopTheOthers_andIsRethrownWithTheLaterOnesSuppressed() {
        SuspendableChain chain = new SuspendableChain(List.of(recording("a"), failing("b"), failing("c")));

        IllegalStateException first = assertThrows(IllegalStateException.class, chain::suspend);

        assertEquals(List.of("suspend c", "suspend b", "suspend a"), calls);
        assertEquals("c", first.getMessage());
        assertEquals(1, first.getSuppressed().length);
    }

    @Test
    void anEmptyChainDoesNothing() {
        new SuspendableChain(List.of()).suspend();
        new SuspendableChain(List.of()).resume();
        assertEquals(List.of(), calls);
    }
}
