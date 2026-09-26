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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.live;

import io.vidocq.mansart.pool.core.MansartDataSource;

import java.util.List;

/**
 * The pools the Mansart extension opened, for its -dev panel only (Vidocq/vidocq#143): published when the pools are
 * open, cleared first when they close, so that a dev reload never shows the previous boot's pools.
 */
public final class MansartPoolsLive {

    /** An open pool and its label, such as {@code @Default}. */
    public record Pool(String label, MansartDataSource pool) {}

    private static volatile List<Pool> pools = List.of();

    private MansartPoolsLive() {}

    public static List<Pool> pools() {
        return pools;
    }

    public static void publish(List<Pool> opened) {
        pools = List.copyOf(opened);
    }

    public static void clear() {
        pools = List.of();
    }
}
