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
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Response;
import io.vidocq.foy.chappe.FoyChappeBoot;
import io.vidocq.runtime.extensions.jakartaee.web.foy.live.ServletMount;
import io.vidocq.runtime.extensions.jakartaee.web.foy.spi.ServletAuthentication;
import io.vidocq.runtime.extensions.jakartaee.web.foy.spi.ServletInitParameters;
import io.vidocq.runtime.extensions.jakartaee.web.foy.live.ServletMounts;
import io.vidocq.runtime.extensions.jakartaee.web.foy.fixture.LifecycleListener;
import io.vidocq.runtime.extensions.jakartaee.web.foy.fixture.SessionServlet;
import io.vidocq.runtime.spi.config.ConfigSource;
import io.vidocq.runtime.spi.config.VidocqConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FoyExtensionTest {

    record Mount(String listener, String prefix, Handler handler) {}

    /** One listener and one servlet, named in a {@code WEB-INF/web.xml}. */
    static final String WEB_XML = """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
              <listener>
                <listener-class>io.vidocq.runtime.extensions.jakartaee.web.foy.fixture.LifecycleListener</listener-class>
              </listener>
              <servlet>
                <servlet-name>hello</servlet-name>
                <servlet-class>io.vidocq.runtime.extensions.jakartaee.web.foy.fixture.HelloServlet</servlet-class>
              </servlet>
              <servlet-mapping>
                <servlet-name>hello</servlet-name>
                <url-pattern>/hello</url-pattern>
              </servlet-mapping>
            </web-app>
            """;

    /** {@link #WEB_XML} with a {@code <response-character-encoding>} of ISO-8859-1. */
    static final String ISO_8859_1_WEB_XML = WEB_XML.replace("<listener>",
            "<response-character-encoding>ISO-8859-1</response-character-encoding>\n  <listener>");

    /** One servlet at {@code /set} and {@code /get}, over a session ({@link SessionServlet}). */
    static final String SESSION_WEB_XML = """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
              <servlet>
                <servlet-name>session</servlet-name>
                <servlet-class>io.vidocq.runtime.extensions.jakartaee.web.foy.fixture.SessionServlet</servlet-class>
              </servlet>
              <servlet-mapping>
                <servlet-name>session</servlet-name>
                <url-pattern>/set</url-pattern>
                <url-pattern>/get</url-pattern>
              </servlet-mapping>
            </web-app>
            """;

    private final List<Mount> mounts = new ArrayList<>();
    private final List<FoyExtension> extensions = new ArrayList<>();
    private final List<Runnable> beforeStopHooks = new ArrayList<>();

    @AfterEach
    void forget() {
        extensions.forEach(FoyExtension::onStop);
    }

    @Test
    void noWebComponentMountsNothing(@TempDir Path root) throws Exception {
        FoyExtension extension = configured(Map.of());

        extension.start(null, config(Map.of()), loaderWith(root, null));

        assertTrue(mounts.isEmpty());
        assertTrue(extension.deployments().all().isEmpty());
    }

    @Test
    void mountsTheDeploymentOnTheConfiguredListenerAndPrefix(@TempDir Path root) throws Exception {
        FoyExtension extension = configured(Map.of(
                "vidocq.servlet.context-path", "/app", "vidocq.servlet.listener", "admin"));

        extension.start(null, config(Map.of()), loaderWith(root, WEB_XML));

        assertEquals(1, mounts.size());
        assertEquals("admin", mounts.getFirst().listener());
        assertEquals("/app", mounts.getFirst().prefix());
        assertEquals(1, extension.deployments().all().size());
    }

    @Test
    void rootContextPathMountsOnTheEmptyPrefix(@TempDir Path root) throws Exception {
        FoyExtension extension = configured(Map.of());

        extension.start(null, config(Map.of()), loaderWith(root, WEB_XML));

        assertEquals("", mounts.getFirst().prefix());
    }

    @Test
    void declarativeServletMountSkipsTheAutoMount(@TempDir Path root) throws Exception {
        FoyExtension extension = configured(Map.of());

        extension.start(null, config(Map.of("vidocq.http.mount.web.type", "servlet")), loaderWith(root, WEB_XML));

        assertTrue(mounts.isEmpty());
        assertTrue(extension.deployments().all().isEmpty());
    }

    @Test
    void stopClosesTheDeployment(@TempDir Path root) throws Exception {
        FoyExtension extension = configured(Map.of());
        extension.start(null, config(Map.of()), loaderWith(root, WEB_XML));
        int before = LifecycleListener.DESTROYED.get();

        extension.onStop();

        assertEquals(before + 1, LifecycleListener.DESTROYED.get());
        assertTrue(extension.deployments().all().isEmpty());
    }

    @Test
    void startRegistersABeforeStopHookThatClosesTheDeployments(@TempDir Path root) throws Exception {
        FoyExtension extension = configured(Map.of());
        extension.begin(new Object(), Optional.empty());
        extension.start(null, config(Map.of()), loaderWith(root, WEB_XML));
        int before = LifecycleListener.DESTROYED.get();

        assertEquals(1, beforeStopHooks.size());
        beforeStopHooks.getFirst().run();

        assertEquals(before + 1, LifecycleListener.DESTROYED.get());
        assertTrue(extension.deployments().all().isEmpty());
        extension.onStop();
        assertEquals(before + 1, LifecycleListener.DESTROYED.get());
    }

    @Test
    void aFailureClosingTheDeploymentsIsLoggedAsAnErrorNamingTheExtension() {
        List<LogRecord> records = logsOf(FoyExtension.class, FoyExtension.failureLogged(() -> {
            throw new IllegalStateException("boom");
        }));

        assertEquals(1, records.size());
        assertEquals(Level.SEVERE, records.getFirst().getLevel());
        assertTrue(records.getFirst().getMessage().contains("servlet-foy"));
        assertEquals("boom", records.getFirst().getThrown().getMessage());
    }

    /**
     * An undeploy invalidates the sessions of the application: under {@code vidocq:dev}, each in-JVM reload does, and an
     * open page then starts a new session (a Vaadin UI reloads). The log says so, with the count, instead of leaving
     * the user to guess why the page lost its state.
     */
    @Test
    void closingTheDeploymentsSaysHowManySessionsItInvalidated(@TempDir Path root) throws Exception {
        FoyDeployments deployments = new FoyDeployments();
        FoyChappeBoot.Mounted mounted = FoyDeployments.deploy(null, loaderWith(root, WEB_XML), null,
                1800, "UTF-8", config(Map.of())).orElseThrow();
        deployments.register("vidocq.servlet", "default", mounted);
        mounted.deployment().sessionManager().createNew();
        mounted.deployment().sessionManager().createNew();
        int[] invalidated = new int[1];

        List<LogRecord> records = logsOf(FoyDeployments.class, () -> invalidated[0] = deployments.closeAll(Optional.empty()));

        assertEquals(2, invalidated[0]);
        assertEquals(1, records.size(), records.toString());
        assertEquals(Level.INFO, records.getFirst().getLevel());
        assertEquals("Undeploying the servlet application invalidated 2 HTTP sessions: their clients start new ones "
                + "on their next request", records.getFirst().getMessage());
    }

    @Test
    void anUndeployWithoutSessionsSaysNothingAboutThem(@TempDir Path root) throws Exception {
        FoyDeployments deployments = new FoyDeployments();
        deployments.register("vidocq.servlet", "default", FoyDeployments.deploy(null, loaderWith(root, WEB_XML), null,
                1800, "UTF-8", config(Map.of())).orElseThrow());
        int[] invalidated = {-1};

        List<LogRecord> records = logsOf(FoyDeployments.class, () -> invalidated[0] = deployments.closeAll(Optional.empty()));

        assertEquals(0, invalidated[0]);
        assertEquals(List.of(), records);
    }

    @Test
    void aSessionAttributeSurvivesASwap(@TempDir Path a, @TempDir Path b) throws Exception {
        var handover = new MapHandover();
        String cookie = sessionBeforeTheSwap(a, handover);

        handover.exporting = true;
        List<LogRecord> atClose = logsOf(FoyDeployments.class, beforeStopHooks.getFirst());
        handover.exporting = false;
        FoyExtension next = bootOf(handover);
        ClassLoader loader = loaderWith(b, SESSION_WEB_XML);
        List<LogRecord> atStart = logsOf(FoyDeployments.class, () -> next.start(null, config(Map.of()), loader));

        assertEquals("kept", text(get(mounts.getLast().handler(), "/get", cookie)));
        assertEquals(List.of(), messages(atClose));
        assertEquals(List.of("Hot reload - kept 1 HTTP session across the reload"), messages(atStart));
    }

    @Test
    void aHandoverOutsideAnExportLeavesTheCloseAsToday(@TempDir Path root) throws Exception {
        var handover = new MapHandover();
        sessionBeforeTheSwap(root, handover);

        List<LogRecord> records = logsOf(FoyDeployments.class, beforeStopHooks.getFirst());

        assertEquals(List.of("Undeploying the servlet application invalidated 1 HTTP session: its client starts a new "
                + "one on its next request"), messages(records));
        assertEquals(0, handover.puts);
    }

    @Test
    void aSecondCloseInTheSameStopKeepsTheExport(@TempDir Path a, @TempDir Path b) throws Exception {
        var handover = new MapHandover();
        String cookie = sessionBeforeTheSwap(a, handover);
        FoyExtension first = extensions.getLast();

        handover.exporting = true;
        beforeStopHooks.getFirst().run();
        first.onStop();
        handover.exporting = false;
        bootOf(handover).start(null, config(Map.of()), loaderWith(b, SESSION_WEB_XML));

        assertEquals("kept", text(get(mounts.getLast().handler(), "/get", cookie)));
        assertEquals(1, handover.puts);
    }

    @Test
    void refusedBytesStartWithoutThem(@TempDir Path root) throws Exception {
        var handover = new MapHandover();
        handover.values.put("servlet:sessions:/", new byte[] {1, 2, 3});
        FoyExtension extension = bootOf(handover);
        ClassLoader loader = loaderWith(root, SESSION_WEB_XML);

        List<LogRecord> records = logsOf(FoyDeployments.class, () -> extension.start(null, config(Map.of()), loader));

        assertEquals(1, records.size(), records.toString());
        assertEquals(Level.WARNING, records.getFirst().getLevel());
        assertTrue(records.getFirst().getMessage().startsWith("Hot reload - the HTTP sessions of the previous "
                + "deployment could not be imported, starting without them: "), records.getFirst().getMessage());
        Response response = get(mounts.getLast().handler(), "/get", null);
        assertEquals(200, response.status().code());
        assertEquals("none", text(response));
    }

    @Test
    void aChangedContextPathStartsEmpty(@TempDir Path root) throws Exception {
        var handover = new MapHandover();
        handover.values.put("servlet:sessions:/old", new byte[] {1, 2, 3});
        FoyExtension extension = configured(Map.of("vidocq.servlet.context-path", "/new"));
        extension.begin(new Object(), Optional.of(handover));
        ClassLoader loader = loaderWith(root, SESSION_WEB_XML);

        List<LogRecord> records = logsOf(FoyDeployments.class, () -> extension.start(null, config(Map.of()), loader));

        assertEquals(List.of(), messages(records));
        assertTrue(handover.values.containsKey("servlet:sessions:/old"));
        assertEquals("none", text(get(mounts.getLast().handler(), "/new/get", null)));
    }

    @Test
    void anEmptyExportLogsNothing(@TempDir Path a, @TempDir Path b) throws Exception {
        var handover = new MapHandover();
        bootOf(handover).start(null, config(Map.of()), loaderWith(a, SESSION_WEB_XML));

        handover.exporting = true;
        List<LogRecord> atClose = logsOf(FoyDeployments.class, beforeStopHooks.getFirst());
        handover.exporting = false;
        FoyExtension next = bootOf(handover);
        ClassLoader loader = loaderWith(b, SESSION_WEB_XML);
        List<LogRecord> atStart = logsOf(FoyDeployments.class, () -> next.start(null, config(Map.of()), loader));

        assertEquals(1, handover.puts);
        assertEquals(List.of(), messages(atClose));
        assertEquals(List.of(), messages(atStart));
    }

    @Test
    void theHandoverKeyNamesTheContextPath() {
        assertEquals("servlet:sessions:/", FoyDeployments.handoverKey(""));
        assertEquals("servlet:sessions:/", FoyDeployments.handoverKey("/"));
        assertEquals("servlet:sessions:/shop", FoyDeployments.handoverKey("/shop"));
    }

    /** A boot handed {@code handover}, serving {@link #SESSION_WEB_XML} from {@code root}: the cookie of a session it opened. */
    private String sessionBeforeTheSwap(Path root, MapHandover handover) throws Exception {
        bootOf(handover).start(null, config(Map.of()), loaderWith(root, SESSION_WEB_XML));
        return cookieOf(get(mounts.getLast().handler(), "/set", null));
    }

    private FoyExtension bootOf(MapHandover handover) {
        FoyExtension extension = configured(Map.of());
        extension.begin(new Object(), Optional.of(handover));
        return extension;
    }

    static Response get(Handler handler, String path, String cookie) throws Exception {
        return handler.handle(new ChappeGet(path, cookie));
    }

    /** The {@code JSESSIONID=...} pair of the response's {@code Set-Cookie}. */
    static String cookieOf(Response response) {
        return response.headers().first("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    static String text(Response response) throws java.io.IOException {
        try (var in = response.body().asInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static List<String> messages(List<LogRecord> records) {
        return records.stream().map(LogRecord::getMessage).toList();
    }

    /** The records {@code type}'s logger publishes while {@code action} runs. */
    private static List<LogRecord> logsOf(Class<?> type, Runnable action) {
        Logger logger = Logger.getLogger(type.getName());
        List<LogRecord> records = new ArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                records.add(logRecord);
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        boolean parents = logger.getUseParentHandlers();
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(parents);
        }
        return records;
    }

    @Test
    void stoppingOneBootLeavesTheOtherBootsDeployment(@TempDir Path a, @TempDir Path b) throws Exception {
        FoyExtension first = configured(Map.of());
        FoyExtension second = configured(Map.of());
        first.start(null, config(Map.of()), loaderWith(a, WEB_XML));
        second.start(null, config(Map.of()), loaderWith(b, WEB_XML));
        int before = LifecycleListener.DESTROYED.get();

        first.onStop();

        assertEquals(before + 1, LifecycleListener.DESTROYED.get());
        assertEquals(1, second.deployments().all().size());
    }

    @Test
    void aBootsDeploymentsArePublishedForTheDevPanel(@TempDir Path root) throws Exception {
        Object boot = new Object();
        FoyDeployments deployments = FoyDeployments.forBoot(boot);
        FoyChappeBoot.Mounted mounted = FoyDeployments.deploy(null, loaderWith(root, WEB_XML), "/shop",
                1800, "UTF-8", config(Map.of())).orElseThrow();

        deployments.register("vidocq.servlet", "admin", mounted);

        ServletMount mount = ServletMounts.of(boot).getFirst();
        assertEquals("vidocq.servlet", mount.name());
        assertEquals("admin", mount.listener());
        assertEquals("/shop", mount.contextPath());
        assertEquals(0, mount.activeSessions().getAsInt());
        deployments.closeAll(Optional.empty());
        FoyDeployments.forget(boot);
        assertTrue(ServletMounts.of(boot).isEmpty());
    }

    @Test
    void contributedInitParametersReachTheContext(@TempDir Path root) throws Exception {
        ServletInitParameters provider = config -> Map.of("x", "1");

        FoyChappeBoot.Mounted mounted = FoyDeployments.deploy(null, loaderWith(root, WEB_XML), null,
                1800, "UTF-8", config(Map.of()), List.of(provider)).orElseThrow();

        try {
            assertEquals("1", mounted.servletContext().getInitParameter("x"));
        } finally {
            mounted.close();
        }
    }

    @Test
    void aProviderReadsTheConfiguration() {
        ServletInitParameters provider = config -> Map.of("origins", config.getValue("k").orElse("none"));

        assertEquals(Map.of("origins", "v"),
                FoyDeployments.initParameters(List.of(provider), config(Map.of("k", "v"))));
    }

    @Test
    void twoProvidersGivingTheSameNameFail() {
        List<ServletInitParameters> providers = List.of(config -> Map.of("dup", "1"), config -> Map.of("dup", "2"));
        VidocqConfig config = config(Map.of());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> FoyDeployments.initParameters(providers, config));

        assertTrue(e.getMessage().contains("dup"), e.getMessage());
    }

    @Test
    void aProviderGivingNullFails() {
        List<ServletInitParameters> noMap = List.of(config -> null);
        VidocqConfig config = config(Map.of());

        assertThrows(NullPointerException.class, () -> FoyDeployments.initParameters(noMap, config));
    }

    private static final io.vidocq.foy.spi.security.RequestAuthentication HOOK =
            (request, response, mandatory) -> io.vidocq.foy.spi.security.RequestAuthentication.anonymous();

    @Test
    void anExtensionTakesOverTheAuthenticationOfEveryRequest() {
        ServletAuthentication provider = beanManager -> Optional.of(HOOK);

        assertEquals(Optional.of(HOOK), FoyDeployments.requestAuthentication(List.of(provider), null));
    }

    @Test
    void withoutAnExtensionTheContainersOwnMechanismsApply() {
        ServletAuthentication silent = beanManager -> Optional.empty();

        assertEquals(Optional.empty(), FoyDeployments.requestAuthentication(List.of(), null));
        assertEquals(Optional.empty(), FoyDeployments.requestAuthentication(List.of(silent), null));
    }

    @Test
    void twoExtensionsTakingOverTheAuthenticationFail() {
        ServletAuthentication one = beanManager -> Optional.of(HOOK);
        List<ServletAuthentication> both = List.of(one, one);

        assertThrows(IllegalStateException.class, () -> FoyDeployments.requestAuthentication(both, null));
    }

    private static final io.vidocq.foy.spi.security.AccessControl CONTROL = (path, method, caller, constraints) -> true;

    private static ServletAuthentication controlling(io.vidocq.foy.spi.security.AccessControl control) {
        return new ServletAuthentication() {
            @Override public Optional<io.vidocq.foy.spi.security.RequestAuthentication> requestAuthentication(
                    jakarta.enterprise.inject.spi.BeanManager beanManager) {
                return Optional.empty();
            }

            @Override public Optional<io.vidocq.foy.spi.security.AccessControl> accessControl(
                    jakarta.enterprise.inject.spi.BeanManager beanManager) {
                return Optional.ofNullable(control);
            }
        };
    }

    @Test
    void anExtensionDecidesTheAccessToEveryResource() {
        assertEquals(Optional.of(CONTROL), FoyDeployments.accessControl(List.of(controlling(CONTROL)), null));
    }

    @Test
    void withoutAnExtensionTheConstraintsDecideTheAccess() {
        ServletAuthentication onlyAuthentication = beanManager -> Optional.of(HOOK);

        assertEquals(Optional.empty(), FoyDeployments.accessControl(List.of(), null));
        assertEquals(Optional.empty(), FoyDeployments.accessControl(List.of(onlyAuthentication), null),
                "the default answer of the SPI is no decision");
        assertEquals(Optional.empty(), FoyDeployments.accessControl(List.of(controlling(null)), null));
    }

    @Test
    void twoExtensionsDecidingTheAccessFail() {
        List<ServletAuthentication> both = List.of(controlling(CONTROL), controlling(CONTROL));

        assertThrows(IllegalStateException.class, () -> FoyDeployments.accessControl(both, null));
    }

    @Test
    void eachBootHasItsOwnDeployments() {
        Object boot = new Object();

        assertSame(FoyDeployments.forBoot(boot), FoyDeployments.forBoot(boot));
        assertNotSame(FoyDeployments.forBoot(boot), FoyDeployments.forBoot(new Object()));
        FoyDeployments.forget(boot);
    }

    @Test
    void theServletSecurityConfigurationReachesFoy(@TempDir Path root) throws Exception {
        FoyExtension extension = configured(Map.of());

        VidocqConfig config = config(Map.of("vidocq.servlet.confidential-port", "70000"));
        ClassLoader loader = loaderWith(root, WEB_XML);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> extension.start(null, config, loader));

        assertTrue(e.getMessage().contains("vidocq.servlet.confidential-port"), e.getMessage());
    }

    /** What the application declares, what the configuration says, and the context's resulting encoding. */
    static Stream<Arguments> responseEncodings() {
        return Stream.of(
                Arguments.of("nothing configured: UTF-8", WEB_XML, Map.of(), "UTF-8"),
                Arguments.of("the descriptor wins", ISO_8859_1_WEB_XML, Map.of(), "ISO-8859-1"),
                Arguments.of("the descriptor wins over the key", ISO_8859_1_WEB_XML,
                        Map.of("vidocq.servlet.response-character-encoding", "UTF-16"), "ISO-8859-1"),
                Arguments.of("the key restores the Servlet default", WEB_XML,
                        Map.of("vidocq.servlet.response-character-encoding", "ISO-8859-1"), "ISO-8859-1"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("responseEncodings")
    void theContextsDefaultResponseEncoding(String description, String webXml, Map<String, String> values,
                                            String expected, @TempDir Path root) throws Exception {
        FoyExtension extension = configured(values);

        extension.start(null, config(Map.of()), loaderWith(root, webXml));

        assertEquals(expected, extension.deployments().all().getFirst().mounted().servletContext()
                .getResponseCharacterEncoding(), description);
    }

    private FoyExtension configured(Map<String, String> values) {
        FoyExtension extension = new FoyExtension((l, p, h) -> mounts.add(new Mount(l, p, h)), new FoyDeployments(),
                beforeStopHooks::add);
        extension.configure(new MapConfiguration(values));
        extensions.add(extension);
        return extension;
    }

    /** A child of the test loader that adds {@code root}, holding {@code WEB-INF/web.xml} when given. */
    static ClassLoader loaderWith(Path root, String webXml) throws Exception {
        if (webXml != null) {
            Files.createDirectories(root.resolve("WEB-INF"));
            Files.writeString(root.resolve("WEB-INF/web.xml"), webXml);
        }
        return new URLClassLoader(new URL[]{root.toUri().toURL()}, FoyExtensionTest.class.getClassLoader());
    }

    /** A {@link VidocqConfig} over a map of strings. */
    static VidocqConfig config(Map<String, String> values) {
        return new VidocqConfig() {
            @Override
            public Optional<String> getValue(String key) {
                return Optional.ofNullable(values.get(key));
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> Optional<T> getValue(String key, Class<T> type) {
                return (Optional<T>) getValue(key);
            }

            @Override
            public <T> List<T> getValues(String key, Class<T> elementType) {
                return List.of();
            }

            @Override
            public Iterable<String> getPropertyNames() {
                return values.keySet();
            }

            @Override
            public Iterable<ConfigSource> getConfigSources() {
                return List.of();
            }
        };
    }
}
