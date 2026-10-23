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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.sax.SAXSource;

import jakarta.servlet.jsp.JspException;

import io.vidocq.bodoni.core.testing.TestHttpServer;
import io.vidocq.bodoni.core.testing.TestHttpServletRequest;
import io.vidocq.bodoni.core.testing.TestHttpServletResponse;
import io.vidocq.bodoni.core.testing.TestPageContext;
import io.vidocq.bodoni.core.testing.TestServletContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/** {@link SystemIdPolicy}: the same-origin rule of PLAN.md open question 6 (Tags 3.0 section 11.1.7). */
class SystemIdPolicyTest {

    private static final String ACTION = "x:parse";
    private static final String DOC = "data/doc.xml";
    private static final String OUTSIDE = "outside the web application";
    private static final String NOT_SAME_ORIGIN = "not the same origin";
    private static final String BODY = "<e/>";

    private TestServletContext context;
    private TestHttpServletRequest request;
    private TestPageContext pc;
    private TestHttpServer server;

    @BeforeEach
    void page() {
        context = new TestServletContext();
        request = new TestHttpServletRequest(context).servletPath("/shop/list.jsp");
        pc = new TestPageContext(request, new TestHttpServletResponse(), context);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    // ---- no system id -------------------------------------------------------

    @Test
    void withoutASystemIdEveryReferenceIsRefused() {
        for (String none : new String[] {null, ""}) {
            SystemIdPolicy policy = SystemIdPolicy.of(ACTION, none, pc);

            assertThrows(SAXException.class, () -> policy.resolveEntity("e", null, null, "a.xml"));

            assertEquals("x:parse: external reference 'a.xml' refused, system id '(none)': no system id",
                    policy.recordedFailure().getMessage());
            assertEquals("bodoni-none:/doc/", policy.parserSystemId());
        }
        assertNull(SystemIdPolicy.of(ACTION, null, pc).systemId());
    }

    @Test
    void withoutASystemIdTheTwoArgumentFormNamesTheReferenceAsWritten() {
        SystemIdPolicy policy = SystemIdPolicy.of(ACTION, null, pc);
        SystemIdPolicy rooted = SystemIdPolicy.of(ACTION, null, pc);
        SystemIdPolicy absolute = SystemIdPolicy.of(ACTION, null, pc);

        assertThrows(SAXException.class, () -> policy.resolveEntity(null, "bodoni-none:/doc/sub/e.xml"));
        assertThrows(SAXException.class, () -> rooted.resolveEntity(null, "bodoni-none:/e.xml"));
        assertThrows(SAXException.class, () -> absolute.resolveEntity(null, "http://h.example/e.xml"));

        assertEquals("x:parse: external reference 'sub/e.xml' refused, system id '(none)': no system id",
                policy.recordedFailure().getMessage());
        assertEquals("x:parse: external reference '/e.xml' refused, system id '(none)': no system id",
                rooted.recordedFailure().getMessage());
        assertEquals("x:parse: external reference 'http://h.example/e.xml' refused, system id '(none)': no system id",
                absolute.recordedFailure().getMessage());
    }

    @Test
    void withoutASystemIdANullReferenceIsRefused() {
        SystemIdPolicy policy = SystemIdPolicy.of(ACTION, null, pc);

        assertThrows(SAXException.class, () -> policy.resolveEntity(null, null));

        assertEquals("x:parse: external reference 'null' refused, system id '(none)': no system id",
                policy.recordedFailure().getMessage());
    }

    // ---- relative system id -------------------------------------------------

    @Test
    void relativeSystemIdsBecomeWebAppUris() {
        assertEquals("bodoni-webapp:/shop/data/doc.xml", SystemIdPolicy.of(ACTION, DOC, pc).parserSystemId());
        assertEquals(DOC, SystemIdPolicy.of(ACTION, DOC, pc).systemId());
        assertEquals("bodoni-webapp:/docs/doc.xml", SystemIdPolicy.of(ACTION, "/docs/doc.xml", pc).parserSystemId());
    }

    @Test
    void anIncludedPageIsTheBaseOfARelativeSystemId() {
        request.setAttribute("jakarta.servlet.include.servlet_path", "/inc/part.jsp");

        assertEquals("bodoni-webapp:/inc/doc.xml", SystemIdPolicy.of(ACTION, "doc.xml", pc).parserSystemId());
    }

    @Test
    void relativeEntitiesAreReadFromTheWebApplication() throws Exception {
        context.resource("/shop/data/xfiles/e.xml", BODY).resource("/WEB-INF/e.xml", "<secret/>");
        SystemIdPolicy policy = SystemIdPolicy.of(ACTION, DOC, pc);

        InputSource entity = policy.resolveEntity(null, null, policy.parserSystemId(), "xfiles/e.xml");
        InputSource webInf = policy.resolveEntity(null, null, policy.parserSystemId(), "/WEB-INF/e.xml");

        assertEquals(BODY, read(entity));
        assertEquals("bodoni-webapp:/shop/data/xfiles/e.xml", entity.getSystemId());
        assertEquals("<secret/>", read(webInf));
        assertNull(policy.recordedFailure());
    }

    @Test
    void theTwoArgumentFormAcceptsAnExpandedWebAppId() throws Exception {
        context.resource("/shop/data/parts/p.xml", BODY);
        SystemIdPolicy policy = SystemIdPolicy.of(ACTION, DOC, pc);

        InputSource entity = policy.resolveEntity(null, "bodoni-webapp:/shop/data/parts/p.xml");

        assertEquals(BODY, read(entity));
        assertNull(policy.getExternalSubset("r", policy.parserSystemId()));
    }

    @Test
    void relativeTargetsOutsideTheWebApplicationAreRefused() {
        context.resource("/etc/passwd", "root").resource("/x", "x").resource("/shop/x", "x");
        for (String reference : new String[] {
                "../../../etc/passwd", "%2e%2e/%2e%2e/%2e%2e/x", "..%2fx", "http://127.0.0.1/x", "file:///etc/passwd"}) {
            SystemIdPolicy policy = SystemIdPolicy.of(ACTION, DOC, pc);
            String base = policy.parserSystemId();

            assertThrows(SAXException.class, () -> policy.resolveEntity(null, null, base, reference), reference);

            assertEquals("x:parse: external reference '" + reference + "' refused, system id 'data/doc.xml': " + OUTSIDE,
                    policy.recordedFailure().getMessage());
        }
    }

    @Test
    void aResourceLookupThatThrowsIsAReadFailure() {
        IllegalArgumentException lookup = new IllegalArgumentException("invalid path");
        context.failingResource("/shop/data/bad.xml", lookup);
        SystemIdPolicy policy = SystemIdPolicy.of(ACTION, DOC, pc);

        SAXException thrown = assertThrows(SAXException.class, () -> policy.resolveEntity(null, null, null, "bad.xml"));

        JspException recorded = policy.recordedFailure();
        assertEquals("x:parse: external reference 'bad.xml' cannot be read, system id 'data/doc.xml': invalid path",
                recorded.getMessage());
        assertSame(lookup, recorded.getCause());
        assertSame(recorded, thrown.getCause());
    }

    @Test
    void aMissingResourceIsAReadFailure() {
        SystemIdPolicy policy = SystemIdPolicy.of(ACTION, DOC, pc);

        SAXException thrown = assertThrows(SAXException.class, () -> policy.resolveEntity(null, null, null, "missing.xml"));

        String message = policy.recordedFailure().getMessage();
        assertTrue(message.startsWith("x:parse: external reference 'missing.xml' cannot be read, system id 'data/doc.xml': "), message);
        assertTrue(message.contains("not found"), message);
        assertSame(policy.recordedFailure(), thrown.getCause());
    }

    @Test
    void aSystemIdLeavingTheContextRootIsRejected() {
        request.servletPath("/a.jsp");

        assertThrows(IllegalArgumentException.class, () -> SystemIdPolicy.of(ACTION, "../../x.xml", pc));
    }

    @Test
    void anUnparsableSystemIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SystemIdPolicy.of(ACTION, "http://a b/doc.xml", pc));
    }

    // ---- absolute file: system id -------------------------------------------

    @Test
    void fileSystemIdsReadFileTargets(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("a/xfiles"));
        Files.createDirectories(dir.resolve("b"));
        Files.writeString(dir.resolve("a/xfiles/e.xml"), BODY);
        Files.writeString(dir.resolve("b/other.xml"), "<other/>");
        String systemId = dir.resolve("a/doc.xml").toUri().toString();
        SystemIdPolicy policy = SystemIdPolicy.of(ACTION, systemId, pc);

        InputSource entity = policy.resolveEntity(null, null, null, "xfiles/e.xml");
        InputSource elsewhere = policy.resolveEntity(null, null, null, dir.resolve("b/other.xml").toUri().toString());

        assertEquals(systemId, policy.parserSystemId());
        assertEquals(BODY, read(entity));
        // URI.resolve drops the empty authority: file:/… rather than file:///…, the same file.
        assertEquals(dir.resolve("a/xfiles/e.xml"), Path.of(URI.create(entity.getSystemId())));
        assertEquals("<other/>", read(elsewhere));
    }

    @Test
    void aFileSystemIdRefusesUncTargets(@TempDir Path dir) {
        String systemId = dir.resolve("doc.xml").toUri().toString();
        // No authority, a path starting with two separators: a UNC path to another host on Windows.
        for (String reference : new String[] {
                "file:////evil/share/x", "file:/%2F%2Fevil/share/x", "file:/%5C%5Cevil/share/x"}) {
            SystemIdPolicy policy = SystemIdPolicy.of(ACTION, systemId, pc);

            assertThrows(SAXException.class, () -> policy.resolveEntity(null, null, null, reference), reference);

            // "refused", not "cannot be read": nothing was opened.
            assertEquals("x:parse: external reference '" + reference + "' refused, system id '" + systemId + "': "
                    + NOT_SAME_ORIGIN, policy.recordedFailure().getMessage());
        }
    }

    @Test
    void aFileSystemIdRefusesOpaqueTargets(@TempDir Path dir) {
        String systemId = dir.resolve("doc.xml").toUri().toString();
        SystemIdPolicy policy = SystemIdPolicy.of(ACTION, systemId, pc);

        assertThrows(SAXException.class, () -> policy.resolveEntity(null, null, null, "file:foo"));

        assertTrue(policy.recordedFailure().getMessage().endsWith("'file:foo' refused, system id '" + systemId + "': "
                + NOT_SAME_ORIGIN), policy.recordedFailure().getMessage());
    }

    @Test
    void relativeTargetsWithAnAuthorityOrABackslashTraversalAreRefused() {
        context.resource("/e.xml", BODY).resource("/shop/x", "x");
        for (String reference : new String[] {"bodoni-webapp://x/e.xml", "..%5cx"}) {
            SystemIdPolicy policy = SystemIdPolicy.of(ACTION, DOC, pc);

            assertThrows(SAXException.class, () -> policy.resolveEntity(null, null, null, reference), reference);

            assertEquals("x:parse: external reference '" + reference + "' refused, system id 'data/doc.xml': " + OUTSIDE,
                    policy.recordedFailure().getMessage());
        }
    }

    @Test
    void anUnparsableReferenceIsRefused() {
        SystemIdPolicy policy = SystemIdPolicy.of(ACTION, DOC, pc);

        assertThrows(SAXException.class, () -> policy.resolveEntity(null, null, null, "a b|c"));

        assertEquals("x:parse: external reference 'a b|c' refused, system id 'data/doc.xml': not a valid URI",
                policy.recordedFailure().getMessage());
    }

    // ---- absolute http system id --------------------------------------------

    @Test
    void sameOriginHttpTargetsAreFetched() throws Exception {
        server = TestHttpServer.start()
                .route("/ctx/xfiles/resolve.xml", 200, Map.of(), BODY)
                .route("/ctx/a.xml", 200, Map.of(), "<a/>");
        SystemIdPolicy policy = SystemIdPolicy.of(ACTION, importXml(), pc);

        InputSource entity = policy.resolveEntity(null, null, null, "xfiles/resolve.xml");
        InputSource upperCase = policy.resolveEntity(null, null, null, "HTTP://127.0.0.1:" + server.port() + "/ctx/a.xml");

        assertEquals(BODY, read(entity));
        assertEquals("<a/>", read(upperCase));
        assertTrue(server.requestedPaths().contains("/ctx/xfiles/resolve.xml"), server.requestedPaths().toString());
        assertNull(policy.recordedFailure());
    }

    @Test
    void anotherHostIsRefusedAndNotRequested() {
        server = TestHttpServer.start().route("/ctx/a.xml", 200, Map.of(), "<a/>");
        SystemIdPolicy policy = SystemIdPolicy.of(ACTION, importXml(), pc);
        String reference = "http://localhost:" + server.port() + "/ctx/a.xml";

        assertThrows(SAXException.class, () -> policy.resolveEntity(null, null, null, reference));

        assertEquals("x:parse: external reference '" + reference + "' refused, system id '" + importXml() + "': "
                + NOT_SAME_ORIGIN, policy.recordedFailure().getMessage());
        assertTrue(server.requestedPaths().isEmpty(), server.requestedPaths().toString());
    }

    @Test
    void aRedirectIsNotFollowed() {
        server = TestHttpServer.start()
                .route("/ctx/moved.xml", 302, Map.of("Location", "/ctx/e.xml"), "")
                .route("/ctx/e.xml", 200, Map.of(), BODY);
        SystemIdPolicy policy = SystemIdPolicy.of(ACTION, importXml(), pc);

        assertThrows(SAXException.class, () -> policy.resolveEntity(null, null, null, "moved.xml"));

        String message = policy.recordedFailure().getMessage();
        assertTrue(message.contains("cannot be read") && message.contains("HTTP status 302"), message);
        assertFalse(server.requestedPaths().contains("/ctx/e.xml"), server.requestedPaths().toString());
    }

    @Test
    void anHttpErrorIsAReadFailure() {
        server = TestHttpServer.start();
        SystemIdPolicy policy = SystemIdPolicy.of(ACTION, importXml(), pc);

        assertThrows(SAXException.class, () -> policy.resolveEntity(null, null, null, "absent.xml"));

        String message = policy.recordedFailure().getMessage();
        assertTrue(message.contains("HTTP status 404"), message);
        assertInstanceOf(IOException.class, policy.recordedFailure().getCause());
    }

    @Test
    void aConnectionClosedBeforeTheResponseIsAReadFailure() {
        server = TestHttpServer.start().drop("/ctx/dropped.xml");
        SystemIdPolicy policy = SystemIdPolicy.of(ACTION, importXml(), pc);

        assertThrows(SAXException.class, () -> policy.resolveEntity(null, null, null, "dropped.xml"));

        String message = policy.recordedFailure().getMessage();
        assertTrue(message.startsWith("x:parse: external reference 'dropped.xml' cannot be read, "), message);
        assertInstanceOf(IOException.class, policy.recordedFailure().getCause());
        assertTrue(server.requestedPaths().contains("/ctx/dropped.xml"), server.requestedPaths().toString());
    }

    @Test
    void aFailedHttpReadDisconnects() throws Exception {
        for (boolean statusFails : new boolean[] {true, false}) {
            FailingConnection connection = new FailingConnection(statusFails);

            assertThrows(IOException.class, () -> SystemIdPolicy.read(connection));

            assertTrue(connection.disconnected, "status fails: " + statusFails);
        }
    }

    // ---- origin and connections ---------------------------------------------

    @Test
    void sameOriginComparesSchemeHostAndPort() {
        URI page = URI.create("http://example.org/a");

        assertTrue(SystemIdPolicy.sameOrigin(page, URI.create("http://EXAMPLE.org:80/b")));
        assertFalse(SystemIdPolicy.sameOrigin(page, URI.create("https://example.org/b")));
        assertFalse(SystemIdPolicy.sameOrigin(page, URI.create("http://example.org:8080/b")));
        // Registry-based authorities (no parsed host) are compared as a whole.
        assertFalse(SystemIdPolicy.sameOrigin(URI.create("http://a_b/"), URI.create("http://c_d/")));
        assertTrue(SystemIdPolicy.sameOrigin(URI.create("http://a_b/x"), URI.create("http://A_B/y")));
    }

    @Test
    void preparedConnectionsDoNotFollowRedirectsAndHaveTimeouts() throws Exception {
        URLConnection connection = URI.create("http://127.0.0.1:1/").toURL().openConnection();

        assertSame(connection, SystemIdPolicy.prepare(connection));

        assertFalse(((HttpURLConnection) connection).getInstanceFollowRedirects());
        assertEquals(10_000, connection.getConnectTimeout());
        assertEquals(30_000, connection.getReadTimeout());
    }

    @Test
    void anOpaqueSystemIdRefusesEveryReference() {
        SystemIdPolicy policy = SystemIdPolicy.of(ACTION, "jar:file:/a.jar!/doc.xml", pc);

        assertThrows(SAXException.class, () -> policy.resolveEntity(null, null, null, "e.xml"));

        assertEquals("x:parse: external reference 'e.xml' refused, system id 'jar:file:/a.jar!/doc.xml': "
                + "the system id is not hierarchical", policy.recordedFailure().getMessage());
    }

    // ---- URIResolver ----------------------------------------------------------

    @Test
    void uriReferencesBecomeSaxSourcesThatResolveThroughThePolicy() throws Exception {
        context.resource("/xsl/common.xsl", "<common/>").resource("/xsl/x.xsl", "<x/>");
        SystemIdPolicy policy = SystemIdPolicy.of("x:transform", "/xsl/main.xsl", pc);

        Source common = policy.resolve("common.xsl", "bodoni-webapp:/xsl/main.xsl");
        Source fromPage = policy.resolve("x.xsl", null);

        SAXSource sax = assertInstanceOf(SAXSource.class, common);
        assertEquals("bodoni-webapp:/xsl/common.xsl", sax.getSystemId());
        assertSame(policy, sax.getXMLReader().getEntityResolver());
        assertEquals("<common/>", read(sax.getInputSource()));
        assertEquals("bodoni-webapp:/xsl/x.xsl", fromPage.getSystemId());
    }

    @Test
    void aBaseCannotWidenARelativeOrigin() {
        context.resource("/e.xml", BODY);
        SystemIdPolicy policy = SystemIdPolicy.of("x:transform", "/xsl/main.xsl", pc);

        TransformerException thrown = assertThrows(TransformerException.class,
                () -> policy.resolve("e.xml", "http://evil.example/"));

        assertEquals("x:transform: external reference 'e.xml' refused, system id '/xsl/main.xsl': " + OUTSIDE,
                policy.recordedFailure().getMessage());
        assertSame(policy.recordedFailure(), thrown.getCause());
    }

    @Test
    void aBaseCannotWidenAnAbsoluteOrigin() {
        server = TestHttpServer.start().route("/e.xml", 200, Map.of(), BODY);
        SystemIdPolicy policy = SystemIdPolicy.of("x:transform", importXml(), pc);
        String base = "http://localhost:" + server.port() + "/";

        assertThrows(TransformerException.class, () -> policy.resolve("e.xml", base));

        assertTrue(policy.recordedFailure().getMessage().endsWith(NOT_SAME_ORIGIN), policy.recordedFailure().getMessage());
        assertTrue(server.requestedPaths().isEmpty(), server.requestedPaths().toString());
    }

    @Test
    void theFirstFailureIsKept() {
        SystemIdPolicy policy = SystemIdPolicy.of(ACTION, DOC, pc);

        assertThrows(SAXException.class, () -> policy.resolveEntity(null, null, null, "first.xml"));
        JspException first = policy.recordedFailure();
        assertThrows(SAXException.class, () -> policy.resolveEntity(null, null, null, "../../../second.xml"));

        assertSame(first, policy.recordedFailure());
        assertTrue(first.getMessage().contains("'first.xml'"), first.getMessage());
    }

    // ---- helpers --------------------------------------------------------------

    private String importXml() {
        return "http://127.0.0.1:" + server.port() + "/ctx/import.xml";
    }

    private static String read(InputSource source) throws IOException {
        try (InputStream in = source.getByteStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** An HTTP connection whose status or stream fails, recording whether it was disconnected. */
    private static final class FailingConnection extends HttpURLConnection {

        private final boolean statusFails;
        private boolean disconnected;

        FailingConnection(boolean statusFails) throws IOException {
            super(URI.create("http://127.0.0.1/x.xml").toURL());
            this.statusFails = statusFails;
        }

        @Override
        public int getResponseCode() throws IOException {
            if (statusFails) {
                throw new IOException("no status line");
            }
            return HTTP_OK;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            throw new IOException("connection reset");
        }

        @Override
        public void connect() {
            // Nothing to connect: the failures are simulated.
        }

        @Override
        public void disconnect() {
            disconnected = true;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }
    }
}
