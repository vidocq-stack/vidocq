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

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * A failure that is neither an {@link Exception} nor an {@link Error} cannot travel through the
 * {@code throws Exception} of an interceptor chain as it is: every engine wraps it the same way, in
 * an {@link UndeclaredThrowableException} that keeps it as its cause.
 */
class FailuresTest {

    private static final Throwable ODD = new Throwable("neither an exception nor an error");

    /** Throws a checked {@link Throwable} that an {@code Invocation} cannot declare. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> Object sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }

    @Test
    void exceptionsAreReturnedAsTheyAre() {
        IOException failure = new IOException("io");

        assertSame(failure, Failures.asException(failure));
    }

    @Test
    void errorsAreThrownAsTheyAre() {
        AssertionError failure = new AssertionError("error");

        assertSame(failure, assertThrows(AssertionError.class, () -> Failures.asException(failure)));
    }

    @Test
    void otherThrowablesAreWrapped() {
        assertSame(ODD, Failures.asException(ODD).getCause());
    }

    @Test
    @SuppressWarnings("unchecked")
    void retryEngineWrapsAnOddFailure() {
        RetryConfig retryNothing = new RetryConfig(0, 0, ChronoUnit.MILLIS, 10_000, ChronoUnit.MILLIS,
                0, ChronoUnit.MILLIS, new Class[]{Throwable.class}, new Class[0]);

        UndeclaredThrowableException thrown = assertThrows(UndeclaredThrowableException.class,
                () -> RetryEngine.execute(() -> sneakyThrow(ODD), retryNothing));

        assertSame(ODD, thrown.getCause());
    }

    @Test
    void timeoutEngineWrapsAnOddFailure() {
        TimeoutConfig generous = new TimeoutConfig(10, ChronoUnit.SECONDS);

        UndeclaredThrowableException thrown = assertThrows(UndeclaredThrowableException.class,
                () -> TimeoutEngine.execute(() -> sneakyThrow(ODD), generous));

        assertSame(ODD, thrown.getCause());
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallbackMethodFailureIsWrappedWhenOdd() throws NoSuchMethodException {
        Method guarded = OddFallbackBean.class.getMethod("guarded");
        FallbackConfig config = new FallbackConfig(new Class[]{Throwable.class}, new Class[0], "recover", null);
        FallbackResolver resolver = new FallbackResolver();
        OddFallbackBean bean = new OddFallbackBean();
        IOException failure = new IOException("guarded failed");

        UndeclaredThrowableException thrown = assertThrows(UndeclaredThrowableException.class,
                () -> resolver.resolve(config, bean, guarded, null, failure));

        assertSame(ODD, thrown.getCause());
    }

    /** A bean whose fallback method fails with a {@link Throwable} it does not declare. */
    public static class OddFallbackBean {
        public String guarded() throws IOException {
            throw new IOException("guarded");
        }

        public String recover() {
            return (String) sneakyThrow(ODD);
        }
    }
}
