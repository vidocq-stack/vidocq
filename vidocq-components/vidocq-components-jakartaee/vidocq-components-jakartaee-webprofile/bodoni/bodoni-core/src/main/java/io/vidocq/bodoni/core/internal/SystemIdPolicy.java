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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.util.Objects;

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
 *   <li>no system id: every reference is refused;</li>
 *   <li>an opaque system id ({@code jar:}, ...): every reference is refused;</li>
 *   <li>an absolute system id: a target must have the same scheme, host and port (scheme and
 *       host compared case-insensitively, default port normalised); it is opened with
 *       {@link java.net.URL#openConnection()}, connect and read timeouts set, HTTP redirects
 *       off, any non-2xx HTTP status a failure;</li>
 *   <li>a relative system id: it is given to the parser as
 *       {@code bodoni-webapp:<context-relative path>} (a leading {@code /} is
 *       context-relative, anything else relative to the directory of the current page), and a
 *       target must stay a {@code bodoni-webapp:} URI whose decoded path has no {@code .} or
 *       {@code ..} segment; it is read with {@code ServletContext.getResourceAsStream}.</li>
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

    /** Refusal reason: the reference, or the base it is resolved against, is not a URI. */
    static final String NOT_A_URI = "not a valid URI";

    /** Read failure: no web-application resource at the target path. */
    static final String NOT_FOUND = "not found";

    /** The system id shown in messages when the page gave none. */
    private static final String NONE = "(none)";

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
     * a relative one as its {@code bodoni-webapp:} URI, {@code null} for none.
     */
    public String parserSystemId() {
        return switch (mode) {
            case NONE -> null;
            case OPAQUE, ABSOLUTE -> systemId;
            case WEBAPP -> origin.toString();
        };
    }

    /** The first refused or unreadable reference, {@code null} while there is none. */
    public JspException recordedFailure() {
        return failure;
    }

    /** No external subset is supplied for a document without one. */
    @Override
    public InputSource getExternalSubset(String name, String baseURI) {
        return null;
    }

    /** Resolves an expanded entity system id, against {@link #parserSystemId()} when relative. */
    @Override
    public InputSource resolveEntity(String publicId, String reference) throws SAXException {
        return resolveEntity(null, publicId, null, reference);
    }

    /**
     * Resolves an external entity or the external DTD subset; the entity name is not used
     * (the JDK passes {@code null} for every entity).
     *
     * @throws SAXException if the reference is refused or cannot be read, its cause the
     *                      recorded {@link JspException}
     */
    @Override
    public InputSource resolveEntity(String name, String publicId, String baseURI, String reference) throws SAXException {
        try {
            InputSource source = open(reference, baseURI);
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
        if (!target.isAbsolute() || target.isOpaque() || !sameOrigin(target, origin)) {
            throw refused(reference, NOT_SAME_ORIGIN);
        }
        try {
            URLConnection connection = prepare(target.toURL().openConnection());
            if (connection instanceof HttpURLConnection http) {
                requireSuccess(http);
            }
            return connection.getInputStream();
        } catch (IOException | IllegalArgumentException e) {
            throw unreadable(reference, e);
        }
    }

    private static void requireSuccess(HttpURLConnection http) throws IOException {
        int status = http.getResponseCode();
        if (status < 200 || status >= 300) {
            http.disconnect();
            throw new IOException("HTTP status " + status);
        }
    }

    private InputStream openWebApp(String reference, URI target) throws JspException {
        if (!insideWebApp(target)) {
            throw refused(reference, OUTSIDE_WEB_APPLICATION);
        }
        InputStream in = pageContext.getServletContext().getResourceAsStream(target.getPath());
        if (in == null) {
            throw unreadable(reference, new FileNotFoundException(NOT_FOUND));
        }
        return in;
    }

    /** A {@code bodoni-webapp:} URI without authority whose decoded path is absolute and has no dot segment. */
    private static boolean insideWebApp(URI target) {
        String path = target.getPath();
        return WEBAPP_SCHEME.equalsIgnoreCase(target.getScheme())
                && target.getRawAuthority() == null
                && path != null
                && path.startsWith("/")
                && !hasDotSegment(path);
    }

    /** Whether a decoded path has a {@code .} or {@code ..} segment, a backslash also counting as a separator. */
    private static boolean hasDotSegment(String path) {
        for (String segment : path.split("[/\\\\]")) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
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
        return record(new JspException(REFUSED.formatted(action, reference, shownSystemId(), reason)));
    }

    private JspException unreadable(String reference, Exception cause) {
        String message = Objects.toString(cause.getMessage(), cause.getClass().getName());
        return record(new JspException(UNREADABLE.formatted(action, reference, shownSystemId(), message), cause));
    }

    private JspException record(JspException exception) {
        if (failure == null) {
            failure = exception;
        }
        return exception;
    }

    private String shownSystemId() {
        return systemId == null ? NONE : systemId;
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
