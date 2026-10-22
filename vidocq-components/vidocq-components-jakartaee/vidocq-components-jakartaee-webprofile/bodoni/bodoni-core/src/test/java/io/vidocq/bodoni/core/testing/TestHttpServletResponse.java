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

package io.vidocq.bodoni.core.testing;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

/**
 * An {@link HttpServletResponse} double. {@code encodeURL} and {@code encodeRedirectURL}
 * append {@code ;jsessionid=T} and record the original argument; {@code sendRedirect}
 * records its target; the status defaults to 200 and the character encoding to
 * {@code ISO-8859-1}. {@code getWriter()} is backed by a string readable through
 * {@link #body()}. Everything else throws {@link UnsupportedOperationException}.
 */
public class TestHttpServletResponse implements HttpServletResponse {

    private final List<String> encodedUrls = new ArrayList<>();
    private final List<String> encodedRedirectUrls = new ArrayList<>();
    private final StringWriter body = new StringWriter();
    private final PrintWriter writer = new PrintWriter(body);
    private int status = SC_OK;
    private String redirect;
    private String characterEncoding = "ISO-8859-1";
    private String contentType;

    /** Arguments of {@code encodeURL}, in call order. */
    public List<String> encodedUrls() {
        return List.copyOf(encodedUrls);
    }

    /** Arguments of {@code encodeRedirectURL}, in call order. */
    public List<String> encodedRedirectUrls() {
        return List.copyOf(encodedRedirectUrls);
    }

    /** The last {@code sendRedirect} target, or {@code null}. */
    public String redirect() {
        return redirect;
    }

    /** What was written through {@code getWriter()}. */
    public String body() {
        writer.flush();
        return body.toString();
    }

    @Override
    public String encodeURL(String url) {
        encodedUrls.add(url);
        return url + ";jsessionid=T";
    }

    @Override
    public String encodeRedirectURL(String url) {
        encodedRedirectUrls.add(url);
        return url + ";jsessionid=T";
    }

    @Override
    public void sendRedirect(String location, int sc, boolean clearBuffer) {
        this.redirect = location;
        this.status = sc;
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
    public String getCharacterEncoding() {
        return characterEncoding;
    }

    @Override
    public void setCharacterEncoding(String encoding) {
        this.characterEncoding = encoding;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public void setContentType(String type) {
        this.contentType = type;
    }

    @Override
    public PrintWriter getWriter() {
        return writer;
    }

    @Override
    public void addCookie(Cookie cookie) {
        throw nope();
    }

    @Override
    public boolean containsHeader(String name) {
        throw nope();
    }

    @Override
    public void sendError(int sc, String msg) {
        throw nope();
    }

    @Override
    public void sendError(int sc) {
        throw nope();
    }

    @Override
    public void setDateHeader(String name, long date) {
        throw nope();
    }

    @Override
    public void addDateHeader(String name, long date) {
        throw nope();
    }

    @Override
    public void setHeader(String name, String value) {
        throw nope();
    }

    @Override
    public void addHeader(String name, String value) {
        throw nope();
    }

    @Override
    public void setIntHeader(String name, int value) {
        throw nope();
    }

    @Override
    public void addIntHeader(String name, int value) {
        throw nope();
    }

    @Override
    public String getHeader(String name) {
        throw nope();
    }

    @Override
    public Collection<String> getHeaders(String name) {
        throw nope();
    }

    @Override
    public Collection<String> getHeaderNames() {
        throw nope();
    }

    @Override
    public ServletOutputStream getOutputStream() {
        throw nope();
    }

    @Override
    public void setContentLength(int len) {
        throw nope();
    }

    @Override
    public void setContentLengthLong(long len) {
        throw nope();
    }

    @Override
    public void setBufferSize(int size) {
        throw nope();
    }

    @Override
    public int getBufferSize() {
        throw nope();
    }

    @Override
    public void flushBuffer() {
        throw nope();
    }

    @Override
    public void resetBuffer() {
        throw nope();
    }

    @Override
    public boolean isCommitted() {
        throw nope();
    }

    @Override
    public void reset() {
        throw nope();
    }

    @Override
    public void setLocale(Locale loc) {
        throw nope();
    }

    @Override
    public Locale getLocale() {
        throw nope();
    }

    private static UnsupportedOperationException nope() {
        return new UnsupportedOperationException("not needed by Bodoni tests");
    }
}
