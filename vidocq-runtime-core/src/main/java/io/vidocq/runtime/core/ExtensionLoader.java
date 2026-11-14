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

import io.vidocq.runtime.spi.VidocqExtension;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleReader;
import java.lang.module.ResolvedModule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.TreeMap;

/**
 * Discovers and loads {@link VidocqExtension} via {@link ServiceLoader}.
 * <p>Extensions are sorted by ascending {@link VidocqExtension#priority()}.</p>
 *
 * <p><b>Discovers and loads {@link VidocqExtension}s via {@link ServiceLoader}.</b>
 * Extensions are sorted by ascending {@link VidocqExtension#priority()}.</p>
 */
final class ExtensionLoader {

    private static final System.Logger LOG = System.getLogger(ExtensionLoader.class.getName());

    private ExtensionLoader() {}

    /**
     * Loads all available extensions, sorted by priority.
     */
    static List<VidocqExtension> load() {
        List<VidocqExtension> extensions = ServiceLoader.load(VidocqExtension.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .sorted(Comparator.comparingInt(VidocqExtension::priority))
                .toList();

        List<String> lines = report(extensions, descriptors(extensions, VidocqAppLayer.installedLayer()));
        System.Logger.Level level = extensions.isEmpty() && lines.size() == 1
                ? System.Logger.Level.WARNING : System.Logger.Level.INFO;
        lines.forEach(line -> LOG.log(level, line));

        return extensions;
    }

    /** The descriptor file of a Vidocq extension module, as {@code vidocq extension add} reads it. */
    private static final String DESCRIPTOR = "META-INF/vidocq/extension.properties";

    /**
     * The start-up list: the {@link VidocqExtension}s, by priority, then the extension modules of {@code descriptors}
     * that have none, "CDI only". The JWT, Rest Client, Fault Tolerance and Config extensions bring a CDI Build
     * Compatible Extension and no lifecycle, and the list left them out although they work (BUG-20261030-17).
     *
     * @param descriptors the {@code id} of each extension module's descriptor, by module name
     */
    static List<String> report(List<VidocqExtension> extensions, Map<String, String> descriptors) {
        List<String> cdiOnly = new ArrayList<>();
        descriptors.forEach((module, id) -> {
            boolean hasLifecycle = extensions.stream().anyMatch(ext ->
                    id.equals(ext.name()) || module.equals(ext.getClass().getModule().getName()));
            if (!hasLifecycle) {
                cdiOnly.add(id);
            }
        });
        if (extensions.isEmpty() && cdiOnly.isEmpty()) {
            return List.of("No Vidocq extensions discovered");
        }
        List<String> lines = new ArrayList<>();
        lines.add("Discovered " + (extensions.size() + cdiOnly.size()) + " extension(s):");
        extensions.forEach(ext -> lines.add("  - " + ext.name() + " (priority=" + ext.priority() + ")"));
        cdiOnly.forEach(id -> lines.add("  - " + id + " (CDI only)"));
        return lines;
    }

    /**
     * The descriptors of the extension modules of the boot layer, of the application layer, and of the layers the
     * extensions came from (an application layer, under {@code vidocq:dev}). An extension that reads an application
     * library, such as the Vaadin extension, which reads {@code flow.server}, sits in the application layer with it.
     *
     * @param applicationLayer the installed application layer, or {@code null} without one
     */
    static Map<String, String> descriptors(List<VidocqExtension> extensions, ModuleLayer applicationLayer) {
        Map<String, String> descriptors = new TreeMap<>(descriptors(ModuleLayer.boot()));
        java.util.stream.Stream.concat(java.util.stream.Stream.ofNullable(applicationLayer),
                        extensions.stream().map(ext -> ext.getClass().getModule().getLayer()))
                .filter(java.util.Objects::nonNull).distinct()
                .forEach(layer -> descriptors.putAll(descriptors(layer)));
        return descriptors;
    }

    /**
     * The {@code id} of the descriptor of each module of {@code layer} that has one, by module name. Read from the
     * module's own content, through its {@link ModuleReader}: {@link Module#getResourceAsStream} asks the module's
     * class loader, and a loader that serves a whole layer, as Vauban's does, may answer for a name outside any
     * package with another module's file. Opening a reader per module costs a few milliseconds for 70 jars.
     */
    static Map<String, String> descriptors(ModuleLayer layer) {
        Map<String, String> descriptors = new TreeMap<>();
        for (ResolvedModule module : layer.configuration().modules()) {
            try (ModuleReader reader = module.reference().open()) {
                Optional<InputStream> found = reader.open(DESCRIPTOR);
                if (found.isPresent()) {
                    String id = id(found.get());
                    if (id != null) {
                        descriptors.put(module.name(), id);
                    }
                }
            } catch (IOException e) {
                // The list is informational: an unreadable descriptor only leaves its module out of it.
                LOG.log(System.Logger.Level.DEBUG, "Cannot read " + DESCRIPTOR + " of " + module.name(), e);
            }
        }
        return descriptors;
    }

    /** The {@code id} of a descriptor, or {@code null} when it has none; closes {@code in}. */
    private static String id(InputStream in) throws IOException {
        try (in) {
            Properties properties = new Properties();
            properties.load(in);
            String id = properties.getProperty("id");
            return id == null || id.isBlank() ? null : id.strip();
        }
    }
}
