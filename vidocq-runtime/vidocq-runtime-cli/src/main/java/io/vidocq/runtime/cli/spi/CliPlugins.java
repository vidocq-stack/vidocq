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
package io.vidocq.runtime.cli.spi;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Discovery and lookup of {@link VidocqCliPlugin} providers. The
 * {@link #find(List, String)} lookup is pure so it can be unit-tested with fake
 * plugins; {@link #all()} performs the impure {@link ServiceLoader} scan.
 */
public final class CliPlugins {

    private CliPlugins() {}

    /** All plugins on the classpath/module-path, sorted by command name. */
    public static List<VidocqCliPlugin> all() {
        return ServiceLoader.load(VidocqCliPlugin.class).stream()
                .map(ServiceLoader.Provider::get)
                .sorted(Comparator.comparing(VidocqCliPlugin::command))
                .toList();
    }

    /** The first plugin in {@code plugins} whose command matches {@code command}. */
    public static Optional<VidocqCliPlugin> find(List<VidocqCliPlugin> plugins, String command) {
        return plugins.stream()
                .filter(p -> command.equals(p.command()))
                .findFirst();
    }

    /** Convenience: scan and look up {@code command} in one call. */
    public static Optional<VidocqCliPlugin> find(String command) {
        return find(all(), command);
    }
}
