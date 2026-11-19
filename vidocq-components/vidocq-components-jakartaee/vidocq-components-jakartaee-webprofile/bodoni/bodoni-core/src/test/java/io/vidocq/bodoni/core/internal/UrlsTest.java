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

package io.vidocq.bodoni.core.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import io.vidocq.bodoni.core.testing.TestHttpServletRequest;
import io.vidocq.bodoni.core.testing.TestServletContext;

import jakarta.servlet.jsp.JspTagException;

import org.junit.jupiter.api.Test;

class UrlsTest {

    private final TestHttpServletRequest request = new TestHttpServletRequest(new TestServletContext());

    @Test
    void absoluteUrlsHaveAScheme() {
        assertTrue(Urls.isAbsolute("http://x"));
        assertTrue(Urls.isAbsolute("mailto:a@b"));
        assertTrue(Urls.isAbsolute("a+b-c.d:rest"));
        assertFalse(Urls.isAbsolute("/p"));
        assertFalse(Urls.isAbsolute("p?q=a:b"));
        assertFalse(Urls.isAbsolute("p#a:b"));
        assertFalse(Urls.isAbsolute("p/q:r"));
        assertFalse(Urls.isAbsolute("1x:y"));
        assertFalse(Urls.isAbsolute(":y"));
        assertFalse(Urls.isAbsolute(""));
    }

    @Test
    void resolveRules() throws Exception {
        assertEquals("http://e.org/p", Urls.resolve("http://e.org/p", null, request));
        assertEquals("/app/p", Urls.resolve("/p", null, request));
        assertEquals("p", Urls.resolve("p", null, request));
        assertEquals("/other/p", Urls.resolve("/p", "/other", request));
        assertEquals("/p", Urls.resolve("/p", "/", request));
    }

    @Test
    void foreignContextMustBeContextRelative() {
        assertThrows(JspTagException.class, () -> Urls.resolve("/p", "other", request));
        assertThrows(JspTagException.class, () -> Urls.resolve("p", "/other", request));
    }

    @Test
    void appendParametersEncodesAndKeepsFragment() {
        List<Map.Entry<String, String>> params = List.of(Map.entry("a", "1"), Map.entry("b c", "é&"));
        assertEquals("/p?a=1&b+c=%C3%A9%26", Urls.appendParameters("/p", params, "UTF-8"));
        assertEquals("/p?x=y&a=1&b+c=%E9%26#f", Urls.appendParameters("/p?x=y#f", params, "ISO-8859-1"));
        assertEquals("/p", Urls.appendParameters("/p", List.of(), "UTF-8"));
    }

    @Test
    void stripSessionId() {
        assertEquals("/p?q=1", Urls.stripSessionId("/p;jsessionid=ABC?q=1"));
        assertEquals("/p#f", Urls.stripSessionId("/p;JSessionId=ABC#f"));
        assertEquals("/p/q", Urls.stripSessionId("/p;jsessionid=ABC/q"));
        assertEquals("/p;x=1", Urls.stripSessionId("/p;jsessionid=ABC;x=1"));
        assertEquals("/p", Urls.stripSessionId("/p;jsessionid=ABC"));
        assertEquals("/p?a=;jsessionid=Z", Urls.stripSessionId("/p?a=;jsessionid=Z"));
        assertEquals("/p", Urls.stripSessionId("/p"));
    }
}
