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
package io.vidocq.runtime.extensions.jakartaee.core.cassini.live;

import java.util.ArrayList;
import java.util.List;

/**
 * The Cassini stacks mounted during this boot, for the {@code rest} section of the startup report and, since
 * Vidocq/vidocq#143, for the {@code rest} live panel of the {@code -dev} companion. Filled from two places that
 * share no instance: the runtime extension's {@code onStart} for the automatic mount, and its
 * {@code MountHandlerProvider}, which Chappe loads as a service, for each {@code type=restful} mount.
 *
 * <p>A static field outlives a dev reload, since this module stays in the boot layer while the application is loaded
 * again: it holds values only, never a {@link Class} of the application, and the runtime extension's {@code onStop}
 * clears it. Readers see an immutable list, replaced as a whole.
 */
public final class RestMounts {

    private static volatile List<RestMount> mounts = List.of();

    private RestMounts() {}

    /** Adds a mount, after those already recorded. */
    public static synchronized void record(RestMount mount) {
        List<RestMount> next = new ArrayList<>(mounts);
        next.add(mount);
        mounts = List.copyOf(next);
    }

    /** The mounts of this boot, in the order they were made. */
    public static List<RestMount> all() {
        return mounts;
    }

    /** Forgets every mount, before the application is stopped or loaded again. */
    public static synchronized void clear() {
        mounts = List.of();
    }
}
