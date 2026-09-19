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
package io.vidocq.runtime.spi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.ModuleReader;
import java.lang.module.ResolvedModule;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The module layer Vidocq boots the application in, when it boots it in one of its own: under
 * {@code vidocq:dev} and {@code vidocq:run}, from the launcher {@code vidocq:package} writes by default,
 * and after {@code Vidocq.run} re-layers a trampoline's application.
 *
 * <p>In such a launch the application's archives are off the JVM's class and module paths. The thread
 * context class loader serves each of their files by name, but no class loader lists a directory of
 * them: {@code getResources("db/migration")} finds nothing. A library that scans a directory of the
 * application, such as a schema-migration engine, {@linkplain #list lists it from this layer} and reads
 * each file by name through the context class loader.
 *
 * <p>Vidocq implements this interface; an extension calls {@link #current()}.
 */
public interface ApplicationLayer {

    /**
     * The application layer this JVM installed, as it stands now: a dev reload replaces it.
     *
     * @return the layer, or empty when the application was not booted in a layer of its own
     */
    Optional<ModuleLayer> layer();

    /**
     * The application layer of the running Vidocq.
     *
     * @return the layer; empty when there is none, in a flat or class-path launch, and when no
     *         runtime implements this interface, as in the unit tests of an extension
     */
    static Optional<ModuleLayer> current() {
        try {
            for (ApplicationLayer provider : ServiceLoader.load(ApplicationLayer.class,
                    ApplicationLayer.class.getClassLoader())) {
                Optional<ModuleLayer> layer = provider.layer();
                if (layer.isPresent()) {
                    return layer;
                }
            }
        } catch (ServiceConfigurationError unusable) {
            // a provider that cannot be loaded gives no layer
        }
        return Optional.empty();
    }

    /**
     * The files under {@code directory} in the modules of {@code layer}, at any depth: their resource
     * names, such as {@code db/migration/V1__init.sql} for {@code db/migration}. Directories are left out.
     * Only the modules of {@code layer} itself are listed, not those of its parents.
     *
     * @param layer     the layer, usually the one {@link #current()} returns
     * @param directory a resource directory, such as {@code db/migration}; a leading or trailing
     *                  {@code /} is ignored, and {@code ""} lists every file
     * @return the names, sorted, each once; an immutable list, empty when there is none
     * @throws UncheckedIOException when a module of the layer cannot be read
     */
    static List<String> list(ModuleLayer layer, String directory) {
        String root = directory.replaceAll("^/+|/+$", "");
        String prefix = root.isEmpty() ? "" : root + "/";
        TreeSet<String> names = new TreeSet<>();
        for (ResolvedModule module : layer.configuration().modules()) {
            try (ModuleReader reader = module.reference().open();
                 Stream<String> entries = reader.list()) {
                entries.filter(name -> name.startsWith(prefix) && !name.endsWith("/")).forEach(names::add);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot list the module " + module.name(), e);
            }
        }
        return List.copyOf(names);
    }
}
