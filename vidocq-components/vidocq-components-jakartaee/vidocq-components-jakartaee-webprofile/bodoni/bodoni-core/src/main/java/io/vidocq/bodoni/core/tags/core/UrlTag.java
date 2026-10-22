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

import io.vidocq.bodoni.core.internal.Scopes;
import io.vidocq.bodoni.core.internal.Urls;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.tagext.BodyTagSupport;

/**
 * {@code <c:url>} (Tags 3.0 §7.5): builds a URL, prefixing the context path of a
 * context-relative URL, appending the nested {@code <c:param>} parameters, and rewriting
 * relative URLs with {@code HttpServletResponse.encodeURL}. Absolute URLs are never passed
 * to {@code encodeURL}, so a session id cannot leak to an external site.
 *
 * <p>The result is written to the page, or exported as a {@code String} when {@code var}
 * is set. A {@code null} {@code value} is an empty URL.
 */
public class UrlTag extends BodyTagSupport implements ParamParent {

    private static final long serialVersionUID = 1L;

    private String value;
    private String context;
    private String var;
    private String scope;
    private final transient List<Map.Entry<String, String>> params = new ArrayList<>();

    /** @param value the URL to process */
    public void setValue(String value) {
        this.value = value;
    }

    /** @param context the name of a foreign context, starting with a slash */
    public void setContext(String context) {
        this.context = context;
    }

    /** @param var the name of the exported scoped variable */
    public void setVar(String var) {
        this.var = var;
    }

    /** @param scope {@code page}, {@code request}, {@code session} or {@code application} */
    public void setScope(String scope) {
        this.scope = scope;
    }

    @Override
    public void addParameter(String name, String paramValue) {
        params.add(Map.entry(name, paramValue));
    }

    @Override
    public int doStartTag() throws JspException {
        // An empty element gets no setBodyContent: never reuse a pooled handler's old body.
        bodyContent = null;
        params.clear();
        if (var != null) {
            scopeConstant();
        }
        return EVAL_BODY_BUFFERED;
    }

    @Override
    public int doEndTag() throws JspException {
        try {
            String url = value == null ? "" : value;
            HttpServletRequest request = (HttpServletRequest) pageContext.getRequest();
            HttpServletResponse response = (HttpServletResponse) pageContext.getResponse();
            String result = Urls.resolve(url, context, request);
            result = Urls.appendParameters(result, params, response.getCharacterEncoding());
            if (!Urls.isAbsolute(result)) {
                result = response.encodeURL(result);
            }
            if (var != null) {
                pageContext.setAttribute(var, result, scopeConstant());
            } else {
                pageContext.getOut().print(result);
            }
            return EVAL_PAGE;
        } catch (IOException | IllegalArgumentException e) {
            throw new JspTagException("c:url: " + e.getMessage(), e);
        } finally {
            params.clear();
        }
    }

    private int scopeConstant() throws JspTagException {
        try {
            return Scopes.of(scope);
        } catch (IllegalArgumentException e) {
            throw new JspTagException("c:url: " + e.getMessage(), e);
        }
    }

    @Override
    public void release() {
        super.release();
        value = null;
        context = null;
        var = null;
        scope = null;
        params.clear();
    }
}
