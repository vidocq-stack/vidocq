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

/**
 * Refreshes the open Vaadin pages that show a class {@code vidocq:dev} redefined in place. A dev tool: only
 * {@code vidocq:dev} adds it, in the application layer next to Vaadin, and no binary contains it.
 */
module io.vidocq.runtime.extensions.jakartaee.web.vaadin.dev {
    // flow-server under the two names its versions give it, as the Vaadin extension reads it.
    requires static flow.server;
    requires static com.vaadin.flow.server;
    // The document of IndexHtmlResponse.
    requires org.jsoup;
    requires jakarta.servlet;
    requires io.vidocq.runtime.spi.devconsole;

    provides io.vidocq.runtime.spi.devconsole.RedefinitionListener
            with io.vidocq.runtime.extensions.jakartaee.web.vaadin.dev.VaadinRedefinitionListener;
    provides com.vaadin.flow.server.VaadinServiceInitListener
            with io.vidocq.runtime.extensions.jakartaee.web.vaadin.dev.DevServiceInit;
    provides jakarta.servlet.ServletContainerInitializer
            with io.vidocq.runtime.extensions.jakartaee.web.vaadin.dev.RedefinedEvents.Initializer;

    // Vaadin and the servlet container create these reflectively.
    exports io.vidocq.runtime.extensions.jakartaee.web.vaadin.dev;
}
