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

import java.util.ArrayList;
import java.util.List;

/** Records every report as {@code kind: message (detail)}. */
final class RecordingReporter implements Reporter {

    final List<String> lines = new ArrayList<>();

    @Override
    public void success(String message, String detail) {
        lines.add("success: " + message + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    @Override
    public void note(String message) {
        lines.add("note: " + message);
    }

    @Override
    public void warning(String message) {
        lines.add("warning: " + message);
    }

    @Override
    public void error(String message) {
        lines.add("error: " + message);
    }
}
