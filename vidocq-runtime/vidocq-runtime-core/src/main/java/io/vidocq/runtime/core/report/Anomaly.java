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
package io.vidocq.runtime.core.report;

import java.util.Objects;

/**
 * Something wrong that did not stop the boot, logged as its own WARNING record when it was detected and
 * recalled by the {@code anomalies} section of the report.
 *
 * @param code    its stable code, such as {@code VIDOCQ-CFG-003}
 * @param message what is broken and its probable cause
 * @param hint    how to fix it, or {@code null}
 * @param source  who reported it: {@value StartupRecorder#CORE}, or the id of a contributor
 */
public record Anomaly(String code, String message, String hint, String source) {

    public Anomaly {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(source, "source");
    }
}
