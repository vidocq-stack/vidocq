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
package io.vidocq.runtime.extensions.jakartaee.web.foy.live;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The servlet deployments of each live boot, keyed by the boot's container, for the {@code servlet}
 * live panel of the {@code -dev} companion. Two live boots (parallel tests, a reload that starts the
 * new boot before stopping the old) never see each other's; the extension forgets a boot when it stops.
 */
public final class ServletMounts {

    private static final Map<Object, List<ServletMount>> BY_BOOT = new IdentityHashMap<>();

    private ServletMounts() {}

    /** Adds a mount of {@code boot}, after those already recorded. */
    public static synchronized void record(Object boot, ServletMount mount) {
        BY_BOOT.computeIfAbsent(boot, b -> new ArrayList<>()).add(mount);
    }

    /** The mounts of {@code boot}, in the order they were made: an unmodifiable copy. */
    public static synchronized List<ServletMount> of(Object boot) {
        return List.copyOf(BY_BOOT.getOrDefault(boot, List.of()));
    }

    /** Forgets the mounts of a stopped boot. */
    public static synchronized void forget(Object boot) {
        BY_BOOT.remove(boot);
    }
}
