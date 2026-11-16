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
import com.vaadin.flow.server.SessionExpiredException;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinServletService;
import com.vaadin.flow.server.VaadinSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The open pages of the application: the Vaadin service adds each UI as it starts ({@link DevServiceInit}), and a UI
 * is open until it closes. The one of the application is {@link DevServiceInit#OPEN_UIS}, in the application layer,
 * so a layer swap drops it with the pages it held.
 *
 * <p>A UI holds no listener of this module: at a swap that keeps the HTTP sessions, the servlet container writes them
 * out with their UIs, and a class of this module would stop it (BUG-20261116-09). The set holds the UIs weakly
 * instead, so one that left its session goes with it. The pages such a swap restored never start in this layer:
 * their UIs open when the page asks for the event stream ({@link #restored}).
 */
final class OpenUis {

    private final Set<UI> open = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final Set<VaadinServletService> services = ConcurrentHashMap.newKeySet();

    void opened(UI ui) {
        open.add(ui);
    }

    /** A Vaadin service of the application, which reads the session of a restored page. */
    void serving(VaadinServletService service) {
        services.add(service);
    }

    /**
     * Opens the UIs of the page that sent {@code request}, the event stream: the first request of a page that a swap
     * restored. Vaadin reads its session again on the way, as for any request of the page.
     */
    void restored(HttpServletRequest request) {
        HttpSession http = request.getSession(false);
        if (http == null) {
            return;
        }
        for (VaadinServletService service : services) {
            // Only a session the page has: asked for one it does not have, Vaadin would create it for this request.
            if (http.getAttribute(VaadinSession.class.getName() + "." + service.getServiceName()) != null) {
                open(service, request);
            }
        }
    }

    private void open(VaadinServletService service, HttpServletRequest request) {
        try {
            VaadinSession session = service.findVaadinSession(new VaadinServletRequest(request, service));
            session.access(() -> session.getUIs().forEach(this::opened));
        } catch (SessionExpiredException _) {
            // The session ended since: no page to refresh.
        }
    }

    /** The UIs opened here that are not closing. */
    List<UI> all() {
        synchronized (open) {
            return open.stream().filter(ui -> !ui.isClosing()).toList();
        }
    }
}
