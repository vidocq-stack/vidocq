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
package io.vidocq.runtime.ext.grimm.openapi;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.grimm.cdi.GrimmModelCache;
import io.vidocq.grimm.cdi.OpenApiResource;
import io.vidocq.runtime.ext.chappe.ChappeMountPoint;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.vauban.core.container.VaubanContainer;

/**
 * Vidocq extension that serves the Grimm (MicroProfile OpenAPI 4.1) document at {@code /openapi}.
 *
 * <p>The OpenAPI model is assembled at startup by grimm-cdi-vauban's CDI BuildCompatibleExtension and
 * cached in {@code GrimmModelCache}. This extension looks up grimm's {@link OpenApiResource} bean and
 * renders it through a thin Chappe {@link io.vidocq.chappe.api.Handler}, so the document is served on
 * the same Chappe engine (and origin) as the Swagger UI from
 * {@code vidocq-runtime-grimm-openapi-ui-extension} — no CORS involved.</p>
 *
 * <h3>Why not Cassini?</h3>
 * <p>Cassini's runtime dispatch is driven by build-time generated route metadata (ServiceLoader
 * {@code RouteRegistry}) produced by the cassini codegen APT from <em>source</em> {@code @Path}
 * classes. Grimm's {@code OpenApiResource} lives in a dependency jar and is never part of a codegen
 * compilation, so Cassini cannot dispatch it. Reusing {@link OpenApiResource#render(String, String)}
 * over a Chappe handler yields the exact same output without depending on the REST layer.</p>
 *
 * <h3>Prerequisite</h3>
 * <p>This only mounts {@code /openapi} when grimm is actually active in the container, i.e. when
 * {@code grimm-cdi-vauban} is a functional vauban bean archive (ships a vauban bean index and
 * registers its CDI {@code BuildCompatibleExtension}, like {@code knock-cdi-vauban} does). As long
 * as grimm-cdi-vauban ships without that wiring, {@link io.vidocq.grimm.cdi.GrimmModelCache} is not
 * a resolvable bean and the endpoint is skipped (a warning is logged). Enabling it is a grimm-side
 * packaging fix (vauban-processor + {@code META-INF/beans.xml} + BCE service file).</p>
 *
 * <h3>Lifecycle</h3>
 * <p>The Chappe router resolves mounts by first registration order (first match wins). This extension
 * registers {@code /openapi} at priority {@value #PRIORITY} — after the Swagger UI ({@code /openapi/ui},
 * priority 200) so the longer UI prefix is matched first, and before the Cassini catch-all
 * ({@code ""}, priority 500). {@code ChappeEngineExtension} (100) has installed the
 * {@link ChappeMountPoint} in its {@code configure} phase.</p>
 */
public final class GrimmOpenApiExtension implements VidocqExtension {

    private static final System.Logger LOG = System.getLogger(GrimmOpenApiExtension.class.getName());

    /** HTTP path the OpenAPI document is served on. */
    static final String MOUNT_PREFIX = "/openapi";

    /** After the Swagger UI (200, longer prefix), before the Cassini catch-all (500). */
    static final int PRIORITY = 300;

    @Override
    public String name() {
        return "grimm-openapi";
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public void onStart(ExtensionContext context) {
        // GrimmModelCache is a synthetic bean produced by grimm's CDI BuildCompatibleExtension.
        // It is only present if grimm-cdi-vauban is active as a vauban bean archive (vauban index +
        // BCE registration). When grimm is not wired that way, we log and skip the mount rather than
        // exposing an endpoint that would fail — see the class javadoc.
        GrimmModelCache cache = tryResolve(context.container());
        if (cache == null) {
            LOG.log(System.Logger.Level.WARNING,
                    "Grimm is not active in the container (grimm-cdi-vauban is not a vauban bean "
                            + "archive: no vauban index / BCE registration). {0} not mounted.",
                    MOUNT_PREFIX);
            return;
        }
        OpenApiResource resource = new OpenApiResource(cache);
        ChappeMountPoint.instance().mount(MOUNT_PREFIX, request -> render(resource, request));
        LOG.log(System.Logger.Level.INFO, "OpenAPI document served at {0}", MOUNT_PREFIX);
    }

    private static GrimmModelCache tryResolve(VaubanContainer container) {
        try {
            return container.select(GrimmModelCache.class);
        } catch (RuntimeException unsatisfied) {
            return null;
        }
    }

    /** Renders the cached OpenAPI document, honouring {@code ?format=} and the {@code Accept} header. */
    private static Response render(OpenApiResource resource, Request request) {
        String format = request.queryParam("format").orElse(null);
        String accept = request.header("Accept").orElse(null);
        OpenApiResource.RenderedDocument doc = resource.render(format, accept);
        return Response.builder()
                .status(StatusCode.OK)
                .header("Content-Type", doc.mediaType())
                .body(Body.of(doc.body()))
                .build();
    }
}
