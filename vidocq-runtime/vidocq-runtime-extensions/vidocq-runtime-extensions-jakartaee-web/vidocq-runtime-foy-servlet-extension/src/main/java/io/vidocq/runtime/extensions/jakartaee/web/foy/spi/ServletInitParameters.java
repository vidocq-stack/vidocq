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
package io.vidocq.runtime.extensions.jakartaee.web.foy.spi;

import io.vidocq.runtime.spi.config.VidocqConfig;

import java.util.Map;

/**
 * Contributes servlet context init parameters to the Foy deployments: an extension that configures a servlet
 * component from the runtime configuration (the WebSocket extension gives Foy its allowed origins) does it here.
 * Found with {@link java.util.ServiceLoader} among the runtime extensions, the way a
 * {@link ServletDeploymentListener} is.
 *
 * <p>Each entry is set on the deployment before its initializers run, and wins over a {@code <context-param>} of
 * the same name in {@code web.xml}. Two providers giving the same name fail the deployment with an
 * {@link IllegalStateException} naming it. A provider that returns {@code null}, or a map with a {@code null}
 * name or value, fails the deployment with a {@link NullPointerException}, as a {@code null} init parameter does.
 */
public interface ServletInitParameters {

    /**
     * Called once per deployment, before it is built.
     *
     * @param config the runtime configuration
     * @return the init parameters to set, by name; possibly empty, never {@code null}
     */
    Map<String, String> initParameters(VidocqConfig config);
}
