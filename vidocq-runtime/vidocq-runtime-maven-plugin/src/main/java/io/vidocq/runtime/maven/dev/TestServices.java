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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The dev services of the test runs (spec §2.5): what {@link TestRunner} hands each run as {@code -D} keys, and what
 * readies them before it. The test JVM never starts dev services of its own: the plugin JVM hosts them.
 */
interface TestServices {

    /**
     * Readies the keys before a run, on the worker of {@link ContinuousTesting} and outside its lock: it may take long,
     * such as starting the tests' containers at their first run. Never throws: what fails is a warning.
     */
    void prepare();

    /** The keys of the run about to start, in order, without waiting. */
    Map<String, String> keys();

    /** Stops what {@link #prepare} started, once the runs are over; nothing by default. */
    default void close() {
        // nothing started
    }

    /** Keys known up front, nothing to ready: {@code vidocq:test}'s own session, or no dev service at all. */
    static TestServices fixed(Map<String, String> keys) {
        return new Fixed(Collections.unmodifiableMap(new LinkedHashMap<>(keys)));
    }

    /** {@link #fixed}. */
    record Fixed(Map<String, String> keys) implements TestServices {

        @Override
        public void prepare() {
            // known up front
        }
    }
}
