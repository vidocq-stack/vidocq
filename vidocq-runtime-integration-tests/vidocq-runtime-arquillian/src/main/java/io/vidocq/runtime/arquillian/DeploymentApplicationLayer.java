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
package io.vidocq.runtime.arquillian;

import io.vidocq.runtime.spi.ApplicationLayer;

import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The application layer of the current deployment, for the embedded container, which runs on the class path and
 * installs no layer of its own: the layer of the war's compiled JSP pages ({@link WarJspPages}), when there is one.
 * The runtime's own provider answers first, and gives nothing in this container.
 */
public final class DeploymentApplicationLayer implements ApplicationLayer {

    private static final AtomicReference<ModuleLayer> CURRENT = new AtomicReference<>();
    private static final AtomicReference<List<URL>> ROOTS = new AtomicReference<>(List.of());

    /** Created by the service loader. */
    public DeploymentApplicationLayer() {
        // nothing to set up: the layer is handed over later, per deployment
    }

    static void set(ModuleLayer layer) {
        CURRENT.set(layer);
    }

    /** The roots of the deployed archive's own classes, which no build-time index lists. */
    static void roots(List<URL> roots) {
        ROOTS.set(List.copyOf(roots));
    }

    static void clear() {
        CURRENT.set(null);
        ROOTS.set(List.of());
    }

    @Override
    public Optional<ModuleLayer> layer() {
        return Optional.ofNullable(CURRENT.get());
    }

    @Override
    public List<URL> roots() {
        return ROOTS.get();
    }
}
