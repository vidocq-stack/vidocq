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
package io.vidocq.grimm.internal.serialization;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Small {@code multipleOf} values must be written as plain decimals: YAML 1.1 parsers read
 * {@code 1E-10} as a string, and the MicroProfile OpenAPI TCK compares it with a number.
 */
class MultipleOfSerializationTest {

    private static OpenAPI modelWithMultipleOf(BigDecimal multipleOf) {
        Schema schema = OASFactory.createSchema()
                .type(List.of(Schema.SchemaType.NUMBER))
                .multipleOf(multipleOf);
        return OASFactory.createOpenAPI()
                .components(OASFactory.createComponents().addSchema("S", schema));
    }

    @Test
    void jsonWritesTinyMultipleOfAsPlainDecimal() {
        String json = new JsonSerializer().serialize(modelWithMultipleOf(BigDecimal.ONE.movePointLeft(10)));
        assertTrue(json.contains("\"multipleOf\":0.0000000001"), json);
        assertFalse(json.contains("E-"), json);
    }

    @Test
    void yamlWritesTinyMultipleOfAsPlainDecimal() {
        String yaml = new YamlSerializer().serialize(modelWithMultipleOf(BigDecimal.ONE.movePointLeft(10)));
        assertTrue(yaml.contains("multipleOf: 0.0000000001"), yaml);
        assertFalse(yaml.contains("E-"), yaml);
    }
}
