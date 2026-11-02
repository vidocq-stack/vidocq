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
package io.vidocq.dirac.cdi.internal;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.metrics.annotation.Timed;
import org.junit.jupiter.api.Test;

/**
 * An interceptor instance is created per bean instance, so per request for a request-scoped bean: the reflective
 * resolution of a metric is kept per class, not per interceptor instance, and an invocation reads no annotation.
 */
class InterceptorResolutionCacheTest {

    public static class Measured {
        @Counted(name = "calls")
        @Timed(name = "time")
        public String call() {
            return "ok";
        }
    }

    @Test
    void twoCountedInterceptorsShareTheResolutionOfAMethod() throws Exception {
        Method call = Measured.class.getMethod("call");

        var first = CountedInterceptor.resolution(Measured.class, call);
        var second = CountedInterceptor.resolution(Measured.class, call);

        assertTrue(first.isPresent());
        assertSame(first.get(), second.get());
    }

    @Test
    void twoTimedInterceptorsShareTheResolutionOfAMethod() throws Exception {
        Method call = Measured.class.getMethod("call");

        var first = TimedInterceptor.resolution(Measured.class, call);
        var second = TimedInterceptor.resolution(Measured.class, call);

        assertTrue(first.isPresent());
        assertSame(first.get(), second.get());
    }
}
