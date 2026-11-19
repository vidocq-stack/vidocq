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
package io.vidocq.grimm.internal;

import io.vidocq.grimm.internal.serialization.JsonSerializer;
import io.vidocq.grimm.internal.serialization.YamlSerializer;
import org.eclipse.microprofile.openapi.models.OpenAPI;

import java.util.Objects;

/**
 * Facade exposing OpenAPI serialization to modules that only depend on grimm-core.
 */
public final class OpenApiSerializers {

    private OpenApiSerializers() {
    }

    public static String toJson(OpenAPI openAPI) {
        return new JsonSerializer().serialize(Objects.requireNonNull(openAPI, "openAPI must not be null"));
    }

    public static String toYaml(OpenAPI openAPI) {
        return new YamlSerializer().serialize(Objects.requireNonNull(openAPI, "openAPI must not be null"));
    }
}

