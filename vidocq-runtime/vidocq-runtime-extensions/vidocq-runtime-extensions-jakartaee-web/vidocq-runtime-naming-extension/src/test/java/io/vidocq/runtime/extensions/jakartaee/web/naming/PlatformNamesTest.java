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
package io.vidocq.runtime.extensions.jakartaee.web.naming;

import io.vidocq.vauban.naming.Binder;
import io.vidocq.vauban.naming.JndiTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The names the Jakarta EE platform defines, bound to the container's beans when it has them. */
class PlatformNamesTest {

    private static final Map<String, String> RUNNABLE = Map.of("java:comp/Runnable", "java.lang.Runnable");

    /** Records what is bound. */
    private static final class Recorder implements Binder {
        final Map<String, JndiTarget> bound = new LinkedHashMap<>();
        @Override public Binder bind(String name, JndiTarget target) { bound.put(name, target); return this; }
    }

    @AfterEach
    void clear() {
        ConfiguredNames.declare(Map.of());
    }

    private static Map<String, JndiTarget> bound(Map<String, String> names, Predicate<JndiTarget.Bean> resolvable) {
        var recorder = new Recorder();
        new PlatformNames(names, resolvable).bind(recorder);
        return recorder.bound;
    }

    @Test
    void theNamesAreTheTransactionOnesOfThePlatform() {
        Map<String, String> names = PlatformNames.TRANSACTION_NAMES;

        assertEquals(Map.of("java:comp/UserTransaction", "jakarta.transaction.UserTransaction",
                "java:comp/TransactionSynchronizationRegistry", "jakarta.transaction.TransactionSynchronizationRegistry"),
                names);
    }

    @Test
    void aNameIsBoundToTheDefaultBeanOfItsType() {
        var bean = (JndiTarget.Bean) bound(RUNNABLE, target -> true).get("java:comp/Runnable");

        assertEquals(Runnable.class, bean.type());
        assertEquals(Set.of(), bean.qualifiers());
    }

    @Test
    void aNameWhoseTypeHasNoBeanIsNotBound() {
        assertEquals(Map.of(), bound(RUNNABLE, target -> false));
    }

    @Test
    void aNameWhoseTypeTheApplicationLacksIsNotBound() {
        assertEquals(Map.of(), bound(Map.of("java:comp/Absent", "com.example.absent.Absent"), target -> true));
    }

    @Test
    void aNameTheConfigurationDeclaresIsLeftToIt() {
        ConfiguredNames.declare(Map.of("java:comp/Runnable", "java.lang.Runnable @Named(\"mine\")"));

        assertEquals(Map.of(), bound(RUNNABLE, target -> true));
    }
}
