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
package io.vidocq.grimm.internal.scanner;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * Fixture {@link ExceptionMapper}: its {@code @APIResponse}s, on the class and on
 * {@code toResponse}, document the operations that declare {@link ScannerFixtureException}.
 */
@APIResponse(responseCode = "409", description = "Conflict", ref = "#/components/responses/Conflict",
        content = @Content(example = "{}"), headers = @Header(name = "X-Reason"))
@APIResponse(responseCode = "200", description = "Ignored: the operation already has a 200")
public class ScannerFixtureExceptionMapper implements ExceptionMapper<ScannerFixtureException> {

    @Override
    @APIResponse(description = "Mapped failure", content = @Content(mediaType = "text/plain"))
    public Response toResponse(ScannerFixtureException exception) {
        return Response.status(409).entity(exception.getMessage()).build();
    }

    /** Not the mapping method: its annotations are not read. */
    @APIResponse(responseCode = "418", description = "Not read")
    public Response toResponse(ScannerFixtureException exception, String detail) {
        return Response.fromResponse(toResponse(exception)).header("X-Detail", detail).build();
    }

    /** Wrong name: not read either. */
    @APIResponse(responseCode = "419", description = "Not read")
    public Response other(ScannerFixtureException exception) {
        return toResponse(exception);
    }
}
