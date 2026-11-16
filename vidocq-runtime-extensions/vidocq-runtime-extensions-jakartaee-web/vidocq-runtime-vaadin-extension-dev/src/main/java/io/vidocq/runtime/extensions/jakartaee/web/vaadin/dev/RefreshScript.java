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

import com.vaadin.flow.server.communication.IndexHtmlRequestListener;
import com.vaadin.flow.server.communication.IndexHtmlResponse;
import org.jsoup.nodes.DataNode;

import java.io.Serial;

/**
 * The script each page gets: on each event of {@link RedefinedEvents}, every Flow client of the page asks the server
 * for a round trip, which brings back the reload {@link VaadinRedefinitionListener} queued. With push on, the reload
 * has already arrived, and the round trip changes nothing.
 */
final class RefreshScript implements IndexHtmlRequestListener {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The id of the script element. */
    static final String ID = "vidocq-dev-refresh";

    private static final String SCRIPT = """
            (() => {
              const stream = new EventSource(new URL('.vidocq/dev/redefined', document.baseURI));
              stream.onmessage = () => {
                const clients = (window.Vaadin && window.Vaadin.Flow && window.Vaadin.Flow.clients) || {};
                Object.values(clients).forEach(client => client.poll && client.poll());
              };
            })();
            """;

    @Override
    public void modifyIndexHtmlResponse(IndexHtmlResponse response) {
        response.getDocument().head().appendElement("script").id(ID).appendChild(new DataNode(SCRIPT));
    }
}
