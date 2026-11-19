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
package io.vidocq.dirac.rest;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;

/**
 * Normalizes the Accept header before JAX-RS routing.
 *
 * <p>MP Metrics §2.3 rule: null / empty / wildcard ({@code *}{@code /*}) → {@code text/plain}
 * (default OpenMetrics format). Explicit values ({@code application/json},
 * {@code text/plain}) are passed through as-is.</p>
 */
@Provider
@PreMatching
public class ContentNegotiationFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext ctx) {
        var accept = ctx.getHeaderString(HttpHeaders.ACCEPT);
        if (accept == null || accept.isBlank() || MediaType.WILDCARD.equals(accept.trim())) {
            ctx.getHeaders().putSingle(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN);
        }
    }
}
