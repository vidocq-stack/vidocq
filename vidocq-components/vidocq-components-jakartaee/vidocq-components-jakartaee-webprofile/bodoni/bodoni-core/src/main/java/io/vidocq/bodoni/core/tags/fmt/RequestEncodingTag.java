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

package io.vidocq.bodoni.core.tags.fmt;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

import io.vidocq.bodoni.core.internal.I18n;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.tagext.TagSupport;

/**
 * {@code <fmt:requestEncoding>} (Tags 3.0 §8.10): sets the character encoding used to decode the
 * request parameters.
 *
 * <p>Without {@code value}, the encoding the request already declares (its {@code Content-Type}
 * charset) is kept; else the session's {@code jakarta.servlet.jsp.jstl.fmt.request.charset}
 * attribute is used; else {@code ISO-8859-1}.
 */
public class RequestEncodingTag extends TagSupport {

    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_ENCODING = "ISO-8859-1";

    private String value;

    /** @param value the name of the character encoding; absent or empty to infer it */
    public void setValue(String value) {
        this.value = value;
    }

    @Override
    public int doStartTag() throws JspException {
        ServletRequest request = pageContext.getRequest();
        String encoding = value;
        if (encoding == null || encoding.isEmpty()) {
            if (request.getCharacterEncoding() != null) {
                return SKIP_BODY;
            }
            encoding = sessionCharset(request);
        }
        try {
            if (!Charset.isSupported(encoding)) {
                throw new JspTagException("fmt:requestEncoding: unsupported character encoding: " + encoding);
            }
            request.setCharacterEncoding(encoding);
        } catch (IllegalArgumentException | UnsupportedEncodingException e) {
            throw new JspTagException("fmt:requestEncoding: unsupported character encoding: " + encoding, e);
        }
        return SKIP_BODY;
    }

    /** The charset recorded by an i18n action in an existing session; never creates a session. */
    private static String sessionCharset(ServletRequest request) {
        if (request instanceof HttpServletRequest http) {
            HttpSession session = http.getSession(false);
            if (session != null) {
                try {
                    if (session.getAttribute(I18n.REQUEST_CHARSET) instanceof String charset) {
                        return charset;
                    }
                } catch (IllegalStateException _) {
                    // invalidated session: behave as if there were none
                }
            }
        }
        return DEFAULT_ENCODING;
    }

    @Override
    public void release() {
        super.release();
        value = null;
    }
}
