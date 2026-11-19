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
package io.vidocq.heisenberg.cdi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vidocq.heisenberg.internal.PolicyComposer;
import jakarta.enterprise.context.control.RequestContextController;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** MP Fault Tolerance 4.1 §5: an asynchronous invocation runs with an active request context. */
class RequestContextActivationTest {

    private static final String ACTIVATE = "activate";
    private static final String RESULT = "done";

    /** A controller that reports whether {@code activate} found the context inactive. */
    private static final class Controller implements RequestContextController {
        final List<String> events = new CopyOnWriteArrayList<>();
        private final boolean activates;

        Controller(boolean activates) {
            this.activates = activates;
        }

        @Override
        public boolean activate() {
            events.add(ACTIVATE);
            return activates;
        }

        @Override
        public void deactivate() {
            events.add("deactivate");
        }
    }

    @Test
    void withoutAContainerTheInvocationIsUnchanged() {
        PolicyComposer.Invocation invocation = () -> RESULT;

        assertSame(invocation, new RequestContextActivation(null).wrap(invocation));
        assertSame(invocation, new RequestContextActivation(new FixedInstance(null).instance()).wrap(invocation));
    }

    @Test
    void activatesRunsAndDeactivatesAnInactiveContext() throws Exception {
        Controller controller = new Controller(true);
        FixedInstance controllers = new FixedInstance(controller);
        List<String> seen = new CopyOnWriteArrayList<>();

        Object result = new RequestContextActivation(controllers.instance()).wrap(() -> {
            seen.addAll(controller.events);
            return RESULT;
        }).proceed();

        assertEquals(RESULT, result);
        assertEquals(List.of(ACTIVATE), seen);
        assertEquals(List.of(ACTIVATE, "deactivate"), controller.events);
        assertEquals(List.of(controller), controllers.destroyed());
    }

    @Test
    void leavesAnAlreadyActiveContextActiveEvenOnFailure() {
        Controller controller = new Controller(false);
        FixedInstance controllers = new FixedInstance(controller);
        PolicyComposer.Invocation wrapped = new RequestContextActivation(controllers.instance()).wrap(() -> {
            throw new IllegalStateException("business failure");
        });

        assertThrows(IllegalStateException.class, wrapped::proceed);

        assertEquals(List.of(ACTIVATE), controller.events);
        assertEquals(List.of(controller), controllers.destroyed());
    }
}
