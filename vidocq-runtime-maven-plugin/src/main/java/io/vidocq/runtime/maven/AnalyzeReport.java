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
package io.vidocq.runtime.maven;

import java.util.List;

/** The text {@code vidocq:analyze-deps} prints: per concerned dependency, what it is and what generate does. */
final class AnalyzeReport {

    private AnalyzeReport() {}

    static String render(List<ScanSelection.Decision> decisions) {
        StringBuilder out = new StringBuilder("Dependencies vidocq:generate scans for CDI beans:\n");
        int others = 0;
        StringBuilder block = new StringBuilder();
        for (ScanSelection.Decision d : decisions) {
            ScanSelection.JarFacts f = d.facts();
            if (d.source() == null && !f.beansXml()) {
                others++;
                continue;
            }
            out.append("  ").append(d.dependency().coordinates()).append(" — ")
                    .append(f.beansXml() ? "bean archive (" + f.discoveryMode() + ")" : "not a bean archive")
                    .append(", ").append(f.automatic() ? "automatic module" : "explicit module")
                    .append(f.signed() ? ", signed" : "").append('\n');
            out.append("    ");
            if (d.selected()) {
                out.append("scanned: ").append(d.source().label()).append(" → ")
                        .append(f.automatic() ? "open module synthesized, then enriched copy" : "enriched copy");
                if (d.source() == ScanSelection.Source.AUTOMATIC) {
                    block.append("    <scanDependency>").append(d.dependency().groupId()).append(':')
                            .append(d.dependency().artifactId()).append("</scanDependency>\n");
                }
            } else if (d.excludedBecause() != null) {
                out.append("not scanned: ").append(d.excludedBecause());
            } else {
                out.append("not scanned: discovery mode none, or the bean-archive detection is off");
            }
            out.append('\n');
        }
        if (others == 1) {
            out.append("1 other dependency is not a bean archive and is not named.\n");
        } else if (others > 1) {
            out.append(others).append(" other dependencies are not bean archives and are not named.\n");
        }
        if (!block.isEmpty()) {
            out.append("\nTo make the detected bean archives explicit:\n<scanDependencies>\n").append(block)
                    .append("</scanDependencies>\n");
        }
        return out.toString();
    }
}
