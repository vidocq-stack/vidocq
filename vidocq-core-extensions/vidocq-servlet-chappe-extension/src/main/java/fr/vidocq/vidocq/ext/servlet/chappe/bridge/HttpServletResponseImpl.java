package fr.vidocq.vidocq.ext.servlet.chappe.bridge;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@link HttpServletResponse} qui accumule l'état (status, headers, body) et se matérialise
 * en {@link fr.vidocq.chappe.api.Response Response} Chappe immuable à la fin du dispatch.
 */
public final class HttpServletResponseImpl implements HttpServletResponse {

    private int status = 200;
    private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final List<Cookie> cookies = new ArrayList<>();
    private String contentType;
    private String characterEncoding;
    private Locale locale = Locale.getDefault();
    private final ServletOutputStreamImpl outputStream = new ServletOutputStreamImpl();
    private PrintWriter writer;
    private boolean streamAcquired;
    private boolean committed;

    // ---- Status ----

    @Override public int getStatus() { return status; }
    @Override public void setStatus(int sc) { this.status = sc; }
    @Override public void sendError(int sc, String msg) throws IOException {
        if (committed) throw new IllegalStateException("response already committed");
        setStatus(sc);
        setContentType("text/plain;charset=utf-8");
        if (msg != null) getOutputStream().write(msg.getBytes(charset()));
        committed = true;
    }
    @Override public void sendError(int sc) throws IOException { sendError(sc, null); }
    @Override public void sendRedirect(String location) throws IOException {
        if (committed) throw new IllegalStateException("response already committed");
        setStatus(SC_FOUND);
        setHeader("Location", location);
        committed = true;
    }
    @Override public void sendRedirect(String location, int sc, boolean clearBuffer) throws IOException {
        if (clearBuffer) resetBuffer();
        setStatus(sc);
        setHeader("Location", location);
        committed = true;
    }

    // ---- Headers ----

    @Override public void setHeader(String name, String value) {
        List<String> list = new ArrayList<>();
        list.add(value);
        headers.put(name, list);
        interceptSpecialHeader(name, value);
    }
    @Override public void addHeader(String name, String value) {
        headers.computeIfAbsent(name, _ -> new ArrayList<>()).add(value);
        interceptSpecialHeader(name, value);
    }
    @Override public void setIntHeader(String name, int value) { setHeader(name, Integer.toString(value)); }
    @Override public void addIntHeader(String name, int value) { addHeader(name, Integer.toString(value)); }
    @Override public void setDateHeader(String name, long date) {
        throw new UnsupportedOperationException("setDateHeader not implemented");
    }
    @Override public void addDateHeader(String name, long date) {
        throw new UnsupportedOperationException();
    }
    @Override public boolean containsHeader(String name) { return headers.containsKey(name); }
    @Override public String getHeader(String name) {
        List<String> list = headers.get(name);
        return list == null || list.isEmpty() ? null : list.get(0);
    }
    @Override public Collection<String> getHeaders(String name) {
        List<String> list = headers.get(name);
        return list == null ? List.of() : List.copyOf(list);
    }
    @Override public Collection<String> getHeaderNames() { return new HashSet<>(headers.keySet()); }

    private void interceptSpecialHeader(String name, String value) {
        if ("Content-Type".equalsIgnoreCase(name)) {
            this.contentType = value;
            int idx = value == null ? -1 : value.toLowerCase(Locale.ROOT).indexOf("charset=");
            if (idx >= 0) {
                this.characterEncoding = value.substring(idx + 8).trim();
            }
        }
    }

    // ---- Cookies ----

    @Override public void addCookie(Cookie cookie) { cookies.add(cookie); }
    public List<Cookie> cookies() { return cookies; }

    // ---- Content-Type / charset ----

    @Override public String getContentType() { return contentType; }
    @Override public void setContentType(String type) {
        this.contentType = type;
        if (type != null) {
            int idx = type.toLowerCase(Locale.ROOT).indexOf("charset=");
            if (idx >= 0) this.characterEncoding = type.substring(idx + 8).trim();
            setHeader("Content-Type", type);
        }
    }
    @Override public String getCharacterEncoding() {
        return characterEncoding == null ? "ISO-8859-1" : characterEncoding;
    }
    @Override public void setCharacterEncoding(String charset) { this.characterEncoding = charset; }
    @Override public void setCharacterEncoding(Charset encoding) {
        this.characterEncoding = encoding == null ? null : encoding.name();
    }
    @Override public void setContentLength(int len) { setIntHeader("Content-Length", len); }
    @Override public void setContentLengthLong(long len) { setHeader("Content-Length", Long.toString(len)); }

    // ---- Body ----

    @Override public ServletOutputStream getOutputStream() throws IOException {
        if (writer != null) throw new IllegalStateException("getWriter() already called");
        streamAcquired = true;
        return outputStream;
    }
    @Override public PrintWriter getWriter() throws IOException {
        if (streamAcquired) throw new IllegalStateException("getOutputStream() already called");
        if (writer == null) {
            writer = new PrintWriter(new java.io.OutputStreamWriter(outputStream, charset()), false);
        }
        return writer;
    }

    private Charset charset() {
        if (characterEncoding == null) return StandardCharsets.ISO_8859_1;
        return Charset.forName(characterEncoding);
    }

    // ---- Buffer / commit ----

    @Override public void setBufferSize(int size) { /* no-op — full buffering in memory */ }
    @Override public int getBufferSize() { return outputStream.size(); }
    @Override public void flushBuffer() {
        if (writer != null) writer.flush();
        committed = true;
    }
    @Override public void resetBuffer() {
        if (committed) throw new IllegalStateException("committed");
        // outputStream n'a pas de reset → recrée un simple impl
        throw new UnsupportedOperationException("resetBuffer not implemented");
    }
    @Override public boolean isCommitted() { return committed; }
    @Override public void reset() {
        if (committed) throw new IllegalStateException("committed");
        status = 200;
        headers.clear();
        cookies.clear();
        contentType = null;
        characterEncoding = null;
    }
    @Override public void setLocale(Locale loc) { this.locale = loc; }
    @Override public Locale getLocale() { return locale; }

    // ---- URL encoding ----

    @Override public String encodeURL(String url) { return url; }
    @Override public String encodeRedirectURL(String url) { return url; }

    // ---- Accès interne pour le bridge Chappe ----

    public byte[] bodyBytes() {
        if (writer != null) writer.flush();
        return outputStream.toByteArray();
    }

    public Map<String, List<String>> allHeaders() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (var e : headers.entrySet()) out.put(e.getKey(), List.copyOf(e.getValue()));
        for (Cookie c : cookies) {
            out.computeIfAbsent("Set-Cookie", _ -> new ArrayList<>())
                    .add(fr.vidocq.vidocq.ext.servlet.chappe.http.CookieCodec.serializeSetCookie(c));
        }
        return out;
    }

    public Set<String> headerNames() {
        return new HashSet<>(headers.keySet());
    }
}
