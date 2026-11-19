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
package io.vidocq.runtime.it.foyservlet;

import jakarta.inject.Inject;
import jakarta.servlet.ServletContext;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

/**
 * Answers with what the injected servlet built-in beans give (Vidocq/foy#107): the request URI and the
 * {@code X-Caller} header of the injected request, the visits counted in the injected session, whether that session is
 * the request's, the context path of the injected context, and what {@link ContextStartListener} read from it at
 * deployment.
 */
@WebServlet("/whoami")
public class WhoAmIServlet extends HttpServlet {

    private static final String VISITS = "visits";

    @Inject
    HttpServletRequest request;

    @Inject
    HttpSession session;

    @Inject
    ServletContext context;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Integer visits = (Integer) session.getAttribute(VISITS);
        int visit = visits == null ? 1 : visits + 1;
        session.setAttribute(VISITS, visit);
        String sameSession = session.getId().equals(req.getSession().getId()) ? "same session" : "another session";
        resp.setContentType("text/plain");
        resp.getWriter().write(String.join("|", request.getRequestURI(), request.getHeader("X-Caller"),
                String.valueOf(visit), sameSession, context.getContextPath(),
                String.valueOf(context.getAttribute(ContextStartListener.SEEN))));
    }
}
