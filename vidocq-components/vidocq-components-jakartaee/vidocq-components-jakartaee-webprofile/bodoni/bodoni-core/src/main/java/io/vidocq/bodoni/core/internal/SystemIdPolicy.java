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

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
import javax.xml.transform.sax.SAXSource;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.PageContext;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.ext.EntityResolver2;

/**
 * The "same origin" resolution of every external reference an XML document or an XSLT
 * stylesheet makes: external entities and the external DTD subset ({@link EntityResolver2}),
 * {@code xsl:import}, {@code xsl:include} and {@code document()} ({@link URIResolver})
 * (Tags 3.0 section 11.1.7, PLAN.md open question 6).
 *
 * <p>The origin is fixed when the policy is built, from the system id the page gave; the
 * base a parser or an XSLT processor passes only serves to resolve a reference, never to
 * widen the origin (an {@code xml:base} in an untrusted document changes that base):
 * <ul>
 *   <li>no system id: every reference is refused. The parser is given the private base
 *       {@code bodoni-none:/doc/} ({@link #NONE_BASE}) so that it never expands a reference
 *       against the JVM working directory, and a refusal names the reference as the document
 *       wrote it, even when it arrives expanded against that base (the two-argument
 *       {@code resolveEntity} an {@code XMLFilterImpl} receives);</li>
 *   <li>an opaque system id ({@code jar:}, ...): every reference is refused;</li>
 *   <li>an absolute system id: a target must have the same scheme, host and port (scheme and
 *       host compared case-insensitively, default port normalised); it is opened with
 *       {@link java.net.URL#openConnection()}, connect and read timeouts set, HTTP redirects
 *       off, any non-2xx HTTP status a failure;</li>
 *   <li>a relative system id: it is given to the parser as
 *       {@code bodoni-webapp:<context-relative path>} (a leading {@code /} is
 *       context-relative, anything else relative to the directory of the current page), and a
 *       target must stay a {@code bodoni-webapp:} URI whose decoded path has no {@code .} or
 *       {@code ..} segment, even once decoded again; it is read with {@code ServletContext.getResourceAsStream}. A target
 *       inside {@code /WEB-INF/} or {@code /META-INF/} is refused unless the system id is inside
 *       that same directory ({@link #protectedDirectory}): a stylesheet kept under
 *       {@code /WEB-INF/} imports its siblings, a public document cannot read them.</li>
 * </ul>
 *
 * <p>A refused or unreadable reference is recorded as a {@link JspException} naming the
 * reference and the page's system id ({@link #recordedFailure()}, the first one wins) and
 * thrown to the JDK as a {@link SAXException} or a {@link TransformerException} whose cause
 * it is: the JDK may wrap or rewrite the message, the recorded failure keeps it intact. The
 * policy never answers {@code null}, which would let the JDK fetch on its own or silently
 * drop an {@code xsl:import} (probe: {@code JdkXmlFactsTest}).
 *
 * <p>A policy belongs to one action invocation on one thread; it is not thread-safe.
 */
public final class SystemIdPolicy implements EntityResolver2, URIResolver {

    /** The private scheme that keeps the expansion of a relative system id inside the web application. */
    public static final String WEBAPP_SCHEME = "bodoni-webapp";

    /** The private scheme of the base given to the parser when the page gave no system id. */
    public static final String NONE_SCHEME = "bodoni-none";

    /**
     * The base given to the parser when the page gave no system id: without one, the JDK
     * would expand a relative reference against the JVM working directory.
     */
    public static final String NONE_BASE = NONE_SCHEME + ":/doc/";

    /** Message of a refused reference: action, reference, system id, reason. */
    static final String REFUSED = "%s: external reference '%s' refused, system id '%s': %s";

    /** Message of a reference that cannot be read: action, reference, system id, cause message. */
    static final String UNREADABLE = "%s: external reference '%s' cannot be read, system id '%s': %s";

