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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Renders pages precompiled by Ibarra (at {@code process-classes}) on a forked Foy-over-Chappe
 * host and checks Bodoni's {@code c:} and {@code fn:} libraries under both the
 * {@code jakarta.tags.*} URIs and the JSTL 1.2 {@code http://java.sun.com/jsp/jstl/*} URIs
 * (Tags 3.0 §2, §4–§7, §15).
 *
 * <p>The host runs strictly on the module path with {@code -Dpeano.reflection=deny} and
 * without any compiler module: the pages only use {@code String}, arrays, {@code List} and
 * {@code Map} values, which Peano resolves without reflection, and {@link #denyModeIsActive()}
 * proves that the host really denies reflection. The {@code varStatus} page reads
 * {@code LoopTagStatus} properties and the {@code entries} page reads {@code Map.Entry}
 * properties: both need an accessor nothing registers yet (BUG.md BOD-001), so their tests
 * are disabled until then.
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
            "io.vidocq.bodoni.core", "jakarta.servlet.jsp.jstl");

    /** JDK modules the application layer reads through the JSTL API and Bodoni. */
    private static final String JDK_MODULES = "java.desktop,java.sql,java.net.http";

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
    @Disabled("BOD-001: Peano deny mode has no accessor for jakarta.servlet.jsp.jstl.core.LoopTagStatus — see BUG.md")
    void forEachExposesVarStatusProperties() throws Exception {
        String page = get("/status.jsp");
        assertEquals("0:1:a;1:2:b;2:3:c;", paragraph(page, "status"));
        assertEquals("FabcL", paragraph(page, "bounds"));
    }

    @Test
    @Disabled("BOD-001: Peano deny mode has no accessor for java.util.Map.Entry implementations — see BUG.md")
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
    void denyModeIsActive() throws Exception {
        // Guards the premise of this IT: the host runs with peano.reflection=deny, so reading a
        // bean property of a plain public class without a registered accessor fails.
        HttpResponse<String> response = send("/deny.jsp");
        assertEquals(500, response.statusCode(), response::body);
        String diagnostics = response.body() + hostLog();
        assertTrue(diagnostics.contains("Reflection fallback denied for jakarta.servlet.jsp.JspTagException"),
                () -> response.body() + "\n--- host log ---\n" + hostLog());
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
