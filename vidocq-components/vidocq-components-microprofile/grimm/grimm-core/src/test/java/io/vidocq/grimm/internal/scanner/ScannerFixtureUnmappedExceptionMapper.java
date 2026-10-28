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

import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * Named like a mapper of {@link ScannerFixtureUnmappedException} but not an
 * {@code ExceptionMapper}: its response is not documented.
 */
@APIResponse(responseCode = "503", description = "Not a mapper")
public final class ScannerFixtureUnmappedExceptionMapper {

    @Override
    public String toString() {
        return "not an ExceptionMapper";
    }
}
