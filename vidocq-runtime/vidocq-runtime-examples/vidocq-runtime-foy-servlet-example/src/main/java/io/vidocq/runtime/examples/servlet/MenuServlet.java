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

/** The home page: the menu, each dish with a button that adds it to the basket. */
@WebServlet("")
public class MenuServlet extends HttpServlet {

    @Inject
    Menu menu;

    @Inject
    Basket basket;

    @Inject
    RequestTicket ticket;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        StringBuilder body = new StringBuilder("<h1>Today's menu</h1><table>");
        for (Dish dish : menu.dishes()) {
            body.append("<tr><td>").append(Html.escape(dish.name())).append("</td><td class=\"price\">")
                    .append(Html.euros(dish.cents())).append("</td><td>")
                    .append("<form method=\"post\" action=\"").append(req.getContextPath()).append("/basket\">")
                    .append("<input type=\"hidden\" name=\"add\" value=\"").append(dish.id()).append("\">")
                    .append("<button>Add</button></form></td></tr>");
        }
        body.append("</table>");
        Html.page(req, resp, "Menu", basket, ticket, body.toString());
    }
}
