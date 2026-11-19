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
package io.vidocq.runtime.examples.servlet;

import jakarta.inject.Inject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The FORM login page of {@code META-INF/web.xml}. Foy forwards a visitor who asks for the kitchen
 * here, and back here with {@code ?error} after a wrong password; the form posts to
 * {@code j_security_check}, which Foy handles.
 */
@WebServlet("/login")
public class LoginServlet extends HttpServlet {

    @Inject
    Basket basket;

    @Inject
    RequestTicket ticket;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        render(req, resp);
    }

    /** The error page answers the POST to {@code j_security_check} that Foy forwards here. */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) {
        render(req, resp);
    }

    private void render(HttpServletRequest req, HttpServletResponse resp) {
        StringBuilder body = new StringBuilder("<h1>Sign in</h1>");
        if (req.getParameter("error") != null) {
            body.append("<p class=\"notice\">Wrong user name or password.</p>");
        }
        body.append("<form method=\"post\" action=\"").append(req.getContextPath()).append("/j_security_check\">")
                .append("<p><label>User <input name=\"j_username\" autocomplete=\"username\" required></label></p>")
                .append("<p><label>Password <input type=\"password\" name=\"j_password\"")
                .append(" autocomplete=\"current-password\" required></label></p>")
                .append("<p><button>Sign in</button></p></form>")
                .append("<p>The kitchen is for the user <code>chef</code>, password <code>chef</code>.</p>");
        Html.page(req, resp, "Sign in", basket, ticket, body.toString());
    }
}
