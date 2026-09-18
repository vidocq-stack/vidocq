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
package io.vidocq.runtime.spi.report;

import java.util.Objects;

/**
 * One anomaly of a {@link StartupReportView}: something wrong that did not stop the boot, as Vidocq
 * logged it in its own WARNING record and recalls it in the report's {@code anomalies} section.
 *
 * @param code    its stable code, such as {@code VIDOCQ-CFG-003}; never {@code null}
 * @param message what is broken and its probable cause; never {@code null}
 * @param hint    how to fix it, or {@code null}
 * @param source  who reported it: {@code core} for Vidocq itself, or the {@link StartupReportContributor#id() id}
 *                of a contributor; never {@code null}
 */
public record ReportAnomaly(String code, String message, String hint, String source) {

    public ReportAnomaly {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(source, "source");
    }
}
