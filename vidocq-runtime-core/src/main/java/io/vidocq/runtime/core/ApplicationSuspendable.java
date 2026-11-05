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

import io.vidocq.runtime.spi.Suspendable;
import io.vidocq.runtime.spi.event.Checkpoint;
import io.vidocq.runtime.spi.event.Restore;
import io.vidocq.vauban.core.container.VaubanContainer;

import java.util.function.Supplier;

/**
 * The application's part in a process snapshot: {@link #suspend()} fires {@link Checkpoint} to its CDI observers,
 * {@link #resume()} fires {@link Restore}. Placed at {@link Suspendable#APPLICATION_PRIORITY} among the extensions.
 * Nothing to fire before the container is built.
 */
final class ApplicationSuspendable implements Suspendable {

    private final Supplier<VaubanContainer> container;

    ApplicationSuspendable(Supplier<VaubanContainer> container) {
        this.container = container;
    }

    @Override
    public void suspend() {
        fire(new Checkpoint());
    }

    @Override
    public void resume() {
        fire(new Restore());
    }

    private void fire(Object event) {
        VaubanContainer current = container.get();
        if (current != null) {
            current.getBeanManager().getEvent().fire(event);
        }
    }
}
