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

import java.util.List;
import java.util.Objects;

/**
 * One section of a {@link StartupReportView}, such as {@code layer} or a contributor's {@code mcp}, as the
 * detailed report prints it:
 *
 * <pre>
 * layer         4 modules (boot-layer detection from com.acme.app)      id, headline
 *   com.acme.app  app/target/classes  directory                         a table row
 *   weaving     none                                                    a row
 * </pre>
 *
 * @param id       the name of the section, printed first, such as {@code layer}; never {@code null}
 * @param headline what follows the id on the first line of the section in the detailed report, or
 *                 {@code null} for the id alone
 * @param summary  the value of the one line the section has in the summary report, or {@code null} when
 *                 it has none there
 * @param lines    the lines under the first one in the detailed report, in order; never {@code null}, an
 *                 immutable copy
 */
public record ReportSection(String id, String headline, String summary, List<ReportLine> lines) {

    public ReportSection {
        Objects.requireNonNull(id, "id");
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