    /** Refusal reason: the page gave no system id. */
    static final String NO_SYSTEM_ID = "no system id";

    /** Refusal reason: the page gave an opaque system id. */
    static final String NOT_HIERARCHICAL = "the system id is not hierarchical";

    /** Refusal reason: an absolute system id and a target of another scheme, host or port. */
    static final String NOT_SAME_ORIGIN = "not the same origin";

    /** Refusal reason: a relative system id and a target outside the web application. */
    static final String OUTSIDE_WEB_APPLICATION = "outside the web application";

    /**
     * Refusal reason: a relative system id and a target inside {@code /WEB-INF/} or
     * {@code /META-INF/}, the system id not inside that same directory (maintainer decision
     * 2026-10-23).
     */
    static final String INSIDE_PROTECTED_DIRECTORY = "inside /WEB-INF/ or /META-INF/ while the system id is not";

    /** Refusal reason: the reference, or the base it is resolved against, is not a URI. */
    static final String NOT_A_URI = "not a valid URI";

    /** Read failure: no web-application resource at the target path. */
    static final String NOT_FOUND = "not found";

    private static final String WEB_INF = "WEB-INF";

    private static final String META_INF = "META-INF";

    /** How many times a decoded path is percent-decoded again, at most ({@link #decodedAgain}). */
    private static final int MAX_DECODING_ROUNDS = 4;

    /** The separators of path segments: a backslash counts as one, as it may on the container's file system. */
    private static final Pattern SEPARATORS = Pattern.compile("[/\\\\]");

    /** The system id shown in messages when the page gave none. */
    private static final String SHOWN_NONE = "(none)";

    /**
     * A URI of a private scheme inside a JDK message. A {@code :} ends it, as in
     * {@code bodoni-none:/doc/: line 1}: the paths these schemes carry have none in practice.
     */
    private static final Pattern PRIVATE_URI =
            Pattern.compile("\\b(?:" + WEBAPP_SCHEME + '|' + NONE_SCHEME + "):[^\\s'\"<>;,:]*");

    private enum Mode { NONE, OPAQUE, ABSOLUTE, WEBAPP }

    private final String action;
    private final String systemId;
    private final URI origin;
    private final Mode mode;
    private final PageContext pageContext;
    private JspException failure;

    private SystemIdPolicy(String action, String systemId, URI origin, Mode mode, PageContext pageContext) {
        this.action = action;
        this.systemId = systemId;
        this.origin = origin;
        this.mode = mode;
        this.pageContext = pageContext;
    }

    /**
     * The policy of one action.
     *
     * @param action      the action name, for messages (e.g. {@code x:parse})
     * @param systemId    the system id the page gave, {@code null} or empty for none
     * @param pageContext the page, for the current page path and the web-application resources
     * @return the policy of {@code systemId}
     * @throws IllegalArgumentException if {@code systemId} is not a URI, or is relative and its
     *                                  normalised path leaves the context root
     */
    public static SystemIdPolicy of(String action, String systemId, PageContext pageContext) {
        if (systemId == null || systemId.isEmpty()) {
            return new SystemIdPolicy(action, null, null, Mode.NONE, pageContext);
        }
        if (Urls.isAbsolute(systemId)) {
            URI uri;
            try {
                uri = new URI(systemId);
            } catch (URISyntaxException e) {
                throw invalid(action, systemId, e);
            }
            return new SystemIdPolicy(action, systemId, uri, uri.isOpaque() ? Mode.OPAQUE : Mode.ABSOLUTE, pageContext);
        }
        return new SystemIdPolicy(action, systemId, webAppUri(action, systemId, pageContext), Mode.WEBAPP, pageContext);
    }

    /** The system id as the page gave it, {@code null} for none. */
    public String systemId() {
        return systemId;
    }

    /**
     * The system id to give the parser or the XSLT processor: an absolute system id as given,
     * a relative one as its {@code bodoni-webapp:} URI, none as {@link #NONE_BASE}.
     */
    public String parserSystemId() {
        return switch (mode) {
            case NONE -> NONE_BASE;
            case OPAQUE, ABSOLUTE -> systemId;
            case WEBAPP -> origin.toString();
        };
    }

