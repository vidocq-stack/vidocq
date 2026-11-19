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
package io.vidocq.runtime.devservices.extension;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the dev services state file (spec §4.2) says, once {@link StateReader#parse parsed}: plain strings, nothing
 * this module resolves further. A secret's value is never in this snapshot — the host already left it out of the
 * file — only whether it is {@link Injected#configured() configured}.
 *
 * @param host       the host that wrote the file, such as {@code vidocq:dev}
 * @param state      {@code running} or {@code stopped}
 * @param startedAt  when the host started the services, as written
 * @param services   the services the host started, in file order
 * @param skipped    the providers that did not start and said why, in file order; empty for a file written before
 *                   the host kept them
 */
public record DevServicesSnapshot(String host, String state, String startedAt, List<Service> services,
        List<Skipped> skipped) {

    /** No state property, no file, or a missing file: not an anomaly, see {@link DevServicesSection}. */
    public static final DevServicesSnapshot NONE = new DevServicesSnapshot(null, null, null, List.of(), List.of());

    public DevServicesSnapshot {
        services = services == null ? List.of() : List.copyOf(services);
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
    }

    /** A snapshot with no skipped provider. */
    public DevServicesSnapshot(String host, String state, String startedAt, List<Service> services) {
        this(host, state, startedAt, services, List.of());
    }

    /**
     * One service the host started.
     *
     * @param id        the provider's id, such as {@code postgres}
     * @param image     the container image, or {@code null} when the service is not a container
     * @param endpoints what a person can reach, by name, in file order: a {@link LinkedHashMap}, since the section
     *                  reads its first entry as the one shown in the summary
     * @param injected  the keys this service injected, in file order
     */
    public record Service(String id, String image, Map<String, String> endpoints, List<Injected> injected) {

        public Service {
            endpoints = endpoints == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(endpoints));
            injected = injected == null ? List.of() : List.copyOf(injected);
        }
    }

    /**
     * One key a service injected.
     *
     * @param key        the configuration key, such as {@code vidocq.pool.url}
     * @param value      the value, or {@code null} when {@code configured} is {@code true}: a secret's value never
     *                   reaches this snapshot
     * @param configured whether {@code key} is a secret the host masked
     */
    public record Injected(String key, String value, boolean configured) {}

    /**
     * A provider the host did not start.
     *
     * @param id     the provider's id, such as {@code postgres}
     * @param reason why, one line with no secret; {@code null} only in a hand-edited file
     */
    public record Skipped(String id, String reason) {}
}
