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

import jakarta.servlet.jsp.tagext.SimpleTagSupport;

import java.io.IOException;

/**
 * {@code <bistro:out value="${...}" escapeXml="true"/>}: writes a value, HTML-escaped unless {@code escapeXml} is
 * {@code false}, as JSTL's {@code <c:out>} does (Ibarra has no JSTL in this example). A {@code null} value writes
 * nothing.
 */
public final class OutTag extends SimpleTagSupport {

    private Object value;
    private boolean escapeXml = true;

    public void setValue(Object value) {
        this.value = value;
    }

    public void setEscapeXml(boolean escapeXml) {
        this.escapeXml = escapeXml;
    }

    @Override
    public void doTag() throws IOException {
        if (value == null) return;
        String text = String.valueOf(value);
        getJspContext().getOut().write(escapeXml ? Html.escape(text) : text);
    }
}
