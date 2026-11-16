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

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.Command;
import com.vaadin.flow.server.SessionExpiredException;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinServletService;
import com.vaadin.flow.server.VaadinSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * A Vaadin service and session as a swap that kept the HTTP sessions leaves them: the session is in the HTTP session,
 * under the attribute Vaadin names after its service, and the service finds it for a request of that page.
 */
final class RestoredSessions {

    static final String SERVICE_NAME = "vaadin";

    /** The attribute under which Vaadin keeps the session of the service {@value #SERVICE_NAME}. */
    static final String ATTRIBUTE = VaadinSession.class.getName() + "." + SERVICE_NAME;

    private RestoredSessions() {
    }

    /** The request of a page whose HTTP session holds {@code attributes}; a request without one when null. */
    static HttpServletRequest request(Map<String, Object> attributes) {
        HttpSession session = attributes == null ? null : Stubs.stub(HttpSession.class,
                (method, args) -> "getAttribute".equals(method) ? attributes.get((String) args[0]) : null);
        return Stubs.stub(HttpServletRequest.class, (method, args) -> "getSession".equals(method) ? session : null);
    }

    /** Finds {@code session} for any request, or none when it is {@code null}: the session expired. */
    static final class Service extends VaadinServletService {

        @Serial
        private static final long serialVersionUID = 1L;

        private final VaadinSession session;
        int lookups;

        Service(VaadinSession session) {
            super(null, null);
            this.session = session;
        }

        @Override
        public String getServiceName() {
            return SERVICE_NAME;
        }

        @Override
        public VaadinSession findVaadinSession(VaadinRequest request) throws SessionExpiredException {
            lookups++;
            if (session == null) {
                throw new SessionExpiredException();
            }
            return session;
        }
    }

    /** Holds {@code uis}, and runs an access at once, as a session nobody holds does. */
    static final class Session extends VaadinSession {

        @Serial
        private static final long serialVersionUID = 1L;

        private final transient List<UI> uis;

        Session(UI... uis) {
            super(new VaadinServletService(null, null));
            this.uis = List.of(uis);
        }

        @Override
        public Future<Void> access(Command command) {
            command.execute();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public Collection<UI> getUIs() {
            return uis;
        }
    }
}
