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
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Reads what the core knows about a boot into the strings and numbers of its report sections: the
 * application layer, the configuration sources, the extensions. Memory is read (the layer's resolved
 * configuration, the loaded extensions), plus the manifest of each archive of the layer, only when the caller's
 * {@link #enrichedReader reader} is allowed to ({@link #enrichedReader(boolean, Function)}); nothing here throws,
 * what cannot be read is left out.
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
                                      List<Long> onStartNanos, String failed, DisplayPaths paths,
                                      Function<Path, List<String>> enriched) {
        return List.of(layer(weaving, paths, enriched), configuration(config, extensions, paths),
                extensions(extensions, onStartNanos, failed));
    }

    /**
     * The reader of the bricks that enriched an archive: {@code raw} behind a memory, so each archive is read at
     * most once per boot, or a reader that opens nothing when no report shows the layer ({@code shown} is false).
     */
    static Function<Path, List<String>> enrichedReader(boolean shown, Function<Path, List<String>> raw) {
        if (!shown) {
            return file -> List.of();
        }
        Map<Path, List<String>> read = new ConcurrentHashMap<>();
        return file -> read.computeIfAbsent(file, raw);
    }

    /**
     * The application layer Vidocq installed, if any: the application's module first, then the others in the order
     * of their archives, reading the bricks of each archive with {@code enriched} ({@link #enrichedReader}).
     */
    static Section layer(String weaving, DisplayPaths paths, Function<Path, List<String>> enriched) {
        VidocqAppLayer.Installation installation = VidocqAppLayer.installation();
        ModuleLayer layer = VidocqAppLayer.installedLayer();
        if (installation == null || layer == null) {
            return CoreSections.layer(null, List.of(), List.of(), weaving, paths);
        }
        String application = applicationName();
        List<CoreSections.LayerModule> modules = modules(layer, installation.archives(), application, enriched);
        return CoreSections.layer(installation.origin(), installation.listed() ? installation.archives() : List.of(),
                modules, libraries(modules, application), installation.pinned(), weaving, paths);
    }

    /** The modules of the layer other than the application's; 0 when the application is unknown. */
    static int libraries(List<CoreSections.LayerModule> modules, String application) {
        if (application == null) {
            return 0;
        }
        return (int) modules.stream().filter(m -> !m.name().equals(application)).count();
    }

    /**
     * The modules of {@code layer}: the application's first, then those of the given archives in their order, then
     * the others by name.
     *
     * @param application the name of the application module, or {@code null} when there is none to name
     */
    static List<CoreSections.LayerModule> modules(ModuleLayer layer, List<String> archives, String application,
                                                  Function<Path, List<String>> enriched) {
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
            int rank = resolved.name().equals(application) ? -1 : index < 0 ? Integer.MAX_VALUE : index;
            placed.add(new Placed(rank,
                    new CoreSections.LayerModule(resolved.name(), file == null ? null : file.toString(), kind,
                            file == null ? List.of() : enriched.apply(file))));
        }
        placed.sort(Comparator.comparingInt(Placed::index).thenComparing(p -> p.module().name()));
        return placed.stream().map(Placed::module).toList();
    }

    /**
     * The bricks named by the {@code Vidocq-Enriched-By} manifest attribute of {@code file}, empty for a directory,
     * a jar without it, or one that cannot be read. The attribute is the plugin's {@code EnrichedJars.ENRICHED_BY};
     * the core cannot depend on the plugin, so the literal is repeated here.
     */
    static List<String> enrichedBy(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(file.toFile())) {
            java.util.jar.Manifest manifest = jar.getManifest();
            String value = manifest == null ? null : manifest.getMainAttributes().getValue("Vidocq-Enriched-By");
            if (value == null || value.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(value.split(",")).map(String::strip).filter(b -> !b.isEmpty()).toList();
        } catch (IOException | RuntimeException _) {
            return List.of();
        }
    }

    /**
     * The name of the application module: the module of the class that called {@code Vidocq.run}, else that of
     * {@code vidocq.app.main}, else the module {@code vidocq.app.module} names, else the launched module
     * ({@link Vidocq#applicationModule()}); {@code null} when there is none.
     */
    private static String applicationName() {
        try {
            Module application = Vidocq.applicationModule();
            return application == null ? null : application.getName();
        } catch (RuntimeException | LinkageError _) {
            return null;
        }
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
            } catch (RuntimeException _) {
                sources.add("not readable");
            }
            if (config instanceof VidocqConfigImpl vidocq) {
                providers = vidocq.sourceProviders();
            }
        }
        List<String> audited;
        try {
            audited = ConfigKeyAudit.auditedNamespaces(VidocqBootstrap.declaredConfigKeys(extensions));
        } catch (RuntimeException _) {
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
            } catch (RuntimeException _) {
                namespaces = List.of();
            }
            String name = VidocqBootstrap.nameOf(extension);
            Module module = extension.getClass().getModule();
            facts.add(new CoreSections.Extension(name, priorityOf(extension), where(module), layerOf(module),
                    namespaces, i < onStartNanos.size() ? onStartNanos.get(i) : -1,
                    i == onStartNanos.size() && ("onStart " + name).equals(failed)));
        }
        return CoreSections.extensions(facts);
    }

    /** The name of {@code module}, {@code io.vidocq.chappe}, or {@code class path} for an unnamed module. */
    static String where(Module module) {
        return module.isNamed() ? module.getName() : "class path";
    }

    /**
     * {@value CoreSections#BOOT_LAYER} or {@value CoreSections#APPLICATION_LAYER} for a named module of one of
     * them, else {@code null}.
     */
    static String layerOf(Module module) {
        ModuleLayer layer = module.isNamed() ? module.getLayer() : null;
        if (layer == null) {
            return null;
        }
        if (layer == ModuleLayer.boot()) {
            return CoreSections.BOOT_LAYER;
        }
        return layer == VidocqAppLayer.installedLayer() ? CoreSections.APPLICATION_LAYER : null;
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
        } catch (RuntimeException _) {
            return 0;
        }
    }

    private static Path file(URI location) {
        if (location == null || !"file".equalsIgnoreCase(location.getScheme())) {
            return null;
        }
        try {
            return Path.of(location).normalize();
        } catch (RuntimeException _) {
            return null;
        }
    }

    private static Path absolute(String archive) {
        try {
            return Path.of(archive).toAbsolutePath().normalize();
        } catch (RuntimeException _) {
            return null;
        }
    }
}
