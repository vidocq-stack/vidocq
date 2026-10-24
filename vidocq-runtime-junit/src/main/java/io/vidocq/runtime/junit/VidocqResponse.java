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
package io.vidocq.runtime.junit;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the application answered to a {@link VidocqApplication} request.
 *
 * @param status  the HTTP status code
 * @param body    the body, decoded as UTF-8
 * @param headers the headers, by lower-case name
 */
public record VidocqResponse(int status, String body, Map<String, List<String>> headers) {

    public VidocqResponse {
        headers = Map.copyOf(headers);
    }

    /** The first value of the header {@code name}, whatever its case. */
    public Optional<String> header(String name) {
        List<String> values = headers.get(name.toLowerCase(java.util.Locale.ROOT));
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }
}
