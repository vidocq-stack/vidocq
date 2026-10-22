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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.vidocq.bodoni.core.internal.Urls;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.tagext.BodyTagSupport;

/**
 * {@code <c:redirect>} (Tags 3.0 §7.6): sends an HTTP redirect to the client and aborts the
 * page by returning {@code SKIP_PAGE} from {@code doEndTag()}.
 *
 * <p>The URL is resolved like {@code <c:url>} does, then relative URLs go through
 * {@code encodeRedirectURL}.
 */
public class RedirectTag extends BodyTagSupport implements ParamParent {

    private static final long serialVersionUID = 1L;

    private String url;
    private String context;
    private final transient List<Map.Entry<String, String>> params = new ArrayList<>();

    /** @param url the URL to redirect to */
    public void setUrl(String url) {
        this.url = url;
    }

    /** @param context the name of a foreign context, starting with a slash */
    public void setContext(String context) {
        this.context = context;
    }

    @Override
    public void addParameter(String name, String value) {
        params.add(Map.entry(name, value));
    }

    @Override
    public int doStartTag() throws JspException {
        params.clear();
        return EVAL_BODY_BUFFERED;
    }

    @Override
    public int doEndTag() throws JspException {
        try {
            if (url == null) {
                throw new JspTagException("c:redirect: the url attribute is required");
            }
            HttpServletRequest request = (HttpServletRequest) pageContext.getRequest();
            HttpServletResponse response = (HttpServletResponse) pageContext.getResponse();
            String target = Urls.resolve(url, context, request);
            target = Urls.appendParameters(target, params, response.getCharacterEncoding());
            if (!Urls.isAbsolute(target)) {
                target = response.encodeRedirectURL(target);
            }
            response.sendRedirect(target);
            return SKIP_PAGE;
        } catch (IOException | IllegalArgumentException e) {
            throw new JspTagException("c:redirect: " + e.getMessage(), e);
        } finally {
            params.clear();
        }
    }

    @Override
    public void release() {
        super.release();
        url = null;
        context = null;
        params.clear();
    }
}
