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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.vidocq.bodoni.core.internal.ExternalResources;
import io.vidocq.bodoni.core.internal.Scopes;
import io.vidocq.bodoni.core.internal.Urls;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.tagext.BodyTagSupport;
import jakarta.servlet.jsp.tagext.TryCatchFinally;

/**
 * {@code <c:import>} (Tags 3.0 §7.4): imports the content of a URL-based resource.
 *
 * <p>A relative URL is included through a {@code RequestDispatcher} of the current or, with
 * {@code context}, of a foreign web application; the included output is captured and decoded
 * with {@code charEncoding} (default: the response encoding). An absolute URL is read through
 * {@link ExternalResources}. Nested {@code <c:param>} extend the query string, except with
 * {@code varReader}, where the resource is opened at the start tag and the exported
 * {@link Reader} is visible in the body only and closed afterwards.
 *
 * <p>A resource answering a status outside 2xx makes the action fail with a
 * {@link JspTagException}.
 */
public class ImportTag extends BodyTagSupport implements ParamParent, TryCatchFinally {

    private static final long serialVersionUID = 1L;

    private final transient ExternalResources external;
    private String url;
    private String context;
    private String var;
    private String scope;
    private String charEncoding;
    private String varReader;
    private final transient List<Map.Entry<String, String>> params = new ArrayList<>();
    private transient Reader openReader;

    /** Uses the JDK to open absolute URLs. */
    public ImportTag() {
        this(ExternalResources.jdk());
    }

    ImportTag(ExternalResources external) {
        this.external = external;
    }

    /** @param url the URL of the resource to import */
    public void setUrl(String url) {
        this.url = url;
    }

    /** @param context the name of a foreign context, starting with a slash */
    public void setContext(String context) {
        this.context = context;
    }

    /** @param var the name of the exported {@code String} variable */
    public void setVar(String var) {
        this.var = var;
    }

    /** @param scope {@code page}, {@code request}, {@code session} or {@code application} */
    public void setScope(String scope) {
        this.scope = scope;
    }

    /** @param charEncoding the character encoding of the resource */
    public void setCharEncoding(String charEncoding) {
        this.charEncoding = charEncoding;
    }

    /** @param varReader the name of the {@code Reader} variable, visible in the body only */
    public void setVarReader(String varReader) {
        this.varReader = varReader;
    }

    @Override
    public void addParameter(String name, String value) {
        if (varReader != null) {
            throw new IllegalStateException("nested c:param is not allowed in c:import with varReader");
        }
        params.add(Map.entry(name, value));
    }

    @Override
    public int doStartTag() throws JspException {
        params.clear();
        openReader = null;
        if (url == null || url.isEmpty()) {
            throw new JspTagException("c:import: the url attribute is required and must not be empty");
        }
        if (var != null) {
            scopeConstant();
        }
        if (varReader == null) {
            return EVAL_BODY_BUFFERED;
        }
        openReader = open();
        pageContext.setAttribute(varReader, openReader, PageContext.PAGE_SCOPE);
        return EVAL_BODY_INCLUDE;
    }

