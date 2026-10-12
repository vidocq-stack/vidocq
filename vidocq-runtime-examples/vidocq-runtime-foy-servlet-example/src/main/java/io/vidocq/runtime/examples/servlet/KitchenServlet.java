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
import jakarta.servlet.annotation.HttpConstraint;
import jakarta.servlet.annotation.ServletSecurity;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/**
 * The kitchen page, for the role {@code chef} only. Without a {@code login-config}, Foy asks
 * for BASIC authentication; the user comes from {@code vidocq.servlet.users.*}.
 */
@WebServlet("/kitchen")
@ServletSecurity(@HttpConstraint(rolesAllowed = "chef"))
public class KitchenServlet extends HttpServlet {

    @Inject
    Kitchen kitchen;

    @Inject
    Basket basket;

    @Inject
    RequestTicket ticket;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        StringBuilder body = new StringBuilder("<h1>Kitchen</h1>")
                .append("<p>Signed in as <b>").append(Html.escape(req.getRemoteUser())).append("</b>. ")
                .append(kitchen.openSessions()).append(" open session(s), ")
                .append(kitchen.orders().size()).append(" order(s), revenue ")
                .append(Html.euros(kitchen.revenueCents())).append(".</p>");
        if (kitchen.orders().isEmpty()) {
            body.append("<p>No order yet.</p>");
        } else {
            body.append("<table><tr><th>#</th><th>Time</th><th>Dishes</th><th class=\"price\">Total</th></tr>");
            for (Kitchen.Order order : kitchen.orders()) {
                StringBuilder dishes = new StringBuilder();
                for (Map.Entry<Dish, Integer> line : order.lines().entrySet()) {
                    if (!dishes.isEmpty()) dishes.append(", ");
                    dishes.append(line.getValue()).append(" × ").append(Html.escape(line.getKey().name()));
                }
                body.append("<tr><td>").append(order.number()).append("</td><td>").append(order.at())
                        .append("</td><td>").append(dishes).append("</td><td class=\"price\">")
                        .append(Html.euros(order.totalCents())).append("</td></tr>");
            }
            body.append("</table>");
        }
        Html.page(req, resp, "Kitchen", basket, ticket, body.toString());
    }
}
