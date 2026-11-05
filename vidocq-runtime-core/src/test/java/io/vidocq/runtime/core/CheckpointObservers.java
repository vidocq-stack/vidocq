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

import io.vidocq.runtime.spi.event.Checkpoint;
import io.vidocq.runtime.spi.event.Restore;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * An application bean of {@link ApplicationCheckpointEventsTest}: its observers, ordered by {@code @Priority}. A
 * top-level class: a nested one with package-private observers does not compile in the generated provider until
 * Vidocq/vauban#278 (VAU-APT-009).
 */
@ApplicationScoped
public class CheckpointObservers {

    void first(@Observes @Priority(10) Checkpoint checkpoint) {
        ApplicationCheckpointEventsTest.EVENTS.add("application checkpoint 10");
    }

    void second(@Observes @Priority(20) Checkpoint checkpoint) {
        ApplicationCheckpointEventsTest.EVENTS.add("application checkpoint 20");
    }

    void restored(@Observes Restore restore) {
        ApplicationCheckpointEventsTest.EVENTS.add("application restore");
    }
}