    /** The first refused or unreadable reference, {@code null} while there is none. */
    public JspException recordedFailure() {
        return failure;
    }

    /**
     * {@code text} as a page may see it: the private bases given to the JDK
     * ({@link #parserSystemId()}) and the references it expanded under them are mapped back to
     * what the page wrote. The {@code parserSystemId()} of one of {@code policies} becomes that
     * policy's system id, {@code (none)} without one; any other {@code bodoni-webapp:} URI becomes
     * its context-relative path, any other {@code bodoni-none:} URI the reference relative to
     * {@link #NONE_BASE}.
     *
     * @param text     a message, {@code null} for none
     * @param policies the policies of the documents involved, the first match wins
     * @return the mapped message, {@code null} for {@code null}
     */
    public static String pageView(String text, SystemIdPolicy... policies) {
        if (text == null) {
            return null;
        }
        return PRIVATE_URI.matcher(text).replaceAll(m -> Matcher.quoteReplacement(shown(m.group(), policies)));
    }

    private static String shown(String uri, SystemIdPolicy[] policies) {
        for (SystemIdPolicy policy : policies) {
            if (uri.equals(policy.parserSystemId())) {
                return policy.shownSystemId();
            }
        }
        String rest = uri.startsWith(NONE_BASE) ? uri.substring(NONE_BASE.length()) : uri.substring(uri.indexOf(':') + 1);
        return rest.isEmpty() ? SHOWN_NONE : rest;
    }

    /** No external subset is supplied for a document without one. */
    @Override
    public InputSource getExternalSubset(String name, String baseURI) {
        return null;
    }

    /** Resolves an expanded entity system id, against {@link #parserSystemId()} when relative. */
    @Override
    public InputSource resolveEntity(String publicId, String systemId) throws SAXException {
        return resolveEntity(null, publicId, null, systemId);
    }

    /**
     * Resolves an external entity or the external DTD subset; the entity name is not used
     * (the JDK passes {@code null} for every entity).
     *
     * @throws SAXException if the reference is refused or cannot be read, its cause the
     *                      recorded {@link JspException}
     */
    @Override
    public InputSource resolveEntity(String name, String publicId, String baseURI, String systemId) throws SAXException {
        try {
            InputSource source = open(systemId, baseURI);
            source.setPublicId(publicId);
            return source;
        } catch (JspException e) {
            throw new SAXException(e.getMessage(), e);
        }
    }

    /**
     * Resolves {@code xsl:import}, {@code xsl:include} and {@code document()}: a
     * {@link SAXSource} whose reader resolves its own entities through this policy.
     *
     * @throws TransformerException if the reference is refused or cannot be read, its cause
     *                              the recorded {@link JspException}
     */
    @Override
    public Source resolve(String href, String base) throws TransformerException {
        try {
            return new SAXSource(XmlFactories.xmlReader(this), open(href, base));
        } catch (JspException e) {
            throw new TransformerException(e.getMessage(), e);
        }
    }

    /**
     * Tells whether two URIs have the same origin: scheme and host equal ignoring case (a
     * registry-based authority compared as a whole), ports equal once {@code -1} is replaced
     * by the scheme's default port.
     */
    static boolean sameOrigin(URI a, URI b) {
        return equalsIgnoreCase(a.getScheme(), b.getScheme())
                && equalsIgnoreCase(host(a), host(b))
                && port(a) == port(b);
    }

    /** Applies the connect and read timeouts and, on HTTP, turns redirects off; returns the connection. */
    static URLConnection prepare(URLConnection connection) {
        JdkExternalResources.withTimeouts(connection);
        if (connection instanceof HttpURLConnection http) {
            http.setInstanceFollowRedirects(false);
        }
        return connection;
    }

    // ---- resolution ---------------------------------------------------------

