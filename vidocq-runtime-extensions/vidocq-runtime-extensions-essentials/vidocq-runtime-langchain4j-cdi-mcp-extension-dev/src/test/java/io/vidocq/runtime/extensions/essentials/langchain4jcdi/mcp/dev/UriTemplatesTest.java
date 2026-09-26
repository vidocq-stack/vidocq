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
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import jakarta.json.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** RFC 6570 level 1, which langchain4j-cdi's template matcher decodes. */
class UriTemplatesTest {

    @Test
    void aVariableIsPercentEncodedButItsUnreservedCharacters() {
        assertEquals("time://zone/Europe%2FParis", UriTemplates.expand("time://zone/{zone}",
                Json.createObjectBuilder().add("zone", "Europe/Paris").build()));
        assertEquals("q/a%20b-c.d_e~f%C3%A9", UriTemplates.expand("q/{q}",
                Json.createObjectBuilder().add("q", "a b-c.d_e~fé").build()));
    }

    @Test
    void aMissingVariableIsEmptyAndANumberIsItsText() {
        assertEquals("a//7", UriTemplates.expand("a/{x}/{n}", Json.createObjectBuilder().add("n", 7).build()));
    }
}
