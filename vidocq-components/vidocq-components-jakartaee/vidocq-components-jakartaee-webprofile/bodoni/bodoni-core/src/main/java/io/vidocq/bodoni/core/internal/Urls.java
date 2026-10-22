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

package io.vidocq.bodoni.core.internal;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.jsp.JspTagException;

/** URL helpers shared by {@code c:url}, {@code c:redirect} and {@code c:import} (Tags 3.0 §7). */
public final class Urls {

    private Urls() {
    }

    /**
     * Tells whether a URL is absolute, that is whether it starts with a scheme (RFC 3986:
     * a letter followed by letters, digits, {@code +}, {@code -} or {@code .}, then
     * {@code :}) found before the first {@code /}, {@code ?} or {@code #}.
     */
    public static boolean isAbsolute(String url) {
        if (url == null || url.isEmpty() || !isAlpha(url.charAt(0))) {
            return false;
        }
        for (int i = 1; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == ':') {
                return true;
            }
            if (!(isAlpha(c) || (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.')) {
                return false;
            }
        }
        return false;
    }

    private static boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * Checks the foreign-context rule of §7.4/§7.5: both the context name and the URL must
     * start with a slash.
     *
     * @throws JspTagException if either does not
     */
    public static void requireContextRelative(String url, String context) throws JspTagException {
        if (!context.startsWith("/")) {
            throw new JspTagException("The context attribute must start with a '/': " + context);
        }
        if (!url.startsWith("/")) {
            throw new JspTagException("With a context, the URL must start with a '/': " + url);
        }
    }

    /**
     * Resolves a URL as {@code c:url} and {@code c:redirect} do: an absolute URL is returned
     * as is; with a {@code context} the result is {@code context + url} (just {@code url}
     * for the context {@code /}); a URL starting with a slash gets the context path of the
     * request; any other URL is page-relative and returned as is.
     *
     * @throws JspTagException if {@code context} (or {@code url} when {@code context} is set)
     *                         does not start with a slash
     */
    public static String resolve(String url, String context, HttpServletRequest request) throws JspTagException {
        if (isAbsolute(url)) {
            return url;
        }
        if (context != null) {
            requireContextRelative(url, context);
            return "/".equals(context) ? url : context + url;
        }
        if (url.startsWith("/")) {
            return request.getContextPath() + url;
        }
        return url;
    }

    /**
     * Appends query parameters, URL-encoding names and values with {@code encoding}. They are
     * inserted before any {@code #fragment}.
     */
    public static String appendParameters(String url, List<Map.Entry<String, String>> params, String encoding) {
        if (params.isEmpty()) {
            return url;
        }
        Charset charset = Charset.forName(encoding == null ? "ISO-8859-1" : encoding);
        int hash = url.indexOf('#');
        String base = hash < 0 ? url : url.substring(0, hash);
        String fragment = hash < 0 ? "" : url.substring(hash);
        StringBuilder out = new StringBuilder(base);
        char separator = base.indexOf('?') < 0 ? '?' : '&';
        if (base.endsWith("?") || base.endsWith("&")) {
            separator = 0;
        }
        for (Map.Entry<String, String> param : params) {
            if (separator != 0) {
                out.append(separator);
            }
            out.append(URLEncoder.encode(param.getKey(), charset)).append('=')
                    .append(URLEncoder.encode(param.getValue(), charset));
            separator = '&';
        }
        return out.append(fragment).toString();
    }

    /**
     * Removes a {@code ;jsessionid=...} path parameter (name matched case-insensitively) from
     * the path of a relative URL, up to the next {@code ;}, {@code /}, {@code ?} or
     * {@code #}. The query string and the fragment are left untouched. Tags 3.0 §7.4:
     * {@code c:import} removes session id information from the URL it dispatches.
     */
    public static String stripSessionId(String url) {
        int pathEnd = url.length();
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '?' || c == '#') {
                pathEnd = i;
                break;
            }
        }
        String path = url.substring(0, pathEnd);
        int at = path.toLowerCase(java.util.Locale.ROOT).indexOf(";jsessionid=");
        if (at < 0) {
            return url;
        }
        int stop = at + 1;
        while (stop < path.length() && path.charAt(stop) != ';' && path.charAt(stop) != '/') {
            stop++;
        }
        return path.substring(0, at) + path.substring(stop) + url.substring(pathEnd);
    }
}
