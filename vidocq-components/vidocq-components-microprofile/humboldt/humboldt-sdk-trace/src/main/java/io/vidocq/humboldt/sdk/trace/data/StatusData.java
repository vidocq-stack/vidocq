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
package io.vidocq.humboldt.sdk.trace.data;

import io.opentelemetry.api.trace.StatusCode;

/**
 * Span status — code + optional description.
 *
 * @param code        OTel status code (UNSET / OK / ERROR)
 * @param description status description (may be {@code null} or empty)
 */
public record StatusData(StatusCode code, String description) {

    private static final StatusData UNSET = new StatusData(StatusCode.UNSET, "");
    private static final StatusData OK = new StatusData(StatusCode.OK, "");

    public StatusData {
        if (code == null) throw new NullPointerException("code");
        if (description == null) description = "";
    }

    public static StatusData unset() {
        return UNSET;
    }

    public static StatusData ok() {
        return OK;
    }

    public static StatusData error(String description) {
        return new StatusData(StatusCode.ERROR, description);
    }
}
