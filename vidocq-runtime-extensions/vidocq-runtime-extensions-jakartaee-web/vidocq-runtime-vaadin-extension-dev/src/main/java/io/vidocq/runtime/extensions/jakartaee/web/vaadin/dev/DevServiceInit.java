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

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.UIInitEvent;
import com.vaadin.flow.server.UIInitListener;
import com.vaadin.flow.server.VaadinServiceInitListener;

import java.io.Serial;

/** Keeps track of the open pages, and gives each one the script that lets a page without push refresh. */
public final class DevServiceInit implements VaadinServiceInitListener, UIInitListener {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * The open pages of this application layer. Vaadin creates this listener, and {@code ServiceLoader} the
     * {@link VaadinRedefinitionListener} of each redefinition: the module is what they share.
     */
    static final OpenUis OPEN_UIS = new OpenUis();

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().addUIInitListener(this);
        event.addIndexHtmlRequestListener(new RefreshScript());
    }

    @Override
    public void uiInit(UIInitEvent event) {
        OPEN_UIS.opened(event.getUI());
    }
}
