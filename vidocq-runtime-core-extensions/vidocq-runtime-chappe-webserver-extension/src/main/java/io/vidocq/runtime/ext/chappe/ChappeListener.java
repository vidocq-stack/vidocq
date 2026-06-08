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
package io.vidocq.runtime.ext.chappe;

import java.util.Objects;

/**
 * Description of a Chappe listener (a host:port pair, with or without TLS).
 * <p>
 * Chappe allocates one {@link io.vidocq.chappe.api.Server} instance per listener.
 * Contributing extensions identify the listener by its {@link #name()}.
 * </p>
 *
 * @param name logical name (e.g. {@code default}, {@code admin})
 * @param host listening host
 * @param port listening port
 * @param tls {@code true} to enable TLS (reserved — not implemented in this milestone)
 */
public record ChappeListener(String name, String host, int port, boolean tls) {

    /** Default listener name. */
    public static final String DEFAULT = "default";

    public ChappeListener {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(host, "host");
        if (name.isBlank()) {
            throw new IllegalArgumentException("listener name must not be blank");
        }
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("invalid port: " + port);
        }
    }

    /** Simple HTTP listener. */
    public static ChappeListener http(String name, String host, int port) {
        return new ChappeListener(name, host, port, false);
    }
}
