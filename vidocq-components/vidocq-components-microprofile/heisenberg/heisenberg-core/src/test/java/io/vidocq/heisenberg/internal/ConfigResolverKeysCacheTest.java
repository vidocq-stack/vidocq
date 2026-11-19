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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Method;
import java.util.List;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.junit.jupiter.api.Test;

/**
 * The configuration keys of a method and one of its policy parameters are computed once: an interceptor resolves them
 * on every invocation, and computing them read the method's and the class's annotations by reflection each time.
 */
class ConfigResolverKeysCacheTest {

    @Retry
    static class Service {
        public void call() {
            // A method the class-level @Retry applies to.
        }
    }

    @Test
    void theKeysOfAMethodParameterAreComputedOnce() throws Exception {
        Method method = Service.class.getMethod("call");

        List<String> first = ConfigResolver.keys(method, Service.class, Retry.class, "Retry", "maxRetries", false);
        List<String> second = ConfigResolver.keys(method, Service.class, Retry.class, "Retry", "maxRetries", false);

        assertSame(first, second);
        assertEquals(List.of(Service.class.getName() + "/call/Retry/maxRetries",
                Service.class.getName() + "/Retry/maxRetries", "Retry/maxRetries"), first);
    }

    @Test
    void anotherParameterHasItsOwnKeys() throws Exception {
        Method method = Service.class.getMethod("call");

        List<String> delay = ConfigResolver.keys(method, Service.class, Retry.class, "Retry", "delay", false);

        assertEquals("Retry/delay", delay.getLast());
    }

    /** A redeployment in the same JVM (hot reload) drops the keys, so the previous classes are not kept alive. */
    @Test
    void aConfigurationChangeDropsTheCachedKeys() throws Exception {
        Method method = Service.class.getMethod("call");
        List<String> before = ConfigResolver.keys(method, Service.class, Retry.class, "Retry", "jitter", false);

        ConfigResolver.configurationChanged();

        assertNotSame(before, ConfigResolver.keys(method, Service.class, Retry.class, "Retry", "jitter", false));
    }
}
