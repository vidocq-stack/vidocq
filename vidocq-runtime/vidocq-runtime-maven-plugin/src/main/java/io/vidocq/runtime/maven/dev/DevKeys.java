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

import io.vidocq.runtime.maven.dev.TestResults.Trigger;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The keys typed in the console under {@code vidocq:dev}: one reader of the console for the whole goal, which each
 * session points at its own continuous testing. A session that restarts on new dependencies (Vidocq/vidocq#799)
 * starts no second reader.
 */
final class DevKeys {

    private final Predicate<Consumer<Trigger>> start;
    private final AtomicReference<Consumer<Trigger>> target = new AtomicReference<>();
    private Boolean read;

    /**
     * @param start starts reading the keys, sending each to the consumer it gets; {@code false} when no console is
     *              there to read: {@link ContinuousTesting#listenToDevKeys} in production
     */
    DevKeys(Predicate<Consumer<Trigger>> start) {
        this.start = start;
    }

    /**
     * Sends the keys to {@code request} from now on; starts the reader the first time.
     *
     * @return whether the keys are read
     */
    synchronized boolean listen(Consumer<Trigger> request) {
        target.set(request);
        if (read == null) {
            read = start.test(trigger -> target.get().accept(trigger));
        }
        return read;
    }
}
