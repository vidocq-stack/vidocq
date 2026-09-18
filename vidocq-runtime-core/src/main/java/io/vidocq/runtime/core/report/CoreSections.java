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
import java.util.function.UnaryOperator;

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
     * @param module       where its class comes from: {@code io.vidocq.chappe (boot layer)}, {@code class path}
     * @param namespaces   the configuration namespaces it declares, {@code vidocq.chappe.*}
     * @param onStartNanos how long its {@code onStart} took, or {@code -1} when it did not run
     * @param failed       whether its {@code onStart} threw, which failed the boot
     */
    public record Extension(String name, int priority, String module, List<String> namespaces, long onStartNanos,
                            boolean failed) {

        public Extension {
            namespaces = namespaces == null ? List.of() : List.copyOf(namespaces);
        }
    }

    /**
     * The {@value #LAYER} section: how many modules the application layer has and how it was found; in the
     * detailed report, one line per module (name, archive, kind), then the load-time weaving.
     *
     * @param origin      what chose the archives, {@code vidocq.app.path} or
     *                    {@code boot-layer detection from com.acme.app}, or {@code null} without an application
     *                    layer
     * @param originPaths the archives to print after {@code origin=}, as {@code vidocq.app.path} lists them;
     *                    empty when the origin says it all
     * @param modules     the modules, the application's first
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
        return new Section(LAYER, count + " (" + origin(origin, originPaths, paths::detailed) + ")",
                count + " (" + origin(origin, originPaths, paths::summary) + ")", lines, -1);
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
     * extension (priority, name, {@code onStart} duration, or {@code onStart failed}, or {@code not started},
     * module, declared namespaces).
     */
    public static Section extensions(List<Extension> extensions) {
        if (extensions.isEmpty()) {
            return new Section(EXTENSIONS, "none", "none", List.of(), -1);
        }
        List<String> names = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        for (Extension extension : extensions) {
            names.add(extension.name());
            String onStart = extension.failed() ? "onStart failed"
                    : extension.onStartNanos() < 0 ? "not started"
                    : "onStart " + extension.onStartNanos() / 1_000_000 + " ms";
            lines.add(new Cells(List.of(String.valueOf(extension.priority()), extension.name(), onStart,
                    extension.module(), String.join(", ", extension.namespaces()))));
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

    private static String origin(String origin, List<String> paths, UnaryOperator<String> shown) {
        if (paths.isEmpty()) {
            return origin;
        }
        List<String> archives = new ArrayList<>();
        for (String path : paths) {
            archives.add(shown.apply(path));
        }
        return origin + "=" + String.join(File.pathSeparator, archives);
    }

    private static String count(int count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }
}
