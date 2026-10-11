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
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;

import java.util.Set;

/**
 * Serves the application's Jakarta Servlet components through Foy on the shared Chappe web
 * server. Runs after the REST extension (500) and before the declarative mounts (7000) and the
 * server start (10000).
 */
public final class FoyExtension implements VidocqExtension {

    private ServletSettings settings;

    @Override
    public String name() {
        return "servlet-foy";
    }

    @Override
    public int priority() {
        return 600;
    }

    @Override
    public void configure(VidocqConfiguration config) {
        this.settings = ServletSettings.from(config);
    }

    @Override
    public Set<String> configKeys() {
        return Set.of(ServletSettings.CONTEXT_PATH, ServletSettings.LISTENER, ServletSettings.SESSION_TIMEOUT);
    }
}
