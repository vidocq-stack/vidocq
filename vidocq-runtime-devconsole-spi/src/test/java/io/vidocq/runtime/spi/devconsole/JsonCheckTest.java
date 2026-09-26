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
package io.vidocq.runtime.spi.devconsole;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The SPI's own JSON check: as strict as the console's reader, so that what one accepts the other can read. */
class JsonCheckTest {

    @Test
    void oneObjectIsAccepted() {
        assertTrue(JsonCheck.isObject("{}", 64));
        assertTrue(JsonCheck.isObject(" {\"a\":1,\"b\":[true,null,\"x\"],\"c\":{\"d\":-1.5e3}} ", 64));
        assertTrue(JsonCheck.isObject("{\"u\":\"\\u00e9\\n\"}", 64));
    }

    @Test
    void anythingButOneObjectIsRefused() {
        assertFalse(JsonCheck.isObject(null, 64));
        assertFalse(JsonCheck.isObject("", 64));
        assertFalse(JsonCheck.isObject("[]", 64));
        assertFalse(JsonCheck.isObject("\"x\"", 64));
        assertFalse(JsonCheck.isObject("{\"a\":1} x", 64), "text after the object");
        assertFalse(JsonCheck.isObject("{\"a\":1,\"a\":2}", 64), "a name written twice");
        assertFalse(JsonCheck.isObject("{\"a\":01}", 64), "a number JSON does not allow");
        assertFalse(JsonCheck.isObject("{\"a\":\"\u0001\"}", 64), "a raw control character");
        assertFalse(JsonCheck.isObject("{'a':1}", 64));
        assertFalse(JsonCheck.isObject("{\"a\":1,}", 64));
        assertFalse(JsonCheck.isObject("{\"a\":", 64));
    }

    @Test
    void nestingPastTheLimitIsRefused() {
        assertFalse(JsonCheck.isObject("{\"a\":{\"b\":1}}", 1));
        assertTrue(JsonCheck.isObject("{\"a\":{\"b\":1}}", 2));
        assertFalse(JsonCheck.isObject("{\"a\":[[1]]}", 2));
    }
}
