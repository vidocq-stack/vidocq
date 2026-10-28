/*
 * Copyright 2026 Vidocq contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
