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
package io.vidocq.champollion.jsonp.internal;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code true}, {@code false} or {@code null} value of another implementation than the {@link JsonValue} constants
 * is read by its value type, not by identity with {@link JsonValue#TRUE}, {@link JsonValue#FALSE} or
 * {@link JsonValue#NULL} (BUG-20261127-90).
 */
@DisplayName("Object model — literal values of another implementation are read by their value type")
class ForeignLiteralValuesTest {

    private static final JsonValue FOREIGN_TRUE = () -> JsonValue.ValueType.TRUE;
    private static final JsonValue FOREIGN_FALSE = () -> JsonValue.ValueType.FALSE;
    private static final JsonValue FOREIGN_NULL = () -> JsonValue.ValueType.NULL;

    @Test
    void anArrayReadsForeignLiterals() {
        JsonArray array = new ChampollionJsonArrayBuilder()
                .add(FOREIGN_TRUE).add(FOREIGN_FALSE).add(FOREIGN_NULL).build();
        assertTrue(array.getBoolean(0));
        assertFalse(array.getBoolean(1));
        assertTrue(array.isNull(2));
        assertFalse(array.isNull(0));
    }

    @Test
    void anObjectReadsForeignLiterals() {
        JsonObject object = new ChampollionJsonObjectBuilder()
                .add("t", FOREIGN_TRUE).add("f", FOREIGN_FALSE).add("n", FOREIGN_NULL).build();
        assertTrue(object.getBoolean("t"));
        assertFalse(object.getBoolean("f"));
        assertTrue(object.isNull("n"));
        assertFalse(object.isNull("t"));
    }

    @Test
    void aMergePatchRemovesAMemberSetToAForeignNull() {
        JsonObject target = new ChampollionJsonObjectBuilder().add("a", 1).add("b", 2).build();
        JsonObject patch = new ChampollionJsonObjectBuilder().add("a", FOREIGN_NULL).build();
        JsonValue merged = new ChampollionJsonMergePatch(patch).apply(target);
        assertEquals(new ChampollionJsonObjectBuilder().add("b", 2).build(), merged);
    }
}
