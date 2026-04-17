package fr.vidocq.vidocq.ext.servlet.chappe.http;

import jakarta.servlet.http.Cookie;

import java.util.ArrayList;
import java.util.List;

/**
 * Encodage/décodage des cookies HTTP (RFC 6265).
 * <p>
 * Décodage : parse un header {@code Cookie} entrant en liste de {@link Cookie}.
 * Encodage : sérialise un {@link Cookie} en ligne {@code Set-Cookie} complète
 * avec ses attributs (Path, Domain, Max-Age, Secure, HttpOnly, SameSite).
 * </p>
 */
public final class CookieCodec {

    private CookieCodec() {}

    /** Parse le header {@code Cookie} : {@code name1=v1; name2=v2}. */
    public static List<Cookie> parseCookieHeader(String value) {
        List<Cookie> out = new ArrayList<>();
        if (value == null || value.isEmpty()) return out;
        for (String pair : value.split(";")) {
            String trimmed = pair.trim();
            int eq = trimmed.indexOf('=');
            if (eq < 0) continue;
            String name = trimmed.substring(0, eq).trim();
            String val = trimmed.substring(eq + 1).trim();
            if (name.isEmpty()) continue;
            // Retire les guillemets autour de la valeur (cookie-value quoted-string).
            if (val.length() >= 2 && val.startsWith("\"") && val.endsWith("\"")) {
                val = val.substring(1, val.length() - 1);
            }
            out.add(new Cookie(name, val));
        }
        return out;
    }

    /** Sérialise un {@link Cookie} complet en ligne {@code Set-Cookie}. */
    public static String serializeSetCookie(Cookie c) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.getName()).append('=').append(c.getValue() == null ? "" : c.getValue());
        if (c.getPath() != null) sb.append("; Path=").append(c.getPath());
        if (c.getDomain() != null) sb.append("; Domain=").append(c.getDomain());
        if (c.getMaxAge() >= 0) sb.append("; Max-Age=").append(c.getMaxAge());
        if (c.getSecure()) sb.append("; Secure");
        if (c.isHttpOnly()) sb.append("; HttpOnly");
        String sameSite = c.getAttribute("SameSite");
        if (sameSite != null) sb.append("; SameSite=").append(sameSite);
        return sb.toString();
    }
}
