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
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.devservices.host.DevServicesException;
import io.vidocq.runtime.devservices.host.DevServicesSession;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The dev services of the tests of {@code vidocq:dev} (BUG-20261114-08, spec §2.5): a session of their own, in the
 * plugin JVM, so that a test that empties a table never empties the application's.
 *
 * <ul>
 *   <li>It opens at the first run that {@linkplain #prepare readies} it: without a test run, no container starts.</li>
 *   <li>Every later run gets the same keys: the containers start once for the whole {@code vidocq:dev} session.</li>
 *   <li>{@link #close} stops them, and waits for a session still opening, so that none outlives the goal; none opens
 *       afterwards.</li>
 *   <li>A session that cannot open is a warning: the run goes on without its keys, and the next run tries again.</li>
 * </ul>
 */
final class DevTestServices implements TestServices {

    /** What the terminal shows when the tests' containers start, before the providers' own lines. */
    static final String STARTING =
            "Tests: starting dev services of their own, apart from the application's; they stop with vidocq:dev";

    /** Opens the tests' session: {@link DevServicesSession#open} with {@link DevServicesSession#DEV_TESTS_HOST}. */
    @FunctionalInterface
    interface Opener {
        DevServicesSession open() throws DevServicesException;
    }

    private final Opener opener;
    private final Consumer<String> info;
    private final Consumer<String> warn;
    /** Held while the session opens or closes, so that {@link #close} waits for an open in flight. */
    private final Object lock = new Object();
    /** Guarded by lock. */
    private DevServicesSession session;
    /** Guarded by lock. */
    private boolean closed;
    /** The session's keys, read without the lock by the run about to start. */
    private final AtomicReference<Map<String, String>> keys = new AtomicReference<>(Map.of());

    DevTestServices(Opener opener, Consumer<String> info, Consumer<String> warn) {
        this.opener = opener;
        this.info = info;
        this.warn = warn;
    }

    @Override
    public void prepare() {
        synchronized (lock) {
            if (closed || session != null) {
                return;
            }
            info.accept(STARTING);
            try {
                session = opener.open();
            } catch (DevServicesException e) {
                warn.accept("Tests: their dev services did not start (" + e.getMessage()
                        + "); this run goes on without them, the next one tries again");
                return;
            }
            Map<String, String> folded = new LinkedHashMap<>();
            session.foldInto(folded);
            keys.set(Collections.unmodifiableMap(folded));
        }
    }

    @Override
    public Map<String, String> keys() {
        return keys.get();
    }

    /** Stops the tests' containers, once; idempotent. */
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            keys.set(Map.of());
            if (session != null) {
                session.close();
                session = null;
            }
        }
    }
}
