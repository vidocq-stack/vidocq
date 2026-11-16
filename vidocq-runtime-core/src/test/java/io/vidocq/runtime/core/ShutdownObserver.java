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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Shutdown;

/**
 * An application bean of {@link ProgrammaticShutdownTest}: it asks for the shutdown from the CDI {@code Shutdown}
 * event, which the container fires while the stop runs. A top-level class, like {@link CheckpointObservers}.
 */
@ApplicationScoped
public class ShutdownObserver {

    void onShutdown(@Observes Shutdown shutdown) {
        ProgrammaticShutdownTest.OBSERVED.incrementAndGet();
        Vidocq.shutdownAndWait(); // the thread is already stopping: must return, not wait for itself
    }
}
