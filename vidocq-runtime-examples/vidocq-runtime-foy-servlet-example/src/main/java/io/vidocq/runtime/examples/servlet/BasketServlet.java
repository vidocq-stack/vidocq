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

import java.io.IOException;
import java.util.Map;

/**
 * The basket of the session. A form POST adds or removes a dish, or sends the order to the
 * kitchen, then redirects back (POST, redirect, GET).
 */
@WebServlet("/basket")
public class BasketServlet extends HttpServlet {

    @Inject
    Menu menu;

    @Inject
    Basket basket;

    @Inject
    Kitchen kitchen;

    @Inject
    RequestTicket ticket;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        StringBuilder body = new StringBuilder("<h1>Your basket</h1>");
        String placed = req.getParameter("placed");
        if (placed != null) {
            body.append("<p class=\"notice\">Order #").append(Html.escape(placed))
                    .append(" sent to the kitchen. Bon appétit!</p>");
        }
        if (basket.count() == 0) {
            body.append("<p>The basket is empty. <a href=\"").append(req.getContextPath())
                    .append("/\">See the menu</a>.</p>");
        } else {
            String action = req.getContextPath() + "/basket";
            body.append("<table>");
            for (Map.Entry<Dish, Integer> line : basket.lines().entrySet()) {
                Dish dish = line.getKey();
                body.append("<tr><td>").append(line.getValue()).append(" × ").append(Html.escape(dish.name()))
                        .append("</td><td class=\"price\">").append(Html.euros(dish.cents() * line.getValue()))
                        .append("</td><td><form method=\"post\" action=\"").append(action).append("\">")
                        .append("<input type=\"hidden\" name=\"remove\" value=\"").append(dish.id()).append("\">")
                        .append("<button>Remove one</button></form></td></tr>");
            }
            body.append("<tr class=\"total\"><td>Total</td><td class=\"price\">")
                    .append(Html.euros(basket.totalCents())).append("</td><td></td></tr></table>")
                    .append("<form method=\"post\" action=\"").append(action).append("\">")
                    .append("<input type=\"hidden\" name=\"order\" value=\"1\"><button>Send to the kitchen</button></form>")
                    .append("<p><a href=\"").append(req.getContextPath()).append("/receipt\">See the receipt</a> (a JSP page)</p>");
        }
        Html.page(req, resp, "Basket", basket, ticket, body.toString());
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String add = req.getParameter("add");
        String remove = req.getParameter("remove");
        if (add != null) {
            menu.find(add).ifPresent(basket::add);
            resp.sendRedirect(req.getContextPath() + "/");
        } else if (remove != null) {
            menu.find(remove).ifPresent(basket::remove);
            resp.sendRedirect(req.getContextPath() + "/basket");
        } else if (req.getParameter("order") != null && basket.count() > 0) {
            Kitchen.Order order = kitchen.place(basket);
            basket.clear();
            resp.sendRedirect(req.getContextPath() + "/basket?placed=" + order.number());
        } else {
            resp.sendRedirect(req.getContextPath() + "/basket");
        }
    }
}