    private InputSource open(String reference, String base) throws JspException {
        if (mode == Mode.NONE) {
            throw refused(reference, NO_SYSTEM_ID);
        }
        if (mode == Mode.OPAQUE) {
            throw refused(reference, NOT_HIERARCHICAL);
        }
        URI target = target(reference, base);
        InputSource source = new InputSource(mode == Mode.ABSOLUTE ? openAbsolute(reference, target) : openWebApp(reference, target));
        source.setSystemId(target.toString());
        return source;
    }

    private URI target(String reference, String base) throws JspException {
        if (reference == null) {
            throw refused(null, NOT_A_URI);
        }
        try {
            return URI.create(base == null ? parserSystemId() : base).resolve(reference);
        } catch (IllegalArgumentException _) {
            throw refused(reference, NOT_A_URI);
        }
    }

    private InputStream openAbsolute(String reference, URI target) throws JspException {
        if (!target.isAbsolute() || target.isOpaque() || hostInPath(target) || !sameOrigin(target, origin)) {
            throw refused(reference, NOT_SAME_ORIGIN);
        }
        try {
            return read(target.toURL().openConnection());
        } catch (IOException | IllegalArgumentException e) {
            throw unreadable(reference, e);
        }
    }

    /**
     * Prepares {@code connection} ({@link #prepare}), requires a 2xx status on HTTP and opens
     * its stream. On any failure an HTTP connection is disconnected, so its socket does not
     * wait for a timeout or the garbage collector.
     *
     * @throws IOException if the connection fails, answers another status or cannot be read
     */
    static InputStream read(URLConnection connection) throws IOException {
        try {
            prepare(connection);
            if (connection instanceof HttpURLConnection http) {
                requireSuccess(http);
            }
            return connection.getInputStream();
        } catch (IOException | RuntimeException e) {
            if (connection instanceof HttpURLConnection http) {
                http.disconnect();
            }
            throw e;
        }
    }

    /**
     * Whether a target without authority has a decoded path starting with two separators
     * ({@code /} or {@code \}), as in {@code file:////host/share/x}: its host compares as
     * empty, yet the opened path names another host (a UNC path on Windows).
     */
    private static boolean hostInPath(URI target) {
        String path = target.getPath();
        return target.getRawAuthority() == null
                && path != null
                && path.length() >= 2
                && isSeparator(path.charAt(0))
                && isSeparator(path.charAt(1));
    }

    private static boolean isSeparator(char c) {
        return c == '/' || c == '\\';
    }

