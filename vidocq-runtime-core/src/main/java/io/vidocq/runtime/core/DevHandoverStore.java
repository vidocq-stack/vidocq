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
package io.vidocq.runtime.core;

import io.vidocq.runtime.spi.DevHandover;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The handover store of the reload loop of {@code vidocq:dev}: one per JVM, handed to each boot it creates, so the
 * bytes one boot puts during its stop reach the next one (session handover design §2). Its class is loaded by the boot
 * layer, and it holds bytes only: nothing of an old application layer stays reachable through it.
 */
final class DevHandoverStore implements DevHandover {

    private final Map<String, byte[]> values = new HashMap<>();
    private boolean exporting;

    /** The stop that starts hands state over: {@link #put} accepts it until {@link #endExport()}. */
    synchronized void beginExport() {
        exporting = true;
    }

    /** The stop ended, whether it went well or not. */
    synchronized void endExport() {
        exporting = false;
    }

    /**
     * Drops what the new boot did not take.
     *
     * @return the keys dropped, sorted
     */
    synchronized Set<String> clear() {
        Set<String> dropped = new TreeSet<>(values.keySet());
        values.clear();
        return dropped;
    }

    @Override
    public synchronized boolean exporting() {
        return exporting;
    }

    @Override
    public synchronized void put(String key, byte[] value) {
        if (!exporting) {
            throw new IllegalStateException("The dev handover takes state only during the stop of a swap that keeps "
                    + "the HTTP sessions: " + key);
        }
        values.put(key, value.clone());
    }

    @Override
    public synchronized Optional<byte[]> take(String key) {
        return Optional.ofNullable(values.remove(key));
    }
}
