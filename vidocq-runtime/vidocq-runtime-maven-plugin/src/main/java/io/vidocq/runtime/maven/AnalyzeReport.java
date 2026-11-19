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

import io.vidocq.runtime.maven.depgen.DependencyCodegenCache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The text {@code vidocq:analyze-deps} prints: per concerned dependency, what it is and what generate does. */
final class AnalyzeReport {

    private AnalyzeReport() {}

    /**
     * What the bricks decide for one dependency jar, as {@code vidocq:generate} would.
     *
     * @param text the rest of the line after {@code <artifactId>: }
     */
    record BrickLine(String artifactId, String text) {
    }

    static String render(List<ScanSelection.Decision> decisions) {
        return render(decisions, List.of());
    }

    /**
     * The bricks' decision for each jar of {@code selections} ({@link DependencyCodegenPass#select}), with what the
     * cache holds for it. Reads the cache, never runs a processor.
     *
     * @param classpath the project's dependencies as {@code groupId:artifactId:version}, for the cache key
     */
    static List<BrickLine> brickDecisions(List<DependencyCodegenPass.Selection> selections,
                                          DependencyCodegenCache cache, int release, List<String> classpath)
            throws IOException {
        List<BrickLine> lines = new ArrayList<>();
        for (DependencyCodegenPass.Selection selection : selections) {
            List<String> parts = brickParts(selection);
            if (!parts.isEmpty()) {
                lines.add(new BrickLine(selection.dependency().artifactId(),
                        brickText(parts, selection, cache, release, classpath)));
            }
        }
        return lines;
    }

    /** The matching and the skipped bricks of one selection, empty when no brick concerns the jar. */
    private static List<String> brickParts(DependencyCodegenPass.Selection selection) {
        var decision = selection.decision();
        List<String> parts = new ArrayList<>();
        for (var match : decision.matches()) {
            parts.add(match.brick().brick() + " (" + match.reason() + ")");
        }
        for (var skip : decision.skips()) {
            parts.add(skip.brick().brick() + " skipped (marker " + skip.marker() + ")");
        }
        return parts;
    }

    /** The line text: the brick parts, then for a selected jar its signature note and what the cache holds. */
    private static String brickText(List<String> parts, DependencyCodegenPass.Selection selection,
                                    DependencyCodegenCache cache, int release, List<String> classpath)
            throws IOException {
        var decision = selection.decision();
        StringBuilder text = new StringBuilder(String.join(", ", parts));
        if (!decision.selected()) {
            return text.toString();
        }
        if (decision.signed()) {
            text.append(", signed: processed only when named in scanDependencies");
        }
        var cached = cache.peek(DependencyCodegenCache.key(selection.dependency().jar(), selection.bricks(),
                release, classpath));
        if (cached.isPresent()) {
            long classes = cached.get().files().keySet().stream().filter(n -> n.endsWith(".class")).count();
            text.append(", cached (").append(classes).append(classes == 1 ? " class)" : " classes)");
        }
        return text.toString();
    }

    static String render(List<ScanSelection.Decision> decisions, List<BrickLine> bricks) {
        StringBuilder out = new StringBuilder("Dependencies vidocq:generate scans for CDI beans:\n");
        int others = 0;
        StringBuilder block = new StringBuilder();
        for (ScanSelection.Decision d : decisions) {
            if (d.source() == null && !d.facts().beansXml()) {
                others++;
            } else {
                appendDecision(out, block, d);
            }
        }
        appendOthers(out, others);
        if (!block.isEmpty()) {
            out.append("\nTo make the detected bean archives explicit:\n<scanDependencies>\n").append(block)
                    .append("</scanDependencies>\n");
        }
        if (!bricks.isEmpty()) {
            out.append("Brick codegen:\n");
            bricks.forEach(b -> out.append("  ").append(b.artifactId()).append(": ").append(b.text()).append('\n'));
        }
        return out.toString();
    }

    /** The two lines of one concerned dependency; a jar scanned by detection also adds its line to {@code block}. */
    private static void appendDecision(StringBuilder out, StringBuilder block, ScanSelection.Decision d) {
        ScanSelection.JarFacts f = d.facts();
        out.append("  ").append(d.dependency().coordinates()).append(" — ")
                .append(f.beansXml() ? "bean archive (" + f.discoveryMode() + ")" : "not a bean archive")
                .append(", ").append(f.automatic() ? "automatic module" : "explicit module")
                .append(f.signed() ? ", signed" : "").append('\n');
        out.append("    ");
        if (d.selected()) {
            out.append("scanned: ").append(d.sourceLabel()).append(" → ")
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

    /** The summary line for the dependencies that are neither bean archives nor named. */
    private static void appendOthers(StringBuilder out, int others) {
        if (others == 1) {
            out.append("1 other dependency is not a bean archive and is not named.\n");
        } else if (others > 1) {
            out.append(others).append(" other dependencies are not bean archives and are not named.\n");
        }
    }
}
