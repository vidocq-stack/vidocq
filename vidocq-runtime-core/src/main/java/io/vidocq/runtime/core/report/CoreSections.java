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

import io.vidocq.runtime.core.report.Section.Cells;
import io.vidocq.runtime.core.report.Section.Items;
import io.vidocq.runtime.core.report.Section.Line;
import io.vidocq.runtime.core.report.Section.Row;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The sections the core writes itself, {@code layer}, {@code configuration} and {@code extensions}, from
 * facts already read into strings and numbers. What must never be printed never gets here: no configuration
 * value, no system property, no absolute path in a summary.
 */
public final class CoreSections {

    /** The id of the application layer section. */
    public static final String LAYER = "layer";
    /** The id of the configuration section. */
    public static final String CONFIGURATION = "configuration";
    /** The id of the extensions section. */
    public static final String EXTENSIONS = "extensions";
    /** The layer of an extension of the boot layer, which the extensions section names only when not all are. */
    public static final String BOOT_LAYER = "boot layer";
    /** The layer of an extension of the application layer. */
    public static final String APPLICATION_LAYER = "application layer";

    private CoreSections() {}

    /**
     * One module of the application layer.
     *
     * @param name     the module name
     * @param location the absolute path of its archive, or {@code null} when it has no file
     * @param kind     {@code directory}, {@code jar}, or the extension of another file
     */
    public record LayerModule(String name, String location, String kind) {}

    /**
     * One extension, in the order it started.
     *
     * @param name         its name
     * @param priority     its priority
     * @param module       the module of its class, {@code io.vidocq.chappe}, or {@code class path}
     * @param layer        the layer of that module, {@value CoreSections#BOOT_LAYER} or
     *                     {@value CoreSections#APPLICATION_LAYER}, or {@code null} for the class path or another
     *                     layer
     * @param namespaces   the configuration namespaces it declares, {@code vidocq.chappe.*}
     * @param onStartNanos how long its {@code onStart} took, or {@code -1} when it did not run
     * @param failed       whether its {@code onStart} threw, which failed the boot
     */
    public record Extension(String name, int priority, String module, String layer, List<String> namespaces,
                            long onStartNanos, boolean failed) {

        public Extension {
            namespaces = namespaces == null ? List.of() : List.copyOf(namespaces);
        }
    }

    /**
     * The {@value #LAYER} section: how many modules the application layer has and how it was found; in the
     * detailed report, one line per module (name, archive, kind), then the load-time weaving.
     *
     * <p>The archives {@code vidocq.app.path} lists are named in the summary, by file name only:
     * {@code 2 modules (vidocq.app.path=.../app:.../lib.jar)}. The detailed headline only counts them,
     * {@code 2 modules (vidocq.app.path, 2 archives)}: the table under it prints every archive, and a headline
     * naming several paths would be cut in the middle of a file name.
     *
     * @param origin      what chose the archives, {@code vidocq.app.path} or
     *                    {@code boot-layer detection from com.acme.app}, or {@code null} without an application
     *                    layer
     * @param originPaths the archives {@code vidocq.app.path} lists, in its order; empty when the origin says it
     *                    all
     * @param modules     the modules, the application's first, then the others in the order of their archives
     * @param weaving     what the load-time weaving did, or {@code null}
     * @param paths       how files are written
     */
    public static Section layer(String origin, List<String> originPaths, List<LayerModule> modules, String weaving,
                                DisplayPaths paths) {
        List<Line> lines = new ArrayList<>();
        for (LayerModule module : modules) {
            lines.add(new Cells(List.of(module.name(),
                    module.location() == null ? "-" : paths.detailed(module.location()), module.kind())));
        }
        if (weaving != null) {
            lines.add(new Row("weaving", weaving));
        }
        if (origin == null) {
            return new Section(LAYER, "no application layer", "no application layer", lines, -1);
        }
        String count = count(modules.size(), "module", "modules");
        String detailed = originPaths.isEmpty() ? origin
                : origin + ", " + count(originPaths.size(), "archive", "archives");
        return new Section(LAYER, count + " (" + detailed + ")",
                count + " (" + summaryOrigin(origin, originPaths, paths) + ")", lines, -1);
    }

