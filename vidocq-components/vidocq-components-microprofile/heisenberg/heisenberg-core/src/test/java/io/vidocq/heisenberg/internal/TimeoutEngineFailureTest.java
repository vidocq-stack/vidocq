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
package io.vidocq.heisenberg.internal;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/** MP FT 4.1 §4: failures other than exceptions, raised before the deadline, reach the caller. */
class TimeoutEngineFailureTest {

    private static final TimeoutConfig GENEROUS = new TimeoutConfig(5_000, ChronoUnit.MILLIS);

    /** Throws a checked {@link Throwable} that an {@code Invocation} cannot declare. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> Object sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }

    @Test
    void errorIsRethrownAsIs() {
        AssertionError error = new AssertionError("broken");

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> TimeoutEngine.execute(() -> sneakyThrow(error), GENEROUS));

        assertSame(error, thrown);
    }

    @Test
    void plainThrowableIsWrappedInARuntimeException() {
        Throwable failure = new Throwable("odd");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> TimeoutEngine.execute(() -> sneakyThrow(failure), GENEROUS));

        assertSame(failure, thrown.getCause());
    }
}
