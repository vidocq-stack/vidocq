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
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

/**
 * The receipt of the basket: the servlet prepares the data, a JSP renders it. The page lives under {@code WEB-INF},
 * where a client cannot request it; only this forward reaches it.
 */
@WebServlet("/receipt")
public class ReceiptServlet extends HttpServlet {

    @Inject
    Basket basket;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        var lines = new ArrayList<ReceiptLine>();
        for (Map.Entry<Dish, Integer> line : basket.lines().entrySet()) {
            Dish dish = line.getKey();
            lines.add(new ReceiptLine(dish.name(), line.getValue(), Html.euros(dish.cents() * line.getValue())));
        }
        req.setAttribute("receipt", new Receipt(lines, Html.euros(basket.totalCents())));
        try {
            req.getRequestDispatcher("/WEB-INF/views/receipt.jsp").forward(req, resp);
        } catch (ServletException | IOException e) {
            Html.failed(resp, e);
        }
    }
}
