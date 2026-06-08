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
package io.vidocq.runtime.extensions.essentials.chappe.spi;

import io.vidocq.chappe.api.Handler;

/**
 * SPI Vidocq allowing an extension to provide a {@link Handler} Chappe
 * configurable by properties — invoked by {@code ChappeMountConfigExtension}
 * for each entry {@code vidocq.http.mount.<name>.type=<type>}.
 *
 * <p>Discovered by {@link java.util.ServiceLoader} via the path module
 * ({@code provides … with …} declaration in {@code module-info.java}) or
 * via {@code META-INF/services/io.vidocq.runtime.extensions.essentials.chappe.spi.MountHandlerProvider}.</p>
 *
 * <h3>Naming convention</h3>
 * <p>{@link #type()} must describe the <b>standard contract</b> served (e.g.
 * {@code "restful"} for Jakarta RESTful Web Services, {@code "servlet"}
 * for Jakarta Servlet, {@code "static"} for static content), not
 * the implementation. Several providers can thus share the same type;
 * the selection will be made later via an optional property {@code .impl}
 * (to be introduced on the day cohabitation becomes necessary).</p>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * public final class CassiniMountHandlerProvider implements MountHandlerProvider {
 *     public String type() { return "restful"; }
 *     public Handler create(MountConfig cfg) {
 *         return new ChappeHttpAdapter(buildCassiniStack(cfg).adaptor());
 *     }
 * }
 * }</pre>
 */
public interface MountHandlerProvider {

    /**
     * Logical identifier of the <b>standard contract</b> served by this provider
     * (key {@code vidocq.http.mount.<name>.type}).
     *
     * <p>For the moment, only one provider must declare a given {@code type}
     * (conflicts are reported at resolution). A future property
     * {@code .impl} will allow a type to be shared between several
     * implementations.</p>
     */
    String type();

    /**
     * Constructs the {@link Handler} for a given mount, with access to the config
     * scoped under {@code vidocq.mount.<name>.}.
     */
    Handler create(MountConfig config);
}
