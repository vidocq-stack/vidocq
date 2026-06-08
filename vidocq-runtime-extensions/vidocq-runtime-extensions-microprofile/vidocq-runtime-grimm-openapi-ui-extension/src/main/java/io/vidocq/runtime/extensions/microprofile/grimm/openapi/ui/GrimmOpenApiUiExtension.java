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
package io.vidocq.runtime.extensions.microprofile.grimm.openapi.ui;

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.StaticFileHandler;
import io.vidocq.runtime.extensions.essentials.chappe.ChappeMountPoint;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqExtension;

/**
 * Vidocq extension that serves <a href="https://swagger.io/tools/swagger-ui/">Swagger UI</a>
 * (vendored {@code swagger-ui-dist}, Apache-2.0) for the OpenAPI document produced by Grimm.
 *
 * <p>The bundle is shipped as classpath resources under
 * {@code META-INF/resources/openapi-ui/} and exposed via a Chappe
 * {@link StaticFileHandler} mounted at {@code /openapi/ui}. Because the UI is served by the same
 * Chappe engine as the {@code /openapi} document (see
 * {@code vidocq-runtime-grimm-openapi-extension}), the spec fetch is <b>same-origin</b> and needs
 * no CORS. The {@code swagger-initializer.js} points the UI at {@code /openapi?format=json}.</p>
 *
 * <h3>Lifecycle</h3>
 * <p>The Chappe router resolves mounts by <b>first registration order</b> (first match wins, see
 * {@code DefaultRouterBuilder}). The Cassini REST extension (priority 500) mounts a catch-all at the
 * root ({@code ""}) that matches every path, so this extension must register its more specific
 * {@code /openapi/ui} mount <b>before</b> Cassini. Hence priority {@value #PRIORITY}: after
 * {@code ChappeEngineExtension} (100), which installs the {@link ChappeMountPoint} in its
 * {@code configure} phase, and before Cassini (500).</p>
 */
public final class GrimmOpenApiUiExtension implements VidocqExtension {

    private static final System.Logger LOG = System.getLogger(GrimmOpenApiUiExtension.class.getName());

    /** HTTP prefix the Swagger UI is mounted on. */
    static final String MOUNT_PREFIX = "/openapi/ui";

    /** Classpath base of the vendored {@code swagger-ui-dist} bundle. */
    static final String CLASSPATH_BASE = "META-INF/resources/openapi-ui";

    /**
     * Must register the {@code /openapi/ui} mount before the Cassini REST catch-all ({@code ""},
     * priority 500) since the router is first-match-in-registration-order. After
     * {@code ChappeEngineExtension} (100) which installs the mount point.
     */
    static final int PRIORITY = 200;

    @Override
    public String name() {
        return "grimm-openapi-ui";
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public void onStart(ExtensionContext context) {
        Handler ui = StaticFileHandler.builder()
                .addClasspath(getClass().getClassLoader(), CLASSPATH_BASE)
                .indexFile("index.html")
                .cacheControl("public, max-age=86400")
                .build();
        ChappeMountPoint.instance().mount(MOUNT_PREFIX, ui);
        LOG.log(System.Logger.Level.INFO,
                "Swagger UI mounted at {0}/ (spec: /openapi?format=json)", MOUNT_PREFIX);
    }
}
