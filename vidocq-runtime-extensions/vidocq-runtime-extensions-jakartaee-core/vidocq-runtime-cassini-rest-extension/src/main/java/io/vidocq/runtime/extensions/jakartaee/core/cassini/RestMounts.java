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
package io.vidocq.runtime.extensions.jakartaee.core.cassini;

import java.util.ArrayList;
import java.util.List;

/**
 * The Cassini stacks mounted during this boot, for the {@code rest} section of the startup report. Filled from two
 * places that share no instance: {@link CassiniExtension#onStart} for the automatic mount, and
 * {@link CassiniMountHandlerProvider}, which Chappe loads as a service, for each {@code type=restful} mount.
 *
 * <p>A static field outlives a dev reload, since this module stays in the boot layer while the application is loaded
 * again: it holds values only, never a {@link Class} of the application, and {@link CassiniExtension#onStop} clears it.
 * Readers see an immutable list, replaced as a whole.
 */
final class RestMounts {

    private static volatile List<RestMount> mounts = List.of();

    private RestMounts() {}

    /** Adds a mount, after those already recorded. */
    static synchronized void record(RestMount mount) {
        List<RestMount> next = new ArrayList<>(mounts);
        next.add(mount);
        mounts = List.copyOf(next);
    }

    /** The mounts of this boot, in the order they were made. */
    static List<RestMount> all() {
        return mounts;
    }

    /** Forgets every mount, before the application is stopped or loaded again. */
    static synchronized void clear() {
        mounts = List.of();
    }
}
