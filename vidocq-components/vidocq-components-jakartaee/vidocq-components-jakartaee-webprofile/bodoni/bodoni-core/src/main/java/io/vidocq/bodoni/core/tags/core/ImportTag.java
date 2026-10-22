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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.vidocq.bodoni.core.internal.ExternalResources;
import io.vidocq.bodoni.core.internal.Scopes;
import io.vidocq.bodoni.core.internal.Urls;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
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
 * {@code context}, of a foreign web application; the included output is captured. Text the
 * resource writes through its {@code Writer} is taken as is; bytes it writes through its
 * {@code OutputStream} are decoded with {@code charEncoding}, else with the character encoding
 * the resource declares (content type or character encoding), else with ISO-8859-1. An
 * absolute URL is read through
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
    private String varName;
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

    /** @param name the name of the exported {@code String} variable */
    public void setVar(String name) {
        this.varName = name;
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
        // An empty element gets no setBodyContent: never reuse a pooled handler's old body.
        bodyContent = null;
        params.clear();
        openReader = null;
        if (url == null || url.isEmpty()) {
            throw new JspTagException("c:import: the url attribute is required and must not be empty");
        }
        if (varName != null) {
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
            if (varName != null) {
                pageContext.setAttribute(varName, text, scopeConstant());
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
            } catch (IOException _) {
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
                return openExternal(Urls.appendParameters(url, params, responseEncoding));
            }
            return new StringReader(include(Urls.appendParameters(Urls.stripSessionId(url), params, responseEncoding)));
        } catch (IllegalArgumentException e) {
            throw new JspTagException("c:import: " + e.getMessage(), e);
        }
    }

    private Reader openExternal(String target) throws JspTagException {
        try {
            return external.open(target, charEncoding);
        } catch (IOException e) {
            throw new JspTagException("c:import: cannot read " + target + ": " + e.getMessage(), e);
        }
    }

    private String include(String path) throws JspTagException {
        RequestDispatcher dispatcher;
        if (context != null) {
            Urls.requireContextRelative(url, context);
            ServletContext foreign = pageContext.getServletContext().getContext(context);
            if (foreign == null) {
                throw new JspTagException("c:import: unknown context " + context);
            }
            dispatcher = foreign.getRequestDispatcher(path);
        } else {
            dispatcher = pageContext.getRequest().getRequestDispatcher(path);
        }
        if (dispatcher == null) {
            throw new JspTagException("c:import: no resource found for " + path);
        }
        CapturingResponse capture = new CapturingResponse((HttpServletResponse) pageContext.getResponse());
        try {
            dispatcher.include(pageContext.getRequest(), capture);
        } catch (ServletException e) {
            Throwable root = e.getRootCause();
            if (root != null) {
                throw new JspTagException("c:import: cannot include " + path + ": " + root.getMessage(), root);
            }
            throw new JspTagException("c:import: cannot include " + path + ": " + e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            throw new JspTagException("c:import: cannot include " + path + ": " + e.getMessage(), e);
        }
        int status = capture.status();
        if (status < 200 || status >= 300) {
            throw new JspTagException("c:import: " + path + " answered with status " + status);
        }
        return capture.content(charEncoding);
    }

    @Override
    public void release() {
        super.release();
        url = null;
        context = null;
        varName = null;
        scope = null;
        charEncoding = null;
        varReader = null;
        params.clear();
        openReader = null;
    }

    /**
     * Captures what an included resource writes, and its status, instead of sending it. An
     * include must not change the importing page's response, so nothing here reaches the
     * wrapped response: status, redirects, errors, reset and flush calls only touch the
     * capture, and headers, cookies, content length and locale are dropped. Content type and
     * character encoding are recorded, not forwarded. A redirect or error requested by the resource is recorded as its status (302
     * for the overloads without a status), which makes the import fail as for any non-2xx
     * outcome. The character encoding the resource declares is remembered and used to decode
     * bytes when {@code charEncoding} is not set; the default is ISO-8859-1 (§7.4).
     */
    private static final class CapturingResponse extends HttpServletResponseWrapper {

        private StringWriter chars = new StringWriter();
        private ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private PrintWriter writer;
        private ServletOutputStream stream;
        private int status = SC_OK;
        private String declaredEncoding;
        private String contentType;

        CapturingResponse(HttpServletResponse response) {
            super(response);
        }

        int status() {
            return status;
        }

        String content(String charEncoding) {
            if (writer != null) {
                writer.flush();
                return chars.toString();
            }
            String name = charEncoding != null && !charEncoding.isBlank() ? charEncoding : declaredEncoding;
            Charset charset = name == null ? StandardCharsets.ISO_8859_1 : Charset.forName(name.trim());
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
        public void sendRedirect(String location) {
            this.status = SC_FOUND;
        }

        @Override
        public void sendRedirect(String location, int sc) {
            this.status = sc;
        }

        @Override
        public void sendRedirect(String location, boolean clearBuffer) {
            this.status = SC_FOUND;
        }

        @Override
        public void sendRedirect(String location, int sc, boolean clearBuffer) {
            this.status = sc;
        }

        @Override
        public void reset() {
            resetBuffer();
            status = SC_OK;
        }

        @Override
        public void resetBuffer() {
            if (writer != null) {
                writer.flush();
            }
            chars.getBuffer().setLength(0);
            bytes.reset();
        }

        @Override
        public void flushBuffer() {
            // everything stays in the capture buffer until the import completes
        }

        @Override
        public void setContentType(String type) {
            this.contentType = type;
            if (type != null && type.toLowerCase(Locale.ROOT).contains("charset=")) {
                this.declaredEncoding = ExternalResources.charsetOf(null, type).name();
            }
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public void setCharacterEncoding(String encoding) {
            this.declaredEncoding = encoding;
        }

        @Override
        public String getCharacterEncoding() {
            return declaredEncoding == null ? "ISO-8859-1" : declaredEncoding;
        }

        // Headers, cookies, length and locale of an included resource are ignored, as the
        // Servlet include contract asks: nothing is forwarded to the importing response.

        @Override
        public void setContentLength(int len) {
            // ignored
        }

        @Override
        public void setContentLengthLong(long len) {
            // ignored
        }

        @Override
        public void setHeader(String name, String value) {
            // ignored
        }

        @Override
        public void addHeader(String name, String value) {
            // ignored
        }

        @Override
        public void setIntHeader(String name, int value) {
            // ignored
        }

        @Override
        public void addIntHeader(String name, int value) {
            // ignored
        }

        @Override
        public void setDateHeader(String name, long date) {
            // ignored
        }

        @Override
        public void addDateHeader(String name, long date) {
            // ignored
        }

        @Override
        public void addCookie(Cookie cookie) {
            // ignored
        }

        @Override
        public void setLocale(Locale locale) {
            // ignored
        }
    }
}
