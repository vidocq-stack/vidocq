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
package io.vidocq.humboldt.sdk.common;

import io.opentelemetry.api.common.Attributes;

/**
 * Identity of the library that produces a span / metric / log (see OpenTelemetry
 * "Instrumentation Scope").
 *
 * @param name       library name (never {@code null}, may be empty)
 * @param version    library version (may be {@code null})
 * @param schemaUrl  semantic conventions schema URL (may be {@code null})
 * @param attributes additional attributes (never {@code null})
 */
public record InstrumentationScope(
        String name, String version, String schemaUrl, Attributes attributes) {

    public InstrumentationScope {
        if (name == null) throw new NullPointerException("name");
        if (attributes == null) attributes = Attributes.empty();
    }

    public static InstrumentationScope of(String name) {
        return new InstrumentationScope(name, null, null, Attributes.empty());
    }
}
