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
package io.vidocq.cervantes.cdi;

import jakarta.enterprise.context.RequestScoped;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Optional;

/**
 * Query context with {@link JsonWebToken} validated from the current query.
 *
 * <p>Posted by JAX-RS authentication filter (module {@code cervantes-jaxrs}, M4) after
 * validation du bearer token, puis lu par {@link io.vidocq.cervantes.cdi.internal.JsonWebTokenProducer}
 * to produce the main injection. {@code @RequestScoped}: one instance per query, isolated
 * between competing requests by the Vauban.ZZPH0ZZ query context
 */
@RequestScoped
public class JsonWebTokenContext {

    private JsonWebToken token;

    /** Stores the validated token for the request (called by the authentication filter). */
    public void setToken(JsonWebToken token) {
        this.token = token;
    }

    /** @return the token for the current request if it was validated, otherwise empty (anonymous request). */
    public Optional<JsonWebToken> current() {
        return Optional.ofNullable(token);
    }

    /** Resets the context (end of request). */
    public void clear() {
        this.token = null;
    }
}
