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
package io.vidocq.runtime.devservices.spi;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a {@link DevService} started, as the host reports it to the application: its id, the image it ran, the
 * addresses it exposes and the keys it injected. Plain values, written to the dev services state file.
 *
 * @param id           the provider's {@link DevService#id() id}
 * @param image        the container image, or {@code null} when the service is not a container
 * @param endpoints    what a person can reach, by name: {@code "default" -> "localhost:54321"}, or a URL
 * @param injectedKeys the keys {@link DevService#start} returned, sorted
 */
public record DevServiceState(String id, String image, Map<String, String> endpoints, List<String> injectedKeys) {

    public DevServiceState {
        Objects.requireNonNull(id, "id");
        endpoints =
                endpoints == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(endpoints));
        injectedKeys = injectedKeys == null ? List.of() : injectedKeys.stream().sorted().toList();
    }

    /** The state of a provider that says nothing about itself: its id and its keys. */
    public static DevServiceState minimal(String id, Collection<String> injectedKeys) {
        return new DevServiceState(id, null, Map.of(), List.copyOf(injectedKeys));
    }
}
