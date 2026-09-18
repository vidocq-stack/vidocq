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
package io.vidocq.runtime.core;

import io.vidocq.runtime.core.config.ConfigKeyAudit;
import io.vidocq.runtime.core.config.VidocqConfigImpl;
import io.vidocq.runtime.core.report.CoreSections;
import io.vidocq.runtime.core.report.DisplayPaths;
import io.vidocq.runtime.core.report.Section;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.config.ConfigSource;
import io.vidocq.runtime.spi.config.VidocqConfig;

import java.lang.module.ResolvedModule;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Reads what the core knows about a boot into the strings and numbers of its report sections: the
 * application layer, the configuration sources, the extensions. Only memory is read (the layer's resolved
 * configuration, the loaded extensions), never a file; nothing here throws, what cannot be read is left out.
 */
final class StartupFacts {

    private StartupFacts() {}

    /**
     * The {@code layer}, {@code configuration} and {@code extensions} sections, in that order.
     *
     * @param weaving      what the load-time weaving did, or {@code null} before it ran
     * @param config       the configuration, or {@code null} before it was loaded
     * @param extensions   the extensions, in the order they start
     * @param onStartNanos the duration of each {@code onStart} that returned, in order
     * @param failed       what failed the boot, such as {@code onStart chappe-bootstrap}, or {@code null}
     * @param paths        how files are written
     */
    static List<Section> coreSections(String weaving, VidocqConfig config, List<VidocqExtension> extensions,
                                      List<Long> onStartNanos, String failed, DisplayPaths paths) {
        return List.of(layer(weaving, paths), configuration(config, extensions, paths),
                extensions(extensions, onStartNanos, failed));
    }

    /** The application layer Vidocq installed, if any, its modules in the order of their archives. */
    static Section layer(String weaving, DisplayPaths paths) {
        VidocqAppLayer.Installation installation = VidocqAppLayer.installation();
        ModuleLayer layer = VidocqAppLayer.installedLayer();
        if (installation == null || layer == null) {
            return CoreSections.layer(null, List.of(), List.of(), weaving, paths);
        }
        return CoreSections.layer(installation.origin(), installation.listed() ? installation.archives() : List.of(),
                modules(layer, installation.archives()), weaving, paths);
    }

    /** The modules of {@code layer}: those of the given archives in their order, then the others by name. */
    static List<CoreSections.LayerModule> modules(ModuleLayer layer, List<String> archives) {
        List<Path> order = new ArrayList<>();
        for (String archive : archives) {
            order.add(absolute(archive));
        }
        record Placed(int index, CoreSections.LayerModule module) {}
        List<Placed> placed = new ArrayList<>();
        for (ResolvedModule resolved : layer.configuration().modules()) {
            URI location = resolved.reference().location().orElse(null);
            Path file = file(location);
            int index = file == null ? -1 : order.indexOf(file);
            String kind = file != null ? kind(location, file) : location == null ? "-" : location.getScheme();
            placed.add(new Placed(index < 0 ? Integer.MAX_VALUE : index,
                    new CoreSections.LayerModule(resolved.name(), file == null ? null : file.toString(), kind)));
        }
        placed.sort(Comparator.comparingInt(Placed::index).thenComparing(p -> p.module().name()));
        return placed.stream().map(Placed::module).toList();
    }

    /** The configuration sources in lookup order, the provider that replaced the native ones, the audit. */
    static Section configuration(VidocqConfig config, List<VidocqExtension> extensions, DisplayPaths paths) {
        List<String> sources = new ArrayList<>();
        List<String> providers = List.of();
        if (config != null) {
            try {
                for (ConfigSource source : config.getConfigSources()) {
                    sources.add(paths.scrub(source.getName()) + " " + source.getOrdinal());
                }
            } catch (RuntimeException unreadable) {
                sources.add("not readable");
            }
            if (config instanceof VidocqConfigImpl vidocq) {
                providers = vidocq.sourceProviders();
            }
        }
        List<String> audited;
        try {
            audited = ConfigKeyAudit.auditedNamespaces(VidocqBootstrap.declaredConfigKeys(extensions));
        } catch (RuntimeException unreadable) {
            // an extension whose configKeys() throws: the audit reported it as VIDOCQ-CFG-002
            audited = List.of();
        }
        return CoreSections.configuration(sources, providers, audited);
    }

    /**
     * The extensions in the order they start, with the duration of each {@code onStart} that returned; the
     * next one failed when the boot failed in its {@code onStart}.
     */
    static Section extensions(List<VidocqExtension> extensions, List<Long> onStartNanos, String failed) {
        List<CoreSections.Extension> facts = new ArrayList<>();
        for (int i = 0; i < extensions.size(); i++) {
            VidocqExtension extension = extensions.get(i);
            List<String> namespaces;
            try {
                namespaces = ConfigKeyAudit.auditedNamespaces(extension.configKeys());
            } catch (RuntimeException unreadable) {
                namespaces = List.of();
            }
            String name = VidocqBootstrap.nameOf(extension);
            facts.add(new CoreSections.Extension(name, priorityOf(extension), where(extension.getClass().getModule()),
                    namespaces, i < onStartNanos.size() ? onStartNanos.get(i) : -1,
                    i == onStartNanos.size() && ("onStart " + name).equals(failed)));
        }
        return CoreSections.extensions(facts);
    }

    /** {@code io.vidocq.chappe (boot layer)}, {@code com.acme (application layer)}, {@code class path}. */
    static String where(Module module) {
        if (!module.isNamed()) {
            return "class path";
        }
        ModuleLayer layer = module.getLayer();
        if (layer == ModuleLayer.boot()) {
            return module.getName() + " (boot layer)";
        }
        if (layer != null && layer == VidocqAppLayer.installedLayer()) {
            return module.getName() + " (application layer)";
        }
        return module.getName();
    }

    /** {@code directory} for a directory of classes, else the extension of the file: {@code jar}. */
    static String kind(URI location, Path file) {
        String path = location.getPath();
        if (path != null && path.endsWith("/")) {
            return "directory";
        }
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "directory";
    }

    private static int priorityOf(VidocqExtension extension) {
        try {
            return extension.priority();
        } catch (RuntimeException unreadable) {
            return 0;
        }
    }

    private static Path file(URI location) {
        if (location == null || !"file".equalsIgnoreCase(location.getScheme())) {
            return null;
        }
        try {
            return Path.of(location).normalize();
        } catch (RuntimeException notAFile) {
            return null;
        }
    }

    private static Path absolute(String archive) {
        try {
            return Path.of(archive).toAbsolutePath().normalize();
        } catch (RuntimeException invalid) {
            return null;
        }
    }
}