    /**
     * The {@value #CONFIGURATION} section, detailed report only: the sources in lookup order, the provider that
     * replaced the native ones, the audited namespaces. Never a value.
     *
     * @param sources   {@code <name> <ordinal>} per source, in lookup order
     * @param providers the configuration source providers whose sources replaced the native ones, if any
     * @param audited   the audited namespaces, {@code vidocq.startup.*}
     */
    public static Section configuration(List<String> sources, List<String> providers, List<String> audited) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Items("sources", sources));
        if (!providers.isEmpty()) {
            lines.add(new Row("provider", String.join(", ", providers) + " (replaces the native sources)"));
        }
        lines.add(new Items("audited", audited));
        return new Section(CONFIGURATION, null, null, lines, -1);
    }

    /**
     * The {@value #EXTENSIONS} section: their names in the summary report; in the detailed one, one line per
     * extension: its priority, its name, its {@code onStart} duration ({@code onStart failed}, {@code not started}),
     * then its module followed by the namespaces it declares.
     *
     * <pre>
     *   100    chappe-engine     onStart 0 ms  io.vidocq.runtime.extensions.essentials.chappe
     *   10000  chappe-bootstrap  onStart 7 ms  io.vidocq.runtime.extensions.essentials.chappe  vidocq.chappe.*
     * </pre>
     *
     * <p>The layer of the module, {@code (boot layer)} or {@code (application layer)}, follows it only when the
     * extensions are not all in the boot layer, where they usually are. The module and the namespaces make the
     * last column, which is not padded: the few extensions that declare namespaces do not widen every line.
     *
     * <p>The detailed sample of the report's design (Vidocq/vidocq#84) shows three columns, priority, name and
     * {@code onStart}; the module, its layer and the namespaces come from the design's table of what each section
     * shows (section 2.3 of its specification: priority, module, layer, declared namespaces, {@code onStart}
     * duration), which the sample leaves out.
     */
    public static Section extensions(List<Extension> extensions) {
        if (extensions.isEmpty()) {
            return new Section(EXTENSIONS, "none", "none", List.of(), -1);
        }
        boolean allInBootLayer = extensions.stream().allMatch(extension -> BOOT_LAYER.equals(extension.layer()));
        List<String> names = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        for (Extension extension : extensions) {
            names.add(extension.name());
            String onStart = extension.failed() ? "onStart failed"
                    : extension.onStartNanos() < 0 ? "not started"
                    : "onStart " + extension.onStartNanos() / 1_000_000 + " ms";
            String where = extension.layer() == null || allInBootLayer ? extension.module()
                    : extension.module() + " (" + extension.layer() + ")";
            if (!extension.namespaces().isEmpty()) {
                where += " ".repeat(StartupReportRenderer.GAP) + String.join(", ", extension.namespaces());
            }
            lines.add(new Cells(List.of(String.valueOf(extension.priority()), extension.name(), onStart, where)));
        }
        return new Section(EXTENSIONS, null, String.join(", ", StartupReportRenderer.capped(names)), lines, -1);
    }

    /**
     * What the load-time weaving did: {@code none}, {@code failed (VAUBAN-009)}, or who weaves the planned
     * beans, {@code Vauban class loader, 1 planned bean: McpToolDiscovery}.
     *
     * @param planned     the binary names of the beans woven at load time
     * @param failed      whether weaving was needed and could not be prepared
     * @param layerLoader whether the Vauban class loader of the application layer weaves them, rather than the
     *                    agent
     */
    public static String weaving(Collection<String> planned, boolean failed, boolean layerLoader) {
        if (failed) {
            return "failed (" + StartupAnomalies.WEAVING_FAILED + ")";
        }
        if (planned.isEmpty()) {
            return "none";
        }
        List<String> names = new ArrayList<>();
        for (String name : planned) {
            names.add(name.substring(name.lastIndexOf('.') + 1));
        }
        return (layerLoader ? "Vauban class loader" : "load-time weaving agent") + ", "
                + count(names.size(), "planned bean", "planned beans") + ": "
                + String.join(", ", StartupReportRenderer.capped(names));
    }

    /** {@code 0.4.0-SNAPSHOT on Java 25+36-LTS}. */
    public static String runtime(String version, String java) {
        return (version == null ? "version unknown" : version) + " on Java " + java;
    }

    /**
     * The launch reason as the header prints it: the configuration key that forced the mode as it is, the
     * signal the detection found after {@code auto: }.
     *
     * @param reason     the reason of the resolution, or {@code null} when the launch could not be read
     * @param configured whether {@code vidocq.launch.mode} set the mode
     */
    public static String launchReason(String reason, boolean configured) {
        if (reason == null) {
            return "auto: launch not read";
        }
        return configured ? reason : "auto: " + reason;
    }

    /** {@code origin}, then {@code =} and the file names of the archives it lists, if any. */
    private static String summaryOrigin(String origin, List<String> paths, DisplayPaths shown) {
        if (paths.isEmpty()) {
            return origin;
        }
        List<String> archives = new ArrayList<>();
        for (String path : paths) {
            archives.add(shown.summary(path));
        }
        return origin + "=" + String.join(File.pathSeparator, archives);
    }

    private static String count(int count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }
}