    @Override
    public int doEndTag() throws JspException {
        if (varReader != null) {
            return EVAL_PAGE;
        }
        String text;
        try (Reader reader = open()) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            text = sb.toString();
            if (var != null) {
                pageContext.setAttribute(var, text, scopeConstant());
            } else {
                pageContext.getOut().write(text);
            }
        } catch (IOException e) {
            throw new JspTagException("c:import: cannot read " + url + ": " + e.getMessage(), e);
        } finally {
            params.clear();
        }
        return EVAL_PAGE;
    }

    @Override
    public void doCatch(Throwable t) throws Throwable {
        throw t;
    }

    @Override
    public void doFinally() {
        Reader reader = openReader;
        openReader = null;
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                // nothing useful to do while unwinding
            }
        }
        if (varReader != null && pageContext != null) {
            pageContext.removeAttribute(varReader, PageContext.PAGE_SCOPE);
        }
    }

    private int scopeConstant() throws JspTagException {
        try {
            return Scopes.of(scope);
        } catch (IllegalArgumentException e) {
            throw new JspTagException("c:import: " + e.getMessage(), e);
        }
    }

    private Reader open() throws JspTagException {
        String responseEncoding = pageContext.getResponse().getCharacterEncoding();
        try {
            if (Urls.isAbsolute(url)) {
                String target = Urls.appendParameters(url, params, responseEncoding);
                try {
                    return external.open(target, charEncoding);
                } catch (IOException e) {
                    throw new JspTagException("c:import: cannot read " + target + ": " + e.getMessage(), e);
                }
            }
            return new StringReader(include(Urls.appendParameters(url, params, responseEncoding), responseEncoding));
        } catch (IllegalArgumentException e) {
            throw new JspTagException("c:import: " + e.getMessage(), e);
        }
    }

    private String include(String path, String responseEncoding) throws JspTagException {
        RequestDispatcher dispatcher;
        if (context != null) {
            Urls.requireContextRelative(url, context);
            ServletContext foreign = pageContext.getServletContext().getContext(context);
            if (foreign == null) {
                throw new JspTagException("c:import: unknown context " + context);
            }
            dispatcher = foreign.getRequestDispatcher(path);
        } else {
            dispatcher = ((HttpServletRequest) pageContext.getRequest()).getRequestDispatcher(path);
        }
        if (dispatcher == null) {
            throw new JspTagException("c:import: no resource found for " + path);
        }
        String encoding = charEncoding != null && !charEncoding.isBlank() ? charEncoding : responseEncoding;
        Charset charset = encoding == null ? Charset.forName("ISO-8859-1") : Charset.forName(encoding.trim());
        CapturingResponse capture = new CapturingResponse((HttpServletResponse) pageContext.getResponse());
        try {
            dispatcher.include(pageContext.getRequest(), capture);
        } catch (ServletException | IOException e) {
            throw new JspTagException("c:import: cannot include " + path + ": " + e.getMessage(), e);
        }
        int status = capture.status();
        if (status < 200 || status >= 300) {
            throw new JspTagException("c:import: " + path + " answered with status " + status);
        }
        return capture.content(charset);
    }

    @Override
    public void release() {
        super.release();
        url = null;
        context = null;
        var = null;
        scope = null;
        charEncoding = null;
        varReader = null;
        params.clear();
        openReader = null;
    }

    /** Captures what an included resource writes, and its status, instead of sending it. */
    private static final class CapturingResponse extends HttpServletResponseWrapper {

        private final StringWriter chars = new StringWriter();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private PrintWriter writer;
        private ServletOutputStream stream;
        private int status = SC_OK;

        CapturingResponse(HttpServletResponse response) {
            super(response);
        }

        int status() {
            return status;
        }

        String content(Charset charset) {
            if (writer != null) {
                writer.flush();
                return chars.toString();
            }
            return bytes.toString(charset);
        }

        @Override
        public PrintWriter getWriter() {
            if (stream != null) {
                throw new IllegalStateException("getOutputStream() was already called");
            }
            if (writer == null) {
                writer = new PrintWriter(chars);
            }
            return writer;
        }

        @Override
        public ServletOutputStream getOutputStream() {
            if (writer != null) {
                throw new IllegalStateException("getWriter() was already called");
            }
            if (stream == null) {
                stream = new ServletOutputStream() {
                    @Override
                    public void write(int b) {
                        bytes.write(b);
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setWriteListener(WriteListener listener) {
                        // synchronous buffer: always ready
                    }
                };
            }
            return stream;
        }

        @Override
        public void setStatus(int sc) {
            this.status = sc;
        }

        @Override
        public int getStatus() {
            return status;
        }

        @Override
        public void sendError(int sc) {
            this.status = sc;
        }

        @Override
        public void sendError(int sc, String msg) {
            this.status = sc;
        }

        @Override
        public void sendRedirect(String location, int sc, boolean clearBuffer) {
            this.status = sc;
        }

        @Override
        public void setContentType(String type) {
            // an included resource cannot change the importing page's headers
        }

        @Override
        public void setCharacterEncoding(String encoding) {
            // an included resource cannot change the importing page's headers
        }
    }
}
