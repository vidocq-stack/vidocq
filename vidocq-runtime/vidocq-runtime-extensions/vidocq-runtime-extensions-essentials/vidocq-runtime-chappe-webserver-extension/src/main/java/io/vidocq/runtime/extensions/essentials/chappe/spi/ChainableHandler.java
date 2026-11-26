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
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * A handler that can share its prefix with other mounts of the same listener (vidocq#802): static files,
 * REST resources and servlets all on {@code /}. The mounts of a shared prefix are asked in their order
 * ({@link MountOrder}); one that has nothing for a request passes it on, and the last one answers what no
 * mount before it served, its own 404 included. Only the last mount of a shared prefix may be a plain
 * {@link Handler}, which cannot pass a request on.
 *
 * <p>A mount alone on its prefix is only ever asked {@link #handle}: sharing costs nothing until a
 * prefix is shared.</p>
 */
public interface ChainableHandler extends Handler {

    /**
     * Handles {@code request} if this handler has something for it.
     *
     * @return the response, or {@code null} to pass the request to the next mount of the prefix
     * @throws Exception if processing fails (the server answers 500)
     */
    @SuppressWarnings("java:S112") // the contract of Handler.handle, which this method calls
    Response handleIfServed(Request request) throws Exception;

    /**
     * A handler that serves the requests {@code serves} accepts, through {@code handler}, and passes the
     * others on. {@code serves} must not handle the request: it only says whether the handler would.
     */
    static ChainableHandler of(Handler handler, Predicate<Request> serves) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(serves, "serves");
        return new ChainableHandler() {
            @Override
            public Response handle(Request request) throws Exception {
                return handler.handle(request);
            }

            @Override
            public Response handleIfServed(Request request) throws Exception {
                return serves.test(request) ? handler.handle(request) : null;
            }
        };
    }
}
