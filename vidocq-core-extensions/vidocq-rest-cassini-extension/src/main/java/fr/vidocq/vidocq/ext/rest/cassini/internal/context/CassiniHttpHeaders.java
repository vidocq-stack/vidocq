package fr.vidocq.vidocq.ext.rest.cassini.internal.context;

import fr.vidocq.chappe.api.Request;
import fr.vidocq.vidocq.ext.rest.cassini.internal.MediaTypes;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Implémentation {@link HttpHeaders} adossée à {@link Request} Chappe.
 */
public final class CassiniHttpHeaders implements HttpHeaders {

    private final Request request;

    public CassiniHttpHeaders(Request request) {
        this.request = request;
    }

    @Override public List<String> getRequestHeader(String name) { return request.headers().all(name); }

    @Override public String getHeaderString(String name) {
        List<String> all = request.headers().all(name);
        return all.isEmpty() ? null : String.join(",", all);
    }

    @Override public boolean containsHeaderString(String n, String valueSep, java.util.function.Predicate<String> match) {
        for (String v : request.headers().all(n)) {
            for (String tok : v.split(valueSep)) if (match.test(tok.trim())) return true;
        }
        return false;
    }

    @Override public boolean containsHeaderString(String n, java.util.function.Predicate<String> match) {
        return containsHeaderString(n, ",", match);
    }

    @Override public MultivaluedMap<String, String> getRequestHeaders() {
        MultivaluedMap<String, String> m = new MultivaluedHashMap<>();
        for (var e : request.headers()) m.add(e.name(), e.value());
        return m;
    }

    @Override public List<MediaType> getAcceptableMediaTypes() {
        return MediaTypes.parseList(request.headers().firstOrNull("Accept"));
    }

    @Override public List<Locale> getAcceptableLanguages() {
        String raw = request.headers().firstOrNull("Accept-Language");
        if (raw == null || raw.isBlank()) return List.of();
        java.util.List<Locale> out = new java.util.ArrayList<>();
        for (String tok : raw.split(",")) {
            int semi = tok.indexOf(';');
            String tag = (semi < 0 ? tok : tok.substring(0, semi)).trim();
            if (!tag.isEmpty()) out.add(Locale.forLanguageTag(tag));
        }
        return out;
    }

    @Override public MediaType getMediaType() {
        return MediaTypes.parse(request.headers().firstOrNull("Content-Type"));
    }

    @Override public Locale getLanguage() {
        String raw = request.headers().firstOrNull("Content-Language");
        return raw == null ? null : Locale.forLanguageTag(raw);
    }

    @Override public Map<String, Cookie> getCookies() {
        Map<String, Cookie> out = new HashMap<>();
        for (String header : request.headers().all("Cookie")) {
            for (String pair : header.split(";")) {
                int eq = pair.indexOf('=');
                if (eq < 0) continue;
                String n = pair.substring(0, eq).trim();
                String v = pair.substring(eq + 1).trim();
                if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                    v = v.substring(1, v.length() - 1);
                }
                out.put(n, new Cookie.Builder(n).value(v).build());
            }
        }
        return out;
    }

    @Override public Date getDate() {
        String raw = request.headers().firstOrNull("Date");
        if (raw == null) return null;
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            return fmt.parse(raw);
        } catch (Exception e) { return null; }
    }

    @Override public int getLength() {
        String raw = request.headers().firstOrNull("Content-Length");
        if (raw == null) return -1;
        try { return Integer.parseInt(raw); } catch (NumberFormatException e) { return -1; }
    }
}
