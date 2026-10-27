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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Locale;

/** The page layout shared by the servlets, and two formatting helpers. */
final class Html {

    private static final System.Logger LOG = System.getLogger(Html.class.getName());

    private static final String STYLE = """
            body{font-family:system-ui,sans-serif;max-width:44rem;margin:2rem auto;padding:0 1rem;color:#222}
            nav{display:flex;gap:1rem;align-items:baseline;border-bottom:1px solid #ddd;padding-bottom:.5rem}
            nav .brand{font-weight:700;margin-right:auto}
            table{border-collapse:collapse;width:100%;margin:1rem 0}
            td,th{padding:.4rem;border-bottom:1px solid #eee;text-align:left}
            .price{text-align:right;white-space:nowrap}
            .total td{font-weight:700}
            .notice{background:#eef7ee;border:1px solid #9c9;padding:.5rem}
            footer{margin-top:2rem;color:#888;font-size:.85em}
            form{margin:0}
            """;

    private Html() {
    }

    static void page(HttpServletRequest req, HttpServletResponse resp, String title, Basket basket,
                     RequestTicket ticket, String body) {
        String root = req.getContextPath();
        resp.setContentType("text/html;charset=UTF-8");
        try {
            resp.getWriter().write("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<title>" + escape(title) + " · Vidocq Bistro</title><style>" + STYLE + "</style></head><body>"
                    + "<nav><span class=\"brand\">Vidocq Bistro</span>"
                    + "<a href=\"" + root + "/\">Menu</a>"
                    + "<a href=\"" + root + "/basket\">Basket (" + basket.count() + ")</a>"
                    + "<a href=\"" + root + "/kitchen\">Kitchen</a>"
                    + "<a href=\"" + root + "/about.jsp\">About</a>" + account(req) + "</nav>"
                    + body
                    + "<footer>Session " + escape(req.getSession().getId().substring(0, 8))
                    + " · request " + ticket.id() + " · " + ticket.elapsedMicros() + " µs"
                    + " · Jakarta Servlet " + req.getServletContext().getMajorVersion() + "."
                    + req.getServletContext().getMinorVersion() + " by Foy on Chappe</footer>"
                    + "</body></html>");
        } catch (IOException e) {
            failed(resp, e);
        }
    }

    /** Redirects the visitor to {@code location}. */
    static void redirect(HttpServletResponse resp, String location) {
        try {
            resp.sendRedirect(location);
        } catch (IOException e) {
            failed(resp, e);
        }
    }

    /**
     * An answer that could not be given: the visitor went away, or the container failed. It is logged, and turned
     * into a 500 while the response is not committed yet.
     */
    static void failed(HttpServletResponse resp, Exception e) {
        LOG.log(System.Logger.Level.WARNING, "Could not answer the request", e);
        if (!resp.isCommitted()) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    /** The signed-in user and a logout button, or a link to the login page. */
    private static String account(HttpServletRequest req) {
        String root = req.getContextPath();
        String user = req.getRemoteUser();
        if (user == null) {
            return "<a href=\"" + root + "/login\">Sign in</a>";
        }
        return "<span>" + escape(user) + "</span><form method=\"post\" action=\"" + root + "/logout\">"
                + "<button>Sign out</button></form>";
    }

    static String euros(int cents) {
        return String.format(Locale.ROOT, "€%d.%02d", cents / 100, cents % 100);
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
