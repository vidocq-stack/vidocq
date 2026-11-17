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
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.core.ReloadRequest;
import io.vidocq.runtime.devservices.host.DefaultDevServiceContext;
import io.vidocq.runtime.devservices.host.DevServiceManager;
import io.vidocq.runtime.devservices.host.DevServicesSession;
import io.vidocq.runtime.devservices.host.StateFile;
import io.vidocq.runtime.devservices.spi.DevService;
import io.vidocq.runtime.devservices.spi.DevServiceContext;
import io.vidocq.runtime.maven.ConsoleColors;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VidocqDevMojoTest {

    @Test
    void commandLineVidocqPropertiesReachTheChild() {
        // mvn vidocq:dev -Dvidocq.devconsole.port=18096 -Dvidocq.chappe.listener.default.port=18094 used to be
        // silently ignored: the child JVM never saw either key and bound the defaults, 8888 and 8080.
        Properties commandLine = new Properties();
        commandLine.setProperty("vidocq.devconsole.port", "18096");
        commandLine.setProperty("vidocq.chappe.listener.default.port", "18094");
        commandLine.setProperty("vidocq.dev.debugPort", "18095");      // configures the goal, not the application
        commandLine.setProperty("vidocq.mainModule", "acme.app");      // idem
        commandLine.setProperty("maven.repo.local", "/tmp/repo");      // not a vidocq key

        Map<String, String> child = new LinkedHashMap<>();
        VidocqDevMojo.forwardCommandLine(child, commandLine);

        assertEquals(Map.of("vidocq.devconsole.port", "18096", "vidocq.chappe.listener.default.port", "18094"), child,
                "the application's vidocq.* keys are forwarded, the goal's own parameters and foreign keys are not");
    }

    @Test
    void whatTheChildAlreadyGetsWinsOverTheCommandLine() {
        Properties commandLine = new Properties();
        commandLine.setProperty("vidocq.profile", "staging");
        commandLine.setProperty("vidocq.devconsole.port", "18096");

        Map<String, String> child = new LinkedHashMap<>();
        child.put("vidocq.profile", "dev");                   // the goal's own value
        child.put("vidocq.devconsole.port", "9000");          // a vidocq.dev.systemProperties entry
        VidocqDevMojo.forwardCommandLine(child, commandLine);

        assertEquals("dev", child.get("vidocq.profile"));
        assertEquals("9000", child.get("vidocq.devconsole.port"),
                "as in vidocq:run, a property the child already gets is not replaced by the command line");
    }

    /**
     * BUG-20261117-01: under {@code vidocq:dev} the recompile of the dev loop builds the main output. A test run
     * builds the test output only, with the switches that keep it away from the main one.
     */
    @Test
    void theTestRunsLeaveTheMainOutputToTheRecompile(@TempDir Path tmp) {
        Path testClasses = tmp.resolve("target/test-classes");
        Path testSources = tmp.resolve("src/test/java");
        MavenProject project = new MavenProject();
        project.getBuild().setTestOutputDirectory(testClasses.toString());
        project.addCompileSourceRoot(tmp.resolve("src/main/java").toString());
        project.addTestCompileSourceRoot(testSources.toString());
        project.addTestCompileSourceRoot(tmp.resolve("target/generated-test-sources/test-annotations").toString());
        VidocqDevMojo mojo = new VidocqDevMojo();
        mojo.setProject(project);
        mojo.setBuildDir(tmp.resolve("target").toFile());

        assertEquals(TestOutputs.ofVidocqDev(new TestOutputs.Output(testClasses, List.of(testSources))),
                mojo.testOutputs(), "the test output, with the test sources a developer saves: not the generated"
                        + " ones, not the main ones (BUG-20261117-03)");
    }

    /** {@code vidocq:dev} starts its dev services unless something says otherwise. */
    @Test
    void devServicesOnByDefault() throws Exception {
        assertTrue(new VidocqDevMojo().devServicesEnabled(key -> Optional.empty()));
    }

    /** Spec §5: {@code vidocq.dev.devServices=false} in {@code vidocq.properties} switches them off. */
    @Test
    void aFalseInTheApplicationsFilesTurnsThemOff() throws Exception {
        assertFalse(new VidocqDevMojo().devServicesEnabled(devServicesInFiles(" False ")));
    }

    /** First match wins: an explicit {@code -D} or goal configuration comes before the files, both ways. */
    @Test
    void anExplicitValueBeatsTheApplicationsFiles() throws Exception {
        VidocqDevMojo mojo = new VidocqDevMojo();
        mojo.setDevServices(true);
        assertTrue(mojo.devServicesEnabled(devServicesInFiles("false")));
        mojo.setDevServices(false);
        assertFalse(mojo.devServicesEnabled(devServicesInFiles("true")));
    }

    private static Function<String, Optional<String>> devServicesInFiles(String value) {
        return key -> "vidocq.dev.devServices".equals(key) ? Optional.of(value) : Optional.empty();
    }

    /**
     * The application cannot tell a dev-service datasource from a hand-set {@code -D}: each key the child gets from a
     * dev service is marked {@code vidocq.dev.provided.<key>=<provider id>}, and a key an explicit value kept is not,
     * since the child does not see the dev service's value there.
     */
    @Test
    void marksEveryKeyWhoseValueADevServiceProvided() throws Exception {
        // FixtureAuditDevService (META-INF/services) is discovered by the real ServiceLoader path
        // VidocqDevMojo#execute() uses: DevServiceManager#start(List, ...) is package-private in
        // vidocq-runtime-devservices-host and not reachable from this package.
        DevServiceManager devs = DevServiceManager.start(
                new DefaultDevServiceContext(Path.of("."), Map.of()), System.getLogger("VidocqDevMojoTest"));
        Map<String, String> sysProps = new LinkedHashMap<>();
        sysProps.put("vidocq.profile", "dev");
        sysProps.put("vidocq.pool.audit.username", "app"); // from vidocq.dev.systemProperties: it wins

        VidocqDevMojo.foldDevServiceProperties(sysProps, devs.collectedProperties(), devs.providers());

        assertEquals("jdbc:postgresql://localhost:54219/audit", sysProps.get("vidocq.pool.audit.url"));
        assertEquals("postgres", sysProps.get("vidocq.dev.provided.vidocq.pool.audit.url"));
        assertEquals("postgres", sysProps.get("vidocq.dev.provided.vidocq.pool.audit.password"));
        assertEquals("app", sysProps.get("vidocq.pool.audit.username"));
        assertFalse(sysProps.containsKey("vidocq.dev.provided.vidocq.pool.audit.username"),
                "the child sees the explicit value, not the dev service's");
        assertEquals(6, sysProps.size(), sysProps.toString());
    }

    /**
     * {@code vidocq:dev}'s shutdown hook and {@code execute()}'s own {@code finally} block both call {@link
     * VidocqDevMojo#closeDevServices(io.vidocq.runtime.devservices.host.DevServicesSession)} on Ctrl+C.
     * Not just "at most once": the loser of that race must not return before the winner's {@code close()}
     * — including the provider's (possibly slow) {@code stop()} — has actually finished. {@code
     * closeDevServices} is {@code synchronized} for exactly this.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void devServicesStopFullyCompletesBeforeEitherCallerReturns(@TempDir Path tmp) throws Exception {
        AtomicInteger stopCalls = new AtomicInteger();
        CountDownLatch stopStarted = new CountDownLatch(1);
        AtomicBoolean stopCompleted = new AtomicBoolean(false);
        DevService slow = new DevService() {
            @Override
            public String id() {
                return "slow";
            }

            @Override
            public boolean appliesWhen(DevServiceContext ctx) {
                return true;
            }

            @Override
            public Map<String, String> start(DevServiceContext ctx) {
                return Map.of();
            }

            @Override
            public void stop() {
                stopCalls.incrementAndGet();
                stopStarted.countDown();
                try {
                    Thread.sleep(300); // simulates a slow container stop (Testcontainers/Ryuk)
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                stopCompleted.set(true);
            }
        };
        DevServicesSession session = DevServicesSession.forTesting("vidocq:dev", tmp, List.of(slow),
                System.getLogger("test"));
        VidocqDevMojo mojo = new VidocqDevMojo();

        AtomicBoolean secondCallerReturnedTooEarly = new AtomicBoolean(false);
        Thread first = new Thread(() -> mojo.closeDevServices(session), "first-closer");
        first.start();
        assertTrue(stopStarted.await(5, TimeUnit.SECONDS), "stop() never started");

        Thread second = new Thread(() -> {
            mojo.closeDevServices(session); // must block until the first call's stop() has fully returned
            if (!stopCompleted.get()) {
                secondCallerReturnedTooEarly.set(true);
            }
        }, "second-closer");
        second.start();
        second.join(TimeUnit.SECONDS.toMillis(5));
        first.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(secondCallerReturnedTooEarly.get(),
                "the second caller returned before the provider's stop() had actually finished");
        assertTrue(stopCompleted.get());
        assertEquals(1, stopCalls.get(), "stop() must run exactly once");
    }

    private static final System.Logger LOG = System.getLogger("VidocqDevMojoTest");

    /** A PostgreSQL-like provider whose container listens on {@code port}. */
    private static DevService postgres(int port) {
        return new DevService() {
            @Override public String id() { return "postgres"; }
            @Override public boolean appliesWhen(DevServiceContext ctx) { return true; }
            @Override public Map<String, String> start(DevServiceContext ctx) {
                return Map.of("vidocq.pool.url", "jdbc:postgresql://localhost:" + port + "/vidocq");
            }
            @Override public void stop() { /* starts no container */ }
        };
    }

    /**
     * BUG-20261114-08: the tests of {@code vidocq:dev} ran against the application's containers, and a test's
     * {@code TRUNCATE} wiped the data the application had just collected. They get dev services of their own, opened
     * at their first run: every run gets their keys, never the dev session's, whose containers keep running.
     */
    @Test
    void theTestsGetDevServicesOfTheirOwnAtTheirFirstRun(@TempDir Path tmp) throws Exception {
        try (DevServicesSession dev = DevServicesSession.forTesting("vidocq:dev", tmp, List.of(postgres(32783)), LOG)) {
            AtomicInteger opens = new AtomicInteger();
            List<String> infos = new ArrayList<>();
            TestServices services = VidocqDevMojo.testServices(dev, () -> {
                opens.incrementAndGet();
                return DevServicesSession.forTesting(DevServicesSession.DEV_TESTS_HOST, tmp,
                        List.of(postgres(32790)), LOG);
            }, infos::add, infos::add);

            assertEquals(0, opens.get(), "no container before the first run");
            services.prepare();
            Map<String, String> keys = services.keys();
            services.close();

            assertEquals(1, opens.get());
            assertEquals("jdbc:postgresql://localhost:32790/vidocq", keys.get("vidocq.pool.url"));
            assertEquals(List.of(DevTestServices.STARTING), infos);
            assertTrue(Files.readString(dev.stateFile()).contains("\"state\":\"running\""),
                    "the application's containers keep running");
        }
    }

    /** Dev services off, or none started for the application: the tests start none either, as before. */
    @Test
    void theTestsStartNoDevServiceWhenTheApplicationHasNone(@TempDir Path tmp) throws Exception {
        DevTestServices.Opener never = () -> {
            throw new AssertionError("the tests' session opened");
        };
        List<String> lines = new ArrayList<>();
        TestServices off = VidocqDevMojo.testServices(null, never, lines::add, lines::add);
        off.prepare();
        assertEquals(Map.of(), off.keys());

        try (DevServicesSession none = DevServicesSession.forTesting("vidocq:dev", tmp, List.of(), LOG)) {
            TestServices nothing = VidocqDevMojo.testServices(none, never, lines::add, lines::add);
            nothing.prepare();
            assertEquals(Map.of(), nothing.keys());
        }
        assertEquals(List.of(), lines);
    }

    /**
     * BUG-20261114-08: the tests' session is the {@code vidocq:dev-tests} host's, with files of its own, and starts from
     * the explicit values the dev session started from, taken before the dev session's own keys joined them: a
     * datasource URL the user set opts the tests' container out too, and the tests keep what they had for it.
     */
    @Test
    void theTestsSessionStartsFromTheDevSessionsExplicitValues(@TempDir Path tmp) throws Exception {
        Map<String, String> explicit = new LinkedHashMap<>(Map.of("vidocq.profile", "dev"));
        DevTestServices.Opener opener = VidocqDevMojo.testSessionOpener(tmp, explicit, key -> Optional.empty(),
                key -> Optional.empty(), className -> false, LOG);
        // What the dev session hands its application afterwards does not reach the tests' session.
        explicit.put("vidocq.pool.audit.url", "jdbc:postgresql://localhost:54219/audit");
        try (DevServicesSession tests = opener.open()) {
            assertEquals(tmp.resolve("target").resolve(StateFile.DEV_TESTS_FILE_NAME), tests.stateFile());
            assertEquals("postgres", tests.providers().get("vidocq.pool.audit.url"));
        }

        DevTestServices.Opener userSet = VidocqDevMojo.testSessionOpener(tmp,
                Map.of("vidocq.pool.audit.url", "jdbc:postgresql://db.example/audit"), key -> Optional.empty(),
                key -> Optional.empty(), className -> false, LOG);
        try (DevServicesSession tests = userSet.open()) {
            assertEquals(Map.of(), tests.injected(), "a URL the user set is no dev service's");
        }
        assertFalse(Files.exists(tmp.resolve("target").resolve(StateFile.FILE_NAME)),
                "the dev session's file is not the tests'");
    }

    /** Whoever reaches a JDWP agent can run any code in the child: by default only this machine can. */
    @Test
    void addsJdwpAgentOnTheLoopbackInterfaceWhenDebugEnabled() {
        VidocqDevMojo mojo = new VidocqDevMojo();
        mojo.setExtraJvmArgs("");
        mojo.setDebugOptions(true, 5005, false);

        List<String> args = mojo.debugJvmArgs();

        assertEquals(List.of("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005"), args);
    }

    @Test
    void honoursCustomPortAndSuspend() {
        VidocqDevMojo mojo = new VidocqDevMojo();
        mojo.setExtraJvmArgs("");
        mojo.setDebugOptions(true, 6789, true);

        assertEquals(List.of("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:6789"),
                mojo.debugJvmArgs());
    }

    /** {@code vidocq.dev.debugHost} is the explicit opt-in to open the agent beyond this machine. */
    @Test
    void anExplicitHostIsPassedToTheAgentAsWritten() {
        VidocqDevMojo mojo = new VidocqDevMojo();
        mojo.setExtraJvmArgs("");
        mojo.setDebugOptions(true, 5005, false);

        mojo.setDebugHost("*");
        assertEquals(List.of("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"),
                mojo.debugJvmArgs());
        mojo.setDebugHost("0.0.0.0");
        assertEquals(List.of("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=0.0.0.0:5005"),
                mojo.debugJvmArgs());
        mojo.setDebugHost(" ");
        assertEquals(List.of("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005"),
                mojo.debugJvmArgs(), "a blank host is the default one, never every interface");
    }

    @Test
    void theAgentIsAnnouncedWithItsHostAndNoWarningOnLoopback() {
        VidocqDevMojo mojo = new VidocqDevMojo();
        CapturingLog log = new CapturingLog();
        mojo.setLog(log);
        mojo.setDebugOptions(true, 5005, false);

        mojo.logDebugAgent();

        assertEquals(List.of("INFO Debug agent (JDWP) on port 5005, host 127.0.0.1 — attach any time"), log.lines);
    }

    @Test
    void aHostOpenToTheNetworkIsAWarning() {
        VidocqDevMojo mojo = new VidocqDevMojo();
        CapturingLog log = new CapturingLog();
        mojo.setLog(log);
        mojo.setDebugOptions(true, 18095, true);
        mojo.setDebugHost("*");

        mojo.logDebugAgent();

        assertEquals("INFO Debug agent (JDWP) on port 18095, host * (every interface)"
                + " — child suspends until a debugger attaches", log.lines.get(0));
        assertEquals(2, log.lines.size(), log.lines.toString());
        String warning = log.lines.get(1);
        assertTrue(warning.startsWith("WARN vidocq.dev.debugHost=* makes the debugger reachable from the network"),
                warning);
        assertTrue(warning.contains("whoever connects to it can run any code in the application JVM"), warning);
    }

    @Test
    void noAnnouncementWithoutAnAgent() {
        VidocqDevMojo mojo = new VidocqDevMojo();
        CapturingLog log = new CapturingLog();
        mojo.setLog(log);
        mojo.setDebugOptions(false, 5005, false);
        mojo.setDebugHost("*");

        mojo.logDebugAgent();

        assertEquals(List.of(), log.lines);
    }

    /**
     * BUG-20261114-03: a second {@code vidocq:dev} on the machine, its debug port left at the default one the first
     * session holds. The child died at start with {@code transport error 202: bind failed}, and nothing said the debug
     * port was the cause. The agent now moves to a free port: the JVM argument and the announcement both carry it.
     */
    @Test
    void aBusyDefaultDebugPortMovesTheAgentToAFreeOne() throws Exception {
        try (ServerSocket firstSession = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int busy = firstSession.getLocalPort();
            VidocqDevMojo mojo = new VidocqDevMojo();
            CapturingLog log = new CapturingLog();
            mojo.setLog(log);
            mojo.setExtraJvmArgs("");
            mojo.setDebugOptions(true, null, false);
            mojo.setDefaultDebugPort(busy);

            mojo.checkDebugPort();
            List<String> args = mojo.debugJvmArgs();
            mojo.logDebugAgent();

            assertEquals(1, args.size(), args.toString());
            String prefix = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:";
            assertTrue(args.get(0).startsWith(prefix), args.toString());
            int moved = Integer.parseInt(args.get(0).substring(prefix.length()));
            assertNotEquals(busy, moved);
            assertEquals(List.of("WARN The debug port " + busy + " is already in use on 127.0.0.1: the debug agent"
                            + " listens on 127.0.0.1:" + moved + " instead, attach the debugger there. Choose its"
                            + " port with -Dvidocq.dev.debugPort=<port>.",
                    "INFO Debug agent (JDWP) on port " + moved + ", host 127.0.0.1 — attach any time"), log.lines);
        }
    }

    /** {@code vidocq.dev.debugPort} names the port the IDE attaches to: busy, the goal fails before the fork. */
    @Test
    void aBusyDebugPortTheUserSetFailsTheGoal() throws Exception {
        try (ServerSocket firstSession = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int busy = firstSession.getLocalPort();
            VidocqDevMojo mojo = new VidocqDevMojo();
            mojo.setDebugOptions(true, busy, false);

            MojoExecutionException failure = assertThrows(MojoExecutionException.class, mojo::checkDebugPort);

            assertTrue(failure.getMessage().startsWith("The debug port " + busy + " is already in use on 127.0.0.1,"
                    + " and vidocq.dev.debugPort sets it"), failure.getMessage());
        }
    }

    @Test
    void noPortCheckWithoutAnAgent() throws Exception {
        try (ServerSocket firstSession = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            VidocqDevMojo mojo = new VidocqDevMojo();
            mojo.setExtraJvmArgs("");
            mojo.setDebugOptions(false, firstSession.getLocalPort(), false);

            mojo.checkDebugPort();

            assertEquals(List.of(), mojo.debugJvmArgs());
        }
    }

    @Test
    void noAgentWhenDebugDisabled() {
        VidocqDevMojo mojo = new VidocqDevMojo();
        mojo.setExtraJvmArgs("-Xmx512m");
        mojo.setDebugOptions(false, 5005, false);

        List<String> args = mojo.debugJvmArgs();

        assertEquals(List.of("-Xmx512m"), args);
        assertFalse(args.stream().anyMatch(a -> a.contains("jdwp")));
    }

    @Test
    void extraJvmArgsComeBeforeTheDebugAgent() {
        VidocqDevMojo mojo = new VidocqDevMojo();
        mojo.setExtraJvmArgs("-Xmx256m -XX:+UseZGC");
        mojo.setDebugOptions(true, 5005, false);

        List<String> args = mojo.debugJvmArgs();

        assertEquals("-Xmx256m", args.get(0));
        assertEquals("-XX:+UseZGC", args.get(1));
        assertTrue(args.get(2).startsWith("-agentlib:jdwp="));
    }

    /**
     * {@code mvn vidocq:dev -Dvidocq.console.color=always} sets the property on the <em>Maven</em> JVM, and
     * the application runs in another one: unless the goal passes it on, asking for colour explicitly used to
     * turn colour off, because nothing else seeds it (unlike {@code vidocq:run}, which forwards every
     * application {@code -Dvidocq.*} of the command line).
     */
    @Test
    void anExplicitColourPolicyReachesTheChild() {
        withProperties("always", "strip", () -> {
            VidocqDevMojo mojo = new VidocqDevMojo();
            mojo.setProfile("dev");
            mojo.setExtraSystemProperties("");

            assertEquals("always", mojo.debugSystemProperties().get(ConsoleColors.COLOR_KEY));
        });
    }

    /** What {@code vidocq.dev.systemProperties} declares for the child is never second-guessed. */
    @Test
    void aDeclaredColourPolicyIsNotOverwritten() {
        withProperties("always", "force", () -> {
            VidocqDevMojo mojo = new VidocqDevMojo();
            mojo.setProfile("dev");
            mojo.setExtraSystemProperties("vidocq.console.color=never");

            assertEquals("never", mojo.debugSystemProperties().get(ConsoleColors.COLOR_KEY));
        });
    }

    /** With nothing asked, the child still inherits Maven's own policy — the IDE Maven console case. */
    @Test
    void mavensOwnColoursReachTheChild() {
        assumeTrue(System.getenv("NO_COLOR") == null || System.getenv("NO_COLOR").isEmpty(),
                "NO_COLOR stops the decision");
        withProperties(null, "force", () -> {
            VidocqDevMojo mojo = new VidocqDevMojo();
            mojo.setProfile("dev");
            mojo.setExtraSystemProperties("");

            assertEquals("always", mojo.debugSystemProperties().get(ConsoleColors.COLOR_KEY));
        });
    }

    /** Keeps the INFO and WARN lines, as {@code "LEVEL message"}. */
    private static final class CapturingLog extends SystemStreamLog {

        final List<String> lines = new ArrayList<>();

        @Override
        public void info(CharSequence content) {
            lines.add("INFO " + content);
        }

        @Override
        public void warn(CharSequence content) {
            lines.add("WARN " + content);
        }
    }

    /** Runs {@code body} with {@code vidocq.console.color} and {@code jansi.mode} set, then restores them. */
    private static void withProperties(String colorKey, String jansiMode, Runnable body) {
        String previousColor = System.getProperty(ConsoleColors.COLOR_KEY);
        String previousJansi = System.getProperty("jansi.mode");
        try {
            set(ConsoleColors.COLOR_KEY, colorKey);
            set("jansi.mode", jansiMode);
            body.run();
        } finally {
            set(ConsoleColors.COLOR_KEY, previousColor);
            set("jansi.mode", previousJansi);
        }
    }

    private static void set(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static Function<String, Optional<String>> files(String key, String value) {
        return k -> k.equals(key) ? Optional.of(value) : Optional.empty();
    }

    private static final Function<String, Optional<String>> NO_FILES = k -> Optional.empty();

    @Test
    void continuousTestingIsOnWhenTheProjectHasTestSources(@TempDir Path dir) throws Exception {
        VidocqDevMojo mojo = new VidocqDevMojo();

        assertFalse(mojo.continuousTestingEnabled(dir, NO_FILES), "no src/test/java");
        Files.createDirectories(dir.resolve("src/test/java"));
        assertTrue(mojo.continuousTestingEnabled(dir, NO_FILES));
    }

    @Test
    void theFilesBeatTheDefaultAndAnExplicitValueBeatsTheFiles(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/test/java"));
        VidocqDevMojo mojo = new VidocqDevMojo();

        assertFalse(mojo.continuousTestingEnabled(dir,
                files(VidocqDevMojo.CONTINUOUS_TESTING_KEY, "false")));
        mojo.setContinuousTesting(true);
        assertTrue(mojo.continuousTestingEnabled(dir, files(VidocqDevMojo.CONTINUOUS_TESTING_KEY, "false")));
    }

    @Test
    void anInvalidSwitchFailsTheGoalNamingTheKey(@TempDir Path dir) {
        MojoExecutionException e = assertThrows(MojoExecutionException.class, () -> new VidocqDevMojo()
                .continuousTestingEnabled(dir, files(VidocqDevMojo.CONTINUOUS_TESTING_KEY, "sometimes")));
        assertTrue(e.getMessage().contains(VidocqDevMojo.CONTINUOUS_TESTING_KEY), e.getMessage());
    }

    /** Records what the loop asks of the tests, and when the reload ran, in one list. */
    private static final class Recording implements TestControl {
        final List<String> calls = new ArrayList<>();

        @Override
        public void changed(TestResults.Trigger trigger, ReadyGate gate) {
            calls.add("changed " + trigger.wire());
        }

        @Override
        public void interrupt() {
            calls.add("interrupt");
        }

        @Override
        public void release() {
            calls.add("release");
        }
    }

    @Test
    void aTestChangeRunsTheTestsWithoutAReload() throws Exception {
        Recording tests = new Recording();

        VidocqDevMojo.onChange(new SourceWatcher.Change(false, true), tests, () -> {
            tests.calls.add("reload");
            return Optional.of(TestControl.ReadyGate.NOW);
        });

        assertEquals(List.of("changed test-change"), tests.calls);
    }

    @Test
    void aMainChangeStopsTheTestsReloadsThenRunsThem() throws Exception {
        Recording tests = new Recording();

        VidocqDevMojo.onChange(new SourceWatcher.Change(true, true), tests, () -> {
            tests.calls.add("reload");
            return Optional.of(TestControl.ReadyGate.NOW);
        });

        assertEquals(List.of("interrupt", "reload", "changed change"), tests.calls);
    }

    @Test
    void aFailedRecompileRunsNoTest() throws Exception {
        Recording tests = new Recording();

        VidocqDevMojo.onChange(new SourceWatcher.Change(true, false), tests, () -> {
            tests.calls.add("reload");
            return Optional.empty();
        });

        assertEquals(List.of("interrupt", "reload", "release"), tests.calls,
                "the requests held during the recompile run after it");
    }

    @Test
    void withoutContinuousTestingAMainChangeOnlyReloads() throws Exception {
        List<String> calls = new ArrayList<>();

        VidocqDevMojo.onChange(new SourceWatcher.Change(true, false), null, () -> {
            calls.add("reload");
            return Optional.of(TestControl.ReadyGate.NOW);
        });

        assertEquals(List.of("reload"), calls);
    }

    /** #138: once the signal is written the child reloads; a failure after it must not respawn the child as well. */
    @Test
    void aStampThatCannotBeReadAfterTheSignalDoesNotRespawn(@TempDir Path dir) throws Exception {
        Path reloadFile = dir.resolve(".vidocq-dev-reload");
        List<String> warnings = new ArrayList<>();

        Optional<TestControl.ReadyGate> gate = VidocqDevMojo.signalHotReload(reloadFile, "1\n", file -> {
            throw new java.io.IOException("no mtime");
        }, () -> true, warnings::add);

        assertTrue(gate.isPresent(), "the child was signalled: no respawn");
        assertTrue(gate.get().await(() -> false), "the tests run without waiting");
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(Files.exists(reloadFile));
    }

    @Test
    void aSignalThatCannotBeWrittenFallsBackToARespawn(@TempDir Path dir) {
        List<String> warnings = new ArrayList<>();

        Optional<TestControl.ReadyGate> gate = VidocqDevMojo.signalHotReload(dir.resolve("absent/.vidocq-dev-reload"),
                "1\n", ReloadAck::stamp, () -> true, warnings::add);

        assertTrue(gate.isEmpty(), "respawn");
        assertTrue(warnings.getFirst().contains("respawn"), warnings.toString());
    }

    /** VIDOCQ, 2026-11-16: without a hot swap session, the reload file and the log line are today's, byte for byte. */
    @Test
    void withoutDeclarationsTheReloadFileIsTodays(@TempDir Path dir) throws Exception {
        Path reloadFile = dir.resolve(".vidocq-dev-reload");
        long now = System.nanoTime();

        VidocqDevMojo.Signal signal = VidocqDevMojo.signalFor(null, now).orElseThrow();
        VidocqDevMojo.signalHotReload(reloadFile, signal.content(), ReloadAck::stamp, () -> true, _ -> { });

        assertEquals(now + "\n", Files.readString(reloadFile));
        assertTrue(Files.readString(reloadFile).matches("\\d+\n"));
        assertEquals("Hot reload signalled after 5 ms (in-JVM layer swap).", VidocqDevMojo.hostLine(5, signal));
    }

    @Test
    void theRequestCarriesTheModeAndTheReason() {
        VidocqDevMojo.Signal signal =
                VidocqDevMojo.signal(new ReloadRequest.Swap("MainView gained a method lambda$new$1"), 42);

        assertEquals("42\nswap MainView gained a method lambda$new$1\n", signal.content());
    }

    @Test
    void theHostLineNamesTheReason() {
        assertEquals("Hot reload signalled after 7 ms (layer swap: MainView gained a method lambda$new$1).",
                VidocqDevMojo.hostLine(7,
                        VidocqDevMojo.signal(new ReloadRequest.Swap("MainView gained a method lambda$new$1"), 1)));
        var methods = new java.util.LinkedHashMap<String, java.util.SequencedSet<String>>();
        methods.put("app.A", new java.util.LinkedHashSet<>(List.of("a")));
        methods.put("app.B", new java.util.LinkedHashSet<>(List.of("b")));
        assertEquals("Hot reload signalled after 7 ms (class redefinition: 2 classes).",
                VidocqDevMojo.hostLine(7, VidocqDevMojo.signal(new ReloadRequest.Redefine(methods), 1)));
    }

    /** Review Focus 2: nothing to reload still runs the tests of the change, at once. */
    @Test
    void anUnchangedOutputReloadsNothingAndTheTestsStillRun() throws Exception {
        Recording tests = new Recording();
        List<String> info = new ArrayList<>();

        VidocqDevMojo.onChange(new SourceWatcher.Change(true, false), tests,
                () -> VidocqDevMojo.nothingReloaded(12, info::add));

        assertEquals(List.of("interrupt", "changed change"), tests.calls);
        assertEquals(List.of("Recompiled in 12 ms: the compiled output did not change, nothing reloaded."), info);
        assertEquals(Optional.of(TestControl.ReadyGate.NOW), VidocqDevMojo.nothingReloaded(1, _ -> { }));
    }

    /**
     * BUG-20261117-03: a save made while the recompile runs, after javac read the source, is compiled by the next
     * recompile. The Maven of this test is the compiler plugin and its javac in short: its first run writes the
     * class after the source was saved again, and every run notes whether it found that class when it started.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    @Timeout(30)
    void aSaveMadeWhileTheRecompileRunsIsCompiledByTheNextRecompile(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("src/main/java/acme/Menu.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Menu { }\n");
        // Saved a minute ago: the save the first run makes shows on a file system that keeps the time to the second.
        Files.setLastModifiedTime(source, FileTime.from(Instant.now().minusSeconds(60)));
        Path maven = dir.resolve("fake-mvn");
        Files.writeString(maven, """
                #!/bin/sh
                if [ -e target/classes/acme/Menu.class ]; then echo "its class is there"; else echo "no class"; fi >> runs.txt
                if [ ! -e compiled-once ]; then
                  echo "// saved again, after javac read the source" >> src/main/java/acme/Menu.java
                  mkdir -p target/classes/acme
                  echo "the class of what javac read" > target/classes/acme/Menu.class
                  touch compiled-once
                fi
                """);
        assertTrue(maven.toFile().setExecutable(true));
        RecompileRunner recompile = new RecompileRunner(dir, maven.toString());
        List<String> info = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        SavedWhileCompiling saved = new SavedWhileCompiling(List.of(dir.resolve("src/main/java")),
                dir.resolve("target/classes"), "recompile", info::add, warnings::add);

        assertEquals(0, VidocqDevMojo.recompile(recompile, saved));
        assertEquals(0, VidocqDevMojo.recompile(recompile, saved));

        assertEquals(List.of("no class", "no class"), Files.readAllLines(dir.resolve("runs.txt")),
                "what each recompile found: the second one compiles the source, as it has no class");
        assertEquals(List.of("Menu.java was saved since the previous recompile started and its class is not older:"
                + " compiling it again."), info);
        assertEquals(List.of(), warnings);
    }

    @Test
    void theNextRecompileWaitsForTheAcknowledgement(@TempDir Path dir) throws Exception {
        Path reloadFile = dir.resolve(".vidocq-dev-reload");
        Files.writeString(reloadFile, "1\n");
        long stamp = ReloadAck.stamp(reloadFile);
        var polls = new java.util.concurrent.atomic.AtomicInteger();

        boolean acknowledged = ReloadAck.await(reloadFile, stamp, Duration.ofSeconds(30), () -> {
            if (polls.incrementAndGet() == 3) {
                try {
                    Files.writeString(ReloadAck.readyFile(reloadFile), stamp + "\n");
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            return true;
        });

        assertTrue(acknowledged);
        assertEquals(3, polls.get(), "the wait went on until the child wrote the stamp");
    }

    @Test
    void aDeadChildOrATimeoutLeavesTheRequestUnacknowledged(@TempDir Path dir) throws Exception {
        Path reloadFile = dir.resolve(".vidocq-dev-reload");
        Files.writeString(reloadFile, "1\n");
        long stamp = ReloadAck.stamp(reloadFile);

        assertFalse(ReloadAck.await(reloadFile, stamp, Duration.ofSeconds(30), () -> false));
        assertFalse(ReloadAck.await(reloadFile, stamp, Duration.ofMillis(250), () -> true));
    }

    /** Review Focus 1: a sibling module's classes, on the application path as a directory, are watched too. */
    @Test
    void theClassRootsAreEveryDirectoryOfTheApplicationPath(@TempDir Path dir) throws Exception {
        Path classes = Files.createDirectories(dir.resolve("app/target/classes"));
        Path sibling = Files.createDirectories(dir.resolve("lib/target/classes"));
        Path jar = Files.writeString(dir.resolve("dep.jar"), "");
        Path runtime = Files.writeString(dir.resolve("runtime.jar"), "");

        assertEquals(List.of(classes, sibling), VidocqDevMojo.classRoots(List.of(classes, jar, sibling)));
        assertEquals(List.of(runtime, jar),
                VidocqDevMojo.dependencyJars(List.of(runtime, classes), List.of(classes, jar, sibling, runtime)));
    }

    @Test
    void theProjectsOwnSourcesLeaveOutTheBuildDirectory(@TempDir Path dir) {
        Path build = dir.resolve("target");

        assertEquals(List.of(dir.resolve("src/main/java"), dir.resolve("src/extra/java")),
                VidocqDevMojo.projectSourceRoots(List.of(dir.resolve("src/main/java").toString(),
                        build.resolve("generated-sources/annotations").toString(),
                        dir.resolve("src/extra/java").toString()), build));
    }

    // The hot swap agent (design §1, "Version" and "Opt-out")

    private static final Path AGENT_JAR = Path.of("/repo/vidocq-runtime-dev-agent-0.4.0.jar");

    @Test
    void theAgentIsOnBothPathsOnlyWhenLayerHotReloadAndHotSwapAreOn() {
        var choice = VidocqDevMojo.agentChoice(true, true, true, "0.4.0", _ -> Optional.of(AGENT_JAR));
        List<Path> modulePath = new ArrayList<>(List.of(Path.of("/repo/core.jar")));
        List<String> jvmArgs = new ArrayList<>(List.of("-Xmx1g"));

        VidocqDevMojo.addAgent(choice, modulePath, jvmArgs);

        assertEquals(new VidocqDevMojo.AgentChoice(AGENT_JAR, null), choice);
        assertEquals(List.of(Path.of("/repo/core.jar"), AGENT_JAR), modulePath);
        assertEquals(List.of("-Xmx1g", "--add-modules", "io.vidocq.runtime.dev.agent", "-javaagent:" + AGENT_JAR),
                jvmArgs);
    }

    @Test
    void hotSwapOffSaysWhy() {
        Function<String, Optional<Path>> found = _ -> Optional.of(AGENT_JAR);
        assertEquals(new VidocqDevMojo.AgentChoice(null, "vidocq.dev.hotSwap=false"),
                VidocqDevMojo.agentChoice(true, true, false, "0.4.0", found));
        assertEquals(new VidocqDevMojo.AgentChoice(null, "vidocq.dev.layer=false"),
                VidocqDevMojo.agentChoice(false, true, true, "0.4.0", found));
        assertEquals(new VidocqDevMojo.AgentChoice(null, "vidocq.dev.hotReload=false"),
                VidocqDevMojo.agentChoice(true, false, true, "0.4.0", found));
        assertEquals(new VidocqDevMojo.AgentChoice(null, "vidocq-runtime-dev-agent 0.3.0 not found"),
                VidocqDevMojo.agentChoice(true, true, true, "0.3.0", _ -> Optional.empty()));
    }

    @Test
    void theOffLineSaysWhatEachChangeDoes() {
        assertEquals("Hot swap off: vidocq.dev.hotSwap=false, every change swaps the application layer",
                new VidocqDevMojo.AgentChoice(null, "vidocq.dev.hotSwap=false").offLine(true).orElseThrow());
        assertEquals("Hot swap off: vidocq.dev.hotReload=false, every change restarts the application",
                new VidocqDevMojo.AgentChoice(null, "vidocq.dev.hotReload=false").offLine(false).orElseThrow());
        assertTrue(new VidocqDevMojo.AgentChoice(AGENT_JAR, null).offLine(true).isEmpty());
    }

    @Test
    void theAgentFollowsTheApplicationsCoreVersion() {
        var core = new org.apache.maven.artifact.DefaultArtifact("io.vidocq.runtime", "vidocq-runtime-core",
                "0.4.0-20261116.152407-61", "compile", "jar", "",
                new org.apache.maven.artifact.handler.DefaultArtifactHandler("jar"));

        assertEquals("0.4.0-SNAPSHOT", VidocqDevMojo.coreVersion(List.of(core), "0.5.0"));
        assertEquals("0.5.0", VidocqDevMojo.coreVersion(List.of(), "0.5.0"));
    }

    @Test
    void withoutTheAgentNothingIsAdded() {
        List<Path> modulePath = new ArrayList<>();
        List<String> jvmArgs = new ArrayList<>();

        VidocqDevMojo.addAgent(new VidocqDevMojo.AgentChoice(null, "vidocq.dev.hotSwap=false"), modulePath, jvmArgs);

        assertEquals(List.of(), modulePath);
        assertEquals(List.of(), jvmArgs);
    }

    /** Without Chappe there is no console, so vidocq:dev adds no dev tool at all, not even a companion. */
    @Test
    void withoutChappeNoDevToolIsAdded(@TempDir Path dir) throws Exception {
        Artifact ext = DevModulesTest.artifact("g", "ext", DevModulesTest.withDescriptor(dir, "ext.jar", "ext-dev"));
        List<String> lines = new ArrayList<>();
        AtomicBoolean asked = new AtomicBoolean();

        List<Path> added = VidocqDevMojo.devTools(List.of(ext), keys -> {
            asked.set(true);
            return List.of();
        }, coords -> {
            asked.set(true);
            return List.of();
        }, lines::add, lines::add, new HashSet<>());

        assertEquals(List.of(), added);
        assertFalse(asked.get(), "neither the console nor a companion is resolved");
        assertEquals(List.of("Dev tools: no dev console, it needs vidocq-runtime-chappe-webserver-extension"), lines);
    }

    /** With Chappe, the console first, then each companion, sharing what is already on the path. */
    @Test
    void withChappeTheConsoleThenTheCompanionsAreAdded(@TempDir Path dir) throws Exception {
        Artifact chappe = DevModulesTest.artifact("io.vidocq.runtime.extensions.essentials",
                "vidocq-runtime-chappe-webserver-extension", DevModulesTest.plainJar(dir, "chappe.jar"));
        Artifact ext = DevModulesTest.artifact("g", "ext", DevModulesTest.withDescriptor(dir, "ext.jar", "ext-dev"));
        Path console = dir.resolve("console.jar");
        Path spi = DevModulesTest.plainJar(dir, "spi.jar");
        Path dev = DevModulesTest.markedJar(dir, "ext-dev.jar");

        List<Path> added = VidocqDevMojo.devTools(List.of(chappe, ext), keys -> {
            keys.add(DevConsoleJars.CONSOLE_KEY);
            keys.add(DevConsoleJars.SPI_KEY);
            return List.of(console, spi);
        }, coords -> List.of(new DevModules.Resolved("g:ext-dev", dev),
                new DevModules.Resolved(DevConsoleJars.SPI_KEY, spi)), w -> {}, i -> {}, new HashSet<>());

        assertEquals(List.of(console, spi, dev), added);
    }
}
