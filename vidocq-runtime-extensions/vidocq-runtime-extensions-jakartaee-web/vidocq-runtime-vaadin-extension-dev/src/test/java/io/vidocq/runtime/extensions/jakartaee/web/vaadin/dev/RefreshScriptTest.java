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
package io.vidocq.runtime.extensions.jakartaee.web.vaadin.dev;

import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.communication.IndexHtmlResponse;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshScriptTest {

    @Test
    void theIndexPageGetsTheScript() {
        String script = script("");

        assertTrue(script.contains("new EventSource('/.vidocq/dev/redefined')"), script);
        assertTrue(script.contains("client.poll()"), script);
    }

    /** Vaadin's base is its servlet's path, which can be below the context: the stream is at the context's root. */
    @Test
    void theStreamIsUnderTheContextPath() {
        String script = script("/shop");

        assertTrue(script.contains("new EventSource('/shop/.vidocq/dev/redefined')"), script);
    }

    @Test
    void aQuoteInTheContextPathCannotEndTheString() {
        String script = script("/a'b</script>");

        assertTrue(script.contains("new EventSource('/a\\'b\\u003c/script>/.vidocq/dev/redefined')"), script);
    }

    private static String script(String contextPath) {
        Document page = Document.createShell("");
        var request = (VaadinRequest) Proxy.newProxyInstance(VaadinRequest.class.getClassLoader(),
                new Class<?>[] {VaadinRequest.class},
                (proxy, method, args) -> "getContextPath".equals(method.getName()) ? contextPath : null);

        new RefreshScript().modifyIndexHtmlResponse(new IndexHtmlResponse(request, null, page));

        var scripts = page.head().select("script#" + RefreshScript.ID);
        assertEquals(1, scripts.size());
        return scripts.getFirst().data();
    }
}
