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
package io.vidocq.runtime.cli.ext;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniJsonTest {

    @Test
    void parsesArrayOfObjects() {
        String json = """
                [
                  {"id": "knock-health", "groupId": "io.vidocq", "description": "Health"},
                  {"id": "dirac-metrics", "groupId": "io.vidocq", "description": "Metrics"}
                ]
                """;
        List<Map<String, String>> rows = MiniJson.parseArrayOfObjects(json);
        assertEquals(2, rows.size());
        assertEquals("knock-health", rows.get(0).get("id"));
        assertEquals("Metrics", rows.get(1).get("description"));
    }

    @Test
    void parsesEmptyArray() {
        assertTrue(MiniJson.parseArrayOfObjects("[]").isEmpty());
        assertTrue(MiniJson.parseArrayOfObjects("  [ ]  ").isEmpty());
    }

    @Test
    void handlesEscapesAndUnicode() {
        String json = "[{\"id\":\"a\\\"b\",\"d\":\"line\\nbreak\",\"u\":\"\\u0041\"}]";
        Map<String, String> row = MiniJson.parseArrayOfObjects(json).get(0);
        assertEquals("a\"b", row.get("id"));
        assertEquals("line\nbreak", row.get("d"));
        assertEquals("A", row.get("u"));
    }

    @Test
    void capturesScalarsAsStringsAndSkipsNested() {
        String json = "[{\"version\": 7, \"stable\": true, \"meta\": {\"x\":1}, \"tags\": [1,2], \"none\": null}]";
        Map<String, String> row = MiniJson.parseArrayOfObjects(json).get(0);
        assertEquals("7", row.get("version"));
        assertEquals("true", row.get("stable"));
        assertNull(row.get("meta"));
        assertNull(row.get("tags"));
        assertNull(row.get("none"));
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parseArrayOfObjects("[{"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parseArrayOfObjects("{}"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parseArrayOfObjects("[] junk"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parseArrayOfObjects(null));
    }
}
