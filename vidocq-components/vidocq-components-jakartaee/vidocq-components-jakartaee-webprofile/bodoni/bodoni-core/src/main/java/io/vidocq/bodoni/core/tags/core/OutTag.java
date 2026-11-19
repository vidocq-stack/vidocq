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

package io.vidocq.bodoni.core.tags.core;

import java.io.IOException;
import java.io.Reader;

import io.vidocq.bodoni.core.internal.Xml;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.JspWriter;
import jakarta.servlet.jsp.tagext.BodyTagSupport;

/**
 * {@code <c:out>} (Tags 3.0 §4.2): evaluates an expression and writes the result to the
 * current {@code JspWriter}, escaping XML special characters by default.
 *
 * <p>When {@code value} is {@code null} the {@code default} attribute is used, and when that
 * is {@code null} too the (trimmed) body content is. The fallback is escaped like the value.
 */
public class OutTag extends BodyTagSupport {

    private static final long serialVersionUID = 1L;

    private transient Object value;
    private transient Object defaultValue;
    private boolean escapeXml = true;
    private transient boolean buffered;

    /** @param value the value to write; a {@link Reader} is streamed */
    public void setValue(Object value) {
        this.value = value;
    }

    /** @param defaultValue the value written when {@code value} is {@code null} */
    public void setDefault(Object defaultValue) {
        this.defaultValue = defaultValue;
    }

    /** @param escapeXml whether {@code < > & ' "} are escaped (default {@code true}) */
    public void setEscapeXml(boolean escapeXml) {
        this.escapeXml = escapeXml;
    }

    @Override
    public int doStartTag() throws JspException {
        // An empty element gets no setBodyContent: never reuse a pooled handler's old body.
        bodyContent = null;
        buffered = false;
        try {
            if (value != null) {
                write(value);
                return SKIP_BODY;
            }
            if (defaultValue != null) {
                write(defaultValue);
                return SKIP_BODY;
            }
        } catch (IOException e) {
            throw new JspTagException(e.getMessage(), e);
        }
        buffered = true;
        return EVAL_BODY_BUFFERED;
    }

    @Override
    public int doEndTag() throws JspException {
        if (buffered && bodyContent != null) {
            try {
                write(bodyContent.getString().trim());
            } catch (IOException e) {
                throw new JspTagException(e.getMessage(), e);
            }
        }
        buffered = false;
        return EVAL_PAGE;
    }

    private void write(Object what) throws IOException {
        JspWriter out = pageContext.getOut();
        if (what instanceof Reader reader) {
            char[] buf = new char[1024];
            int n;
            while ((n = reader.read(buf)) != -1) {
                String chunk = new String(buf, 0, n);
                out.write(escapeXml ? Xml.escape(chunk) : chunk);
            }
            return;
        }
        String text = what.toString();
        out.write(escapeXml ? Xml.escape(text) : text);
    }

    @Override
    public void release() {
        super.release();
        value = null;
        defaultValue = null;
        escapeXml = true;
        buffered = false;
    }
}
