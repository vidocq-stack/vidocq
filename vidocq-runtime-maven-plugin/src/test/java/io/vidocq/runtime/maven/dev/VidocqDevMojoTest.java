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

import io.vidocq.runtime.devservices.host.DefaultDevServiceContext;
import io.vidocq.runtime.devservices.host.DevServiceManager;
import io.vidocq.runtime.devservices.host.DevServicesSession;
import io.vidocq.runtime.devservices.spi.DevService;
import io.vidocq.runtime.devservices.spi.DevServiceContext;
import io.vidocq.runtime.maven.ConsoleColors;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
                } catch (InterruptedException e) {
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
}
