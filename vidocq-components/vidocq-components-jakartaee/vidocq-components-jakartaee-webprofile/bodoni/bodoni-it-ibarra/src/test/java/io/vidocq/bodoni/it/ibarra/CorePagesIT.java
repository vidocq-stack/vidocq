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
package io.vidocq.bodoni.it.ibarra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Renders pages precompiled by Ibarra (at {@code process-classes}) on a forked Foy-over-Chappe
 * host and checks Bodoni's {@code c:}, {@code fmt:}, {@code x:} and {@code fn:} libraries under
 * both the {@code jakarta.tags.*} URIs and the JSTL 1.2 {@code http://java.sun.com/jsp/jstl/*} URIs
 * (Tags 3.0 §2, §4–§13, §15).
 *
 * <p>The host runs strictly on the module path with {@code -Dpeano.reflection=deny} and
 * without any compiler module: the pages only use {@code String}, arrays, {@code List} and
 * {@code Map} values, which Peano resolves without reflection, and {@link #denyModeIsActive()}
 * proves that the host really denies reflection. The {@code varStatus} page reads
 * {@code LoopTagStatus} properties and the {@code entries} page reads {@code Map.Entry}
 * properties: under deny mode both are served by accessor tables, the {@code LoopTagStatus}
 * one from {@code bodoni-peano} and the {@code Map.Entry} one built into Peano (BUG.md BOD-001).
 * The {@code x:} pages hand EL only {@code Document}, {@code Node}, {@code NodeList},
 * {@code String} values and exceptions printed through {@code toString()}: no new accessor table.
 */
class CorePagesIT {

    private static final String APPLICATION = "io.vidocq.bodoni.it.ibarra";
    private static final String CONTEXT = "/bodoni";

    /** Runtime modules of the host's boot layer (as in Ibarra's ibarra-tck host). */
    private static final Set<String> HOST_MODULES = Set.of(
            "io.vidocq.ibarra.api", "io.vidocq.ibarra.core", "io.vidocq.ibarra.foy",
            "io.vidocq.foy.api", "io.vidocq.foy.core", "io.vidocq.peano.api", "io.vidocq.peano.core",
            "jakarta.servlet", "jakarta.servlet.jsp", "jakarta.el");

    /** Modules deployed with the application, like the jars of a WAR's WEB-INF/lib. */
    private static final Set<String> APPLICATION_LIBRARIES = Set.of(
            "io.vidocq.bodoni.core", "io.vidocq.bodoni.peano", "jakarta.servlet.jsp.jstl");

    /**
     * JDK modules the application layer reads through the JSTL API and Bodoni. The host runs with
     * {@code --limit-modules}: {@code jdk.localedata} provides the CLDR locale data (French, ...)
     * the fmt pages format with.
     */
    private static final String JDK_MODULES = "java.desktop,java.sql,java.xml,java.net.http,jdk.localedata";

    /** "345 987,246": the French group separator of the JDK's CLDR data is U+202F. */
    private static final String FRENCH_NUMBER = "345\u202f987,246";

    private static Process host;
    private static Path log;
    private static int port;
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeAll
    static void startHost() throws Exception {
        Path application = Path.of(System.getProperty("bodoni.it.application"));
        Path webapp = Path.of(System.getProperty("bodoni.it.webapp"));
        Path work = Files.createDirectories(Path.of(System.getProperty("bodoni.it.work")));
        assertTrue(Files.isRegularFile(application.resolve("module-info.class")),
                "precompiled application module expected in " + application);

        List<ModuleReference> available = modularEntries();
        List<Path> hostPath = new ArrayList<>();
        List<Path> applicationPath = new ArrayList<>(List.of(application));
        for (ModuleReference reference : available) {
            String name = reference.descriptor().name();
            Path location = Path.of(reference.location().orElseThrow());
            if (HOST_MODULES.contains(name) || name.startsWith("io.vidocq.chappe.")) {
                hostPath.add(location);
            } else if (APPLICATION_LIBRARIES.contains(name)) {
                applicationPath.add(location);
            }
        }
        assertEquals(APPLICATION_LIBRARIES.size() + 1, applicationPath.size(),
                "application libraries on the test class path: " + applicationPath);

        Path hostClasses = compileHost(work, hostPath);
        List<Path> runtime = new ArrayList<>(hostPath);
        runtime.add(hostClasses);

        log = work.resolve("host.log");
        host = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dpeano.reflection=deny",
                "--module-path", join(runtime),
                "--limit-modules", "bodoni.it.host,io.vidocq.chappe.core," + JDK_MODULES,
                "--add-modules", "io.vidocq.chappe.core," + JDK_MODULES,
                "-m", "bodoni.it.host/host.Main",
                join(applicationPath), APPLICATION, webapp.toString(), CONTEXT)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline && host.isAlive()) {
            Matcher ready = Pattern.compile("READY:(\\d+)\\R").matcher(Files.readString(log));
            if (ready.find()) {
                port = Integer.parseInt(ready.group(1));
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new IllegalStateException("Host did not start:\n" + Files.readString(log));
    }

    @AfterAll
    static void stopHost() throws Exception {
        if (host == null) {
            return;
        }
        try {
            host.getOutputStream().write(1);
            host.getOutputStream().flush();
            if (!host.waitFor(15, TimeUnit.SECONDS)) {
                host.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        } catch (IOException alreadyGone) {
            host.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void forEachIteratesItemsInOrderUnderJakartaUris() throws Exception {
        // The counter is a page-scoped c:set inside the body: proves order and body re-evaluation.
        assertEquals("1:a2:b3:c", paragraph(get("/core.jsp"), "loop"));
    }

    @Test
    void forEachExposesVarStatusProperties() throws Exception {
        String page = get("/status.jsp");
        assertEquals("0:1:a;1:2:b;2:3:c;", paragraph(page, "status"));
        assertEquals("FabcL", paragraph(page, "bounds"));
    }

    @Test
    void forEachExposesMapEntryKeyAndValue() throws Exception {
        // Tags 3.0 §6.1.2: iterating a Map exposes Map.Entry items with key and value.
        assertEquals("k=v;", paragraph(get("/entries.jsp?k=v"), "entries"));
    }

    @Test
    void conditionalsUnderJakartaUris() throws Exception {
        String page = get("/core.jsp");
        assertEquals("three", paragraph(page, "if"));
        assertEquals("bee", paragraph(page, "choose"));
    }

    @Test
    void outEscapesXml() throws Exception {
        assertEquals("&lt;b&gt;", paragraph(get("/core.jsp"), "out"));
    }

    @Test
    void urlPrefixesTheContextPathAndEncodesParams() throws Exception {
        // c:url goes through HttpServletResponse.encodeURL (Tags 3.0 §7.5): the page opens a
        // session and the request carries no session cookie, so Foy may rewrite the URL.
        String url = paragraph(get("/core.jsp"), "url");
        assertTrue(url.matches(Pattern.quote(CONTEXT + "/x") + "(;jsessionid=[^?]+)?" + Pattern.quote("?q=a+b")), url);
    }

    @Test
    void catchExposesTheFailureOfSetWithANullTarget() throws Exception {
        String caught = paragraph(get("/core.jsp"), "catch");
        assertTrue(caught.startsWith("jakarta.servlet.jsp.JspTagException"), caught);
    }

    @Test
    void legacyUrisRenderTheSameLoopAndFunctions() throws Exception {
        String page = get("/legacy.jsp");
        assertEquals("1:a2:b3:c", paragraph(page, "loop"));
        assertEquals("3", paragraph(page, "length"));
    }

    @Test
    void functionsAreCalledFromPrecompiledEl() throws Exception {
        String page = get("/functions.jsp");
        assertEquals("a-b-c", paragraph(page, "join"));
        assertEquals("BODONI", paragraph(page, "upper"));
        assertEquals("parma", paragraph(page, "lower"));
        assertEquals("true/true", paragraph(page, "contains"));
        assertEquals("true/true", paragraph(page, "affixes"));
        assertEquals("2", paragraph(page, "index"));
        assertEquals("Manuale|b|a", paragraph(page, "substring"));
        assertEquals("1888", paragraph(page, "replace"));
        assertEquals("[Parma]", paragraph(page, "trim"));
        assertEquals("&lt;i&gt;&amp;&lt;/i&gt;", paragraph(page, "escape"));
        assertEquals("6/0", paragraph(page, "length"));
    }

    @Test
    void importInlinesARelativeFragmentWithItsParameter() throws Exception {
        // Tags 3.0 §7.4: the fragment declares UTF-8 and writes non-ASCII text; c:param reaches it.
        assertEquals("Manuale Tipografico — Parma, Bodoni, «è»", paragraph(get("/import.jsp"), "fragment").strip());
    }

    @Test
    void importOfAMissingResourceFailsInsideCatch() throws Exception {
        // Tags 3.0 §7.4 "Null & Error Handling": no resource, or a non-2xx status, is a JspException.
        String caught = paragraph(get("/import.jsp"), "missing");
        assertTrue(caught.startsWith("jakarta.servlet.jsp.JspTagException"), caught);
    }

    @Test
    void importOfAResourceAnsweringARedirectFails() throws Exception {
        // Tags 3.0 §7.4: a status outside 2xx set by the included resource is a JspException
        // naming the resource and the status.
        String caught = paragraph(get("/import.jsp"), "status");
        assertTrue(caught.startsWith("jakarta.servlet.jsp.JspTagException") && caught.contains("302"), caught);
    }

    @Test
    void redirectSendsTheContextRelativeLocation() throws Exception {
        // Tags 3.0 §7.6: the context path is prepended to a URL starting with a slash; the
        // response may be rewritten with a session id (encodeRedirectURL).
        HttpResponse<String> response = send("/redirect.jsp");
        assertEquals(302, response.statusCode(), () -> response.body() + "\n--- host log ---\n" + hostLog());
        String location = response.headers().firstValue("Location").orElse("");
        assertTrue(location.matches("(http://127\\.0\\.0\\.1:" + port + ")?" + Pattern.quote(CONTEXT + "/core.jsp")
                + "(;jsessionid=[^?]+)?" + Pattern.quote("?from=redirect")), location);
    }

    @Test
    void fmtMessageResolvesABundleOfAnOpenedPackage() throws Exception {
        // The French strings come from the JDK's CLDR data and the page's own bundles.
        assertEquals("Bonjour de la part de Bodoni", paragraph(get("/fmt.jsp"), "greeting"));
    }

    @Test
    void fmtMessageResolvesABundleOfANonPackageDirectoryWithItsParam() throws Exception {
        assertEquals("Salut Bodoni", paragraph(get("/fmt.jsp"), "plain"));
    }

    @Test
    void fmtFormatsAndParsesWithTheLocaleSetByThePage() throws Exception {
        String page = get("/fmt.jsp");
        assertEquals(FRENCH_NUMBER, paragraph(page, "number"));
        assertEquals("13.04.02", paragraph(page, "date"));
        assertEquals("samedi 13 avril 2002", paragraph(page, "full"));
    }

    @Test
    void fmtSetLocaleSetsTheResponseLocale() throws Exception {
        HttpResponse<String> response = send("/fmt.jsp");
        assertEquals(200, response.statusCode(), response::body);
        assertEquals("fr", response.headers().firstValue("Content-Language").orElse(""),
                () -> response.headers() + "\n--- host log ---\n" + hostLog());
    }

    @Test
    void fmtLegacyUriFormatsANumber() throws Exception {
        assertEquals(FRENCH_NUMBER, paragraph(get("/fmt-legacy.jsp"), "number"));
    }

    @Test
    void fmtLegacyUriResolvesAMessage() throws Exception {
        assertEquals("Bonjour de la part de Bodoni", paragraph(get("/fmt-legacy.jsp"), "message"));
    }

    @Test
    void xmlOutSelectsWithARequestParameterVariable() throws Exception {
        // Tags 3.0 §11.1.3, §11.3: $param:id is bound to the request parameter.
        assertEquals("Tipografico", paragraph(get("/xml.jsp?id=2"), "out"));
    }

    @Test
    void xmlForEachSetsTheContextNodeAndExposesVarStatus() throws Exception {
        // Tags 3.0 §12.6: the body's relative XPath is evaluated against the current node.
        assertEquals("1:Manuale;2:Tipografico;", paragraph(get("/xml.jsp?id=2"), "loop"));
    }

    @Test
    void xmlConditionalsUseXPathBooleanConversion() throws Exception {
        // Tags 3.0 §12.2–§12.5: a non-empty node-set is true; x:choose runs its first true branch.
        String page = get("/xml.jsp?id=2");
        assertEquals("found", paragraph(page, "if"));
        assertEquals("second", paragraph(page, "when"));
        assertEquals("none", paragraph(page, "otherwise"));
    }

    @Test
    void xmlSetExportsANodeSetThatOutConvertsToItsFirstNode() throws Exception {
        // Tags 3.0 §11.4: x:set stores the node-set; x:out takes its string value (§11.1.4).
        assertEquals("Manuale", paragraph(get("/xml.jsp?id=2"), "titles"));
    }

    @Test
    void xmlTransformImportsAStylesheetThroughARelativeSystemId() throws Exception {
        // Tags 3.0 §13.1, §11.1.7: xsl:import of common.xsl resolves against /xml/main.xsl inside
        // the web application; x:param reaches the stylesheet.
        assertEquals("[Manuale]|[Tipografico]", paragraph(get("/xml.jsp?id=2"), "transform"));
    }

    @Test
    void xmlLegacyUriParsesAndSelects() throws Exception {
        assertEquals("Parma", paragraph(get("/xml-legacy.jsp"), "city"));
    }

    @Test
    void xmlParseResolvesAnEntityInsideTheWebApplicationWithARelativeSystemId() throws Exception {
        assertEquals("Relative", paragraph(entitiesPage(), "relative"));
    }

    @Test
    void xmlParseReadsAnEntityOfTheSameOriginWithAnAbsoluteSystemId() throws Exception {
        // The page fetches xml-part.jsp from this server while it serves the request.
        assertEquals("Absolute", paragraph(entitiesPage(), "absolute"));
    }

    @Test
    void xmlParseRefusesAnEntityOfAnotherOrigin() throws Exception {
        String caught = paragraph(entitiesPage(), "cross");
        assertTrue(caught.contains("refused") && caught.contains("not the same origin") && caught.contains("localhost"),
                caught);
    }

    @Test
    void xmlParseDoesNotFollowARedirect() throws Exception {
        String caught = paragraph(entitiesPage(), "redirect");
        assertTrue(caught.contains("cannot be read") && caught.contains("HTTP status 302"), caught);
    }

    @Test
    void xmlParseRefusesAnEntityWithoutASystemId() throws Exception {
        String caught = paragraph(entitiesPage(), "none");
        assertTrue(caught.contains("refused") && caught.contains("no system id"), caught);
    }

    @Test
    void xmlParseRefusesAnEntityIntoWebInfFromAPublicSystemId() throws Exception {
        String caught = paragraph(get("/xml-protected.jsp"), "webinf");
        assertTrue(caught.contains("refused") && caught.contains("/WEB-INF/secret.xml")
                && caught.contains("inside /WEB-INF/ or /META-INF/ while the system id is not"), caught);
    }

    @Test
    void xmlTransformImportsASiblingUnderWebInf() throws Exception {
        // A stylesheet whose xsltSystemId is under /WEB-INF/ imports /WEB-INF/xsl/common.xsl.
        assertEquals("{Manuale}", paragraph(get("/xml-protected.jsp"), "transform"));
    }

    @Test
    void denyModeIsActive() throws Exception {
        // Guards the premise of this IT: the host runs with peano.reflection=deny, so reading a
        // bean property of a plain public class without a registered accessor fails.
        HttpResponse<String> response = send("/deny.jsp");
        assertEquals(500, response.statusCode(), response::body);
        String diagnostics = response.body() + hostLog();
        assertTrue(diagnostics.contains("Reflection fallback denied for jakarta.servlet.jsp.JspTagException"),
                () -> response.body() + "\n--- host log ---\n" + hostLog());
    }

    /**
     * The entities page with its two absolute system ids, built here from the host's port: {@code base}
     * on this server, {@code cross} the same server under another host name (another origin).
     */
    private static String entitiesPage() throws Exception {
        String base = "http://127.0.0.1:" + port + CONTEXT + "/xml-doc.xml";
        String cross = "http://localhost:" + port + CONTEXT + "/xml-part.jsp";
        return get("/xml-entities.jsp?base=" + URLEncoder.encode(base, StandardCharsets.UTF_8)
                + "&cross=" + URLEncoder.encode(cross, StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> send(String page) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + CONTEXT + page))
                        .timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String get(String page) throws Exception {
        HttpResponse<String> response = send(page);
        assertEquals(200, response.statusCode(),
                () -> page + " -> " + response.statusCode() + "\n" + response.body() + "\n--- host log ---\n" + hostLog());
        return response.body();
    }

    private static String paragraph(String body, String id) {
        Matcher matcher = Pattern.compile("<p id=\"" + id + "\">(.*?)</p>", Pattern.DOTALL).matcher(body);
        assertTrue(matcher.find(), () -> "no <p id=\"" + id + "\"> in:\n" + body);
        return matcher.group(1);
    }

    private static String hostLog() {
        try {
            return Files.readString(log);
        } catch (IOException e) {
            return "(unreadable: " + e + ")";
        }
    }

    /** Explicit Java modules on the test class path (the IT itself runs on the class path). */
    private static List<ModuleReference> modularEntries() throws IOException {
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<Path> modular = new ArrayList<>();
        for (String item : classPath.split(Pattern.quote(File.pathSeparator))) {
            if (item.isEmpty()) {
                continue;
            }
            Path path = Path.of(item);
            boolean explicit = Files.isRegularFile(path.resolve("module-info.class"));
            if (Files.isRegularFile(path) && item.endsWith(".jar")) {
                try (JarFile jar = new JarFile(path.toFile())) {
                    explicit = jar.getJarEntry("module-info.class") != null
                            || jar.stream().anyMatch(e -> e.getName().matches("META-INF/versions/\\d+/module-info.class"));
                }
            }
            if (explicit) {
                modular.add(path);
            }
        }
        return List.copyOf(ModuleFinder.of(modular.toArray(Path[]::new)).findAll());
    }

    /** Compiles the host sources shipped as test resources against the runtime modules. */
    private static Path compileHost(Path work, List<Path> modulePath) throws IOException {
        Path sources = work.resolve("host-source");
        Path classes = work.resolve("host-classes");
        for (String file : List.of("module-info.java", "host/Main.java")) {
            Path target = sources.resolve(file);
            Files.createDirectories(target.getParent());
            try (var input = CorePagesIT.class.getResourceAsStream("/host/" + file)) {
                if (input == null) {
                    throw new IOException("Missing host source: " + file);
                }
                Files.write(target, input.readAllBytes());
            }
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new ByteArrayOutputStream();
        int status = compiler.run(null, diagnostics, diagnostics, "--module-path", join(modulePath),
                "-d", classes.toString(),
                sources.resolve("module-info.java").toString(), sources.resolve("host/Main.java").toString());
        if (status != 0) {
            throw new IOException("Host compilation failed:\n" + diagnostics);
        }
        return classes;
    }

    private static String join(List<Path> paths) {
        return String.join(File.pathSeparator, paths.stream().map(Path::toString).toList());
    }
}
