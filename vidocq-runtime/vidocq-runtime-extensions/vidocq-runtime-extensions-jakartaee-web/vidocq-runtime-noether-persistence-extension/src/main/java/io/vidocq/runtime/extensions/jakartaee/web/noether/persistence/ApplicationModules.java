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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence;

import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.units.ModuleRoots;
import io.vidocq.runtime.spi.ApplicationLayer;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Where the modules of the running application are: in the layer Vidocq boots it in, else in the boot layer. The root
 * of a module is the archive or the directory it was resolved from.
 */
final class ApplicationModules implements ModuleRoots {

    @Override
    public Optional<URL> application() {
        return ApplicationLayer.applicationModule()
                .flatMap(named -> Optional.ofNullable(named.getLayer()).flatMap(layer -> root(layer, named.getName())));
    }

    @Override
    public Optional<URL> of(String name) {
        return Stream.concat(ApplicationLayer.current().stream(), Stream.of(ModuleLayer.boot()))
                .flatMap(layer -> root(layer, name).stream()).findFirst();
    }

    private static Optional<URL> root(ModuleLayer layer, String name) {
        return layer.configuration().findModule(name).map(ResolvedModule::reference)
                .flatMap(ModuleReference::location).flatMap(ApplicationModules::url);
    }

    private static Optional<URL> url(URI location) {
        try {
            return Optional.of(location.toURL());
        } catch (MalformedURLException | IllegalArgumentException _) {
            // a location no URL stands for: no root to scan
            return Optional.empty();
        }
    }
}
