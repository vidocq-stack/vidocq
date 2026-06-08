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

import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

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

        if (extensions.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "No Vidocq extensions discovered");
        } else {
            LOG.log(System.Logger.Level.INFO, "Discovered {0} extension(s):", extensions.size());
            for (VidocqExtension ext : extensions) {
                LOG.log(System.Logger.Level.INFO, "  - {0} (priority={1})",
                        ext.name(), ext.priority());
            }
        }

        return extensions;
    }
}