    private static void requireSuccess(HttpURLConnection http) throws IOException {
        int status = http.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP status " + status);
        }
    }

    private InputStream openWebApp(String reference, URI target) throws JspException {
        if (!insideWebApp(target)) {
            throw refused(reference, OUTSIDE_WEB_APPLICATION);
        }
        String directory = protectedDirectory(target.getPath());
        if (directory != null && !directory.equals(protectedDirectory(origin.getPath()))) {
            throw refused(reference, INSIDE_PROTECTED_DIRECTORY);
        }
        InputStream in;
        try {
            in = pageContext.getServletContext().getResourceAsStream(target.getPath());
        } catch (RuntimeException e) {
            // A container may reject a path it cannot map (an IllegalArgumentException for a NUL).
            throw unreadable(reference, e);
        }
        if (in == null) {
            throw unreadable(reference, new FileNotFoundException(NOT_FOUND));
        }
        return in;
    }

    /**
     * A {@code bodoni-webapp:} URI without authority whose decoded path is absolute and has no
     * dot segment, neither as decoded nor once decoded again ({@link #decodedAgain}): a container
     * that decodes the path a second time must not see a {@code ..} either. A path that still
     * has an escape after the last decoding round is refused too.
     */
    private static boolean insideWebApp(URI target) {
        String path = target.getPath();
        if (!WEBAPP_SCHEME.equalsIgnoreCase(target.getScheme()) || target.getRawAuthority() != null
                || path == null || !path.startsWith("/")) {
            return false;
        }
        String decoded = decodedAgain(path);
        return decoded != null && !hasDotSegment(path) && !hasDotSegment(decoded);
    }

    /**
     * Whether a decoded path has a dot segment, a backslash also counting as a separator: a
     * {@code .} or {@code ..}, or any non-empty segment made only of dots and blanks
     * ({@code ...}, {@code ..} and a space), which a Windows file layer, stripping trailing dots and
     * spaces, turns into {@code ..}. A segment is only stripped here, not cut at {@code ;}, so
     * {@code /;jsessionid=1/x} stays a plain path.
     */
    private static boolean hasDotSegment(String path) {
        for (String segment : SEPARATORS.split(path)) {
            if (!segment.isEmpty() && withoutTrailingDotsAndBlanks(segment, segment.length()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The protected directory ({@code WEB-INF} or {@code META-INF}) the first segment of a
     * decoded path names, {@code null} for none. The match is conservative, so that no spelling
     * a container might map to the same directory gets through:
     * <ul>
     *   <li>the path is percent-decoded again, in case a container decodes it a second time;</li>
     *   <li>{@code /} and {@code \} both separate segments, and doubled separators count as one;</li>
     *   <li>a segment is cut at its first {@code ;} (a path parameter) or {@code :} (an NTFS
     *       stream name), then loses its trailing dots, spaces and control characters (Windows
     *       drops trailing dots and spaces);</li>
     *   <li>a segment left empty ({@code .}, {@code ..}, blanks) is skipped, the next one is first;</li>
     *   <li>the name is compared ignoring case (case-insensitive file systems).</li>
     * </ul>
     * A path that still has an escape after the last decoding round names none: an origin then
     * counts as public (least privilege), and {@link #insideWebApp} has already refused such a
     * target.
     */
    static String protectedDirectory(String path) {
        String decoded = decodedAgain(path);
        if (decoded == null) {
            return null;
        }
        for (String segment : SEPARATORS.split(decoded)) {
            String name = segmentName(segment);
            if (!name.isEmpty()) {
                return protectedName(name);
            }
        }
        return null;
    }

    private static String protectedName(String name) {
        if (WEB_INF.equalsIgnoreCase(name)) {
            return WEB_INF;
        }
        return META_INF.equalsIgnoreCase(name) ? META_INF : null;
    }

    /** A segment cut at its first {@code ;} or {@code :}, without trailing dots, spaces or control characters. */
    private static String segmentName(String segment) {
        int end = segment.length();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == ';' || c == ':') {
                end = i;
                break;
            }
        }
        return withoutTrailingDotsAndBlanks(segment, end);
    }

    /** The first {@code end} characters of {@code segment} without their trailing dots, spaces and control characters. */
    private static String withoutTrailingDotsAndBlanks(String segment, int end) {
        int length = end;
        while (length > 0 && (segment.charAt(length - 1) == '.' || segment.charAt(length - 1) <= ' ')) {
            length--;
        }
        return segment.substring(0, length);
    }

    /**
     * {@code path} percent-decoded again until no well-formed escape is left, {@code null} when
     * one is still left after {@link #MAX_DECODING_ROUNDS} rounds.
     */
    private static String decodedAgain(String path) {
        String decoded = path;
        for (int round = 0; round < MAX_DECODING_ROUNDS && hasEscape(decoded); round++) {
            decoded = percentDecoded(decoded);
        }
        return hasEscape(decoded) ? null : decoded;
    }

    private static boolean hasEscape(String text) {
        for (int i = text.indexOf('%'); i >= 0; i = text.indexOf('%', i + 1)) {
            if (escapedByte(text, i) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * One round of lenient percent decoding (UTF-8): every well-formed {@code %XX} escape is
     * decoded, a malformed one is kept as written, so it cannot hide the escapes after it.
     */
    private static String percentDecoded(String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(text.length());
        int i = 0;
        while (i < text.length()) {
            int escaped = escapedByte(text, i);
            if (escaped >= 0) {
                bytes.write(escaped);
                i += 3;
            } else {
                int end = i + Character.charCount(text.codePointAt(i));
                bytes.writeBytes(text.substring(i, end).getBytes(StandardCharsets.UTF_8));
                i = end;
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /** The byte a well-formed {@code %XX} escape at {@code i} stands for, {@code -1} if there is none. */
    private static int escapedByte(String text, int i) {
        if (text.charAt(i) != '%' || i + 2 >= text.length()) {
            return -1;
        }
        int high = Character.digit(text.charAt(i + 1), 16);
        int low = Character.digit(text.charAt(i + 2), 16);
        return high < 0 || low < 0 ? -1 : (high << 4) | low;
    }

    // ---- relative system ids ------------------------------------------------

    private static URI webAppUri(String action, String systemId, PageContext pageContext) {
        String path = systemId.startsWith("/") ? systemId : pageDirectory(pageContext.getRequest()) + systemId;
        URI uri;
        try {
            uri = new URI(WEBAPP_SCHEME, null, path, null).normalize();
        } catch (URISyntaxException e) {
            throw invalid(action, systemId, e);
        }
        String normalised = uri.getPath();
        if (normalised.equals("/..") || normalised.startsWith("/../")) {
            throw new IllegalArgumentException(action + ": the system id '" + systemId + "' leaves the context root");
        }
        return uri;
    }

    /** The directory of the current page, the included one when inside an include, ending with {@code /}. */
    private static String pageDirectory(ServletRequest request) {
        String page;
        if (request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH) instanceof String included) {
            page = included;
        } else if (request instanceof HttpServletRequest http) {
            page = http.getServletPath();
        } else {
            page = "";
        }
        int slash = page.lastIndexOf('/');
        return slash < 0 ? "/" : page.substring(0, slash + 1);
    }

    // ---- failures -----------------------------------------------------------

    private JspException refused(String reference, String reason) {
        String shown = mode == Mode.NONE ? asWritten(reference) : reference;
        return keepFirst(new JspException(REFUSED.formatted(action, shown, shownSystemId(), reason)));
    }

    /**
     * A reference as the document wrote it, before the parser expanded it against
     * {@link #NONE_BASE} (the two-argument {@code resolveEntity}, as an {@code XMLFilterImpl}
     * receives it).
     */
    private static String asWritten(String reference) {
        if (reference == null) {
            return null;
        }
        if (reference.startsWith(NONE_BASE)) {
            return reference.substring(NONE_BASE.length());
        }
        if (reference.startsWith(NONE_SCHEME + ":")) {
            return reference.substring(NONE_SCHEME.length() + 1);
        }
        return reference;
    }

    private JspException unreadable(String reference, Exception cause) {
        String message = Objects.toString(cause.getMessage(), cause.getClass().getName());
        return keepFirst(new JspException(UNREADABLE.formatted(action, reference, shownSystemId(), message), cause));
    }

    private JspException keepFirst(JspException exception) {
        if (failure == null) {
            failure = exception;
        }
        return exception;
    }

    private String shownSystemId() {
        return systemId == null ? SHOWN_NONE : systemId;
    }

    private static IllegalArgumentException invalid(String action, String systemId, URISyntaxException cause) {
        return new IllegalArgumentException(action + ": invalid system id '" + systemId + "': " + cause.getMessage(), cause);
    }

    // ---- origin ---------------------------------------------------------------

    private static boolean equalsIgnoreCase(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    /** The host, else the raw authority of a registry-based one, else {@code ""}. */
    private static String host(URI uri) {
        if (uri.getHost() != null) {
            return uri.getHost();
        }
        return Objects.toString(uri.getRawAuthority(), "");
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        try {
            return uri.toURL().getDefaultPort();
        } catch (MalformedURLException | IllegalArgumentException _) {
            return -1;
        }
    }
}
