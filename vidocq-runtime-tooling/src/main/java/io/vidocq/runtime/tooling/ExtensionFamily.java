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
package io.vidocq.runtime.tooling;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The family an extension belongs to, which its descriptor names with the {@code family} key, and under which
 * {@code vidocq extension list --available} lists it (Vidocq/vidocq#353). The order of the constants is the order
 * of the listing.
 */
public enum ExtensionFamily {

    /** The Jakarta EE specifications an application programs against: REST, Expression Language, naming. */
    JAKARTA_EE("jakarta-ee", "Jakarta EE"),
    /** The MicroProfile specifications: config, health, metrics, telemetry, fault tolerance, JWT, OpenAPI. */
    MICROPROFILE("microprofile", "MicroProfile"),
    /** Databases: Jakarta Data, the connection pool, transactions, schema migrations. */
    DATA("data", "Data"),
    /** Serving HTTP and pages: the web server, Servlet, Pages, Vaadin. */
    WEB("web", "Web");

    private final String id;
    private final String title;

    ExtensionFamily(String id, String title) {
        this.id = id;
        this.title = title;
    }

    /** The value of a descriptor's {@code family} key, e.g. {@code jakarta-ee}. */
    public String id() {
        return id;
    }

    /** The heading of the family in a listing, e.g. {@code Jakarta EE}. */
    public String title() {
        return title;
    }

    /** The family a descriptor names. */
    public static ExtensionFamily of(String id) {
        return Arrays.stream(values())
                .filter(f -> f.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown extension family '" + id + "': one of "
                        + Arrays.stream(values()).map(ExtensionFamily::id).collect(Collectors.joining(", "))));
    }
}
