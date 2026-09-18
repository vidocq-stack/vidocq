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

import io.vidocq.runtime.devservices.spi.DevService;
import io.vidocq.runtime.devservices.spi.DevServiceContext;
import io.vidocq.runtime.maven.ConsoleColors;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VidocqDevMojoTest {

    @Test
    void connectionReportFallsBackToClassesDirParentWhenBuildDirIsNull(@TempDir Path tmp) throws Exception {
        VidocqDevMojo mojo = new VidocqDevMojo();
        Path classes = Files.createDirectories(tmp.resolve("target/classes"));
        mojo.setClassesDir(classes.toFile());
        // buildDir deliberately left null (the bug Arago's vidocq:dev hit): must not NPE — the report
        // falls back to the parent of the classes dir (target/classes → target).
        mojo.reportConnectionInformation(Map.of(
                "vidocq.pool.url", "jdbc:postgresql://localhost:5432/app",
                "vidocq.pool.username", "app"));
        Path report = tmp.resolve("target/vidocq-dev-services.properties");
        assertTrue(Files.exists(report), "report written under the classes-dir parent (target)");
        assertTrue(Files.readString(report).contains("datasource.default.url="));
    }

    /**
     * The application cannot tell a dev-service datasource from a hand-set {@code -D}: each key the child gets from a
     * dev service is marked {@code vidocq.dev.provided.<key>=<provider id>}, and a key an explicit value kept is not,
     * since the child does not see the dev service's value there.
     */
    @Test
    void marksEveryKeyWhoseValueADevServiceProvided() throws Exception {
        Map<String, String> provided = new LinkedHashMap<>();
        provided.put("vidocq.pool.audit.url", "jdbc:postgresql://localhost:54219/audit");
        provided.put("vidocq.pool.audit.username", "vidocq");
        provided.put("vidocq.pool.audit.password", "vidocq");
        DevServiceManager devs = DevServiceManager.start(List.of(providing("postgres", provided)),
                new DefaultDevServiceContext(Path.of("."), Map.of()), new SystemStreamLog());
        Map<String, String> sysProps = new LinkedHashMap<>();
        sysProps.put("vidocq.profile", "dev");
        sysProps.put("vidocq.pool.audit.username", "app"); // from vidocq.dev.systemProperties: it wins

        VidocqDevMojo.foldDevServiceProperties(sysProps, devs);

        assertEquals("jdbc:postgresql://localhost:54219/audit", sysProps.get("vidocq.pool.audit.url"));
        assertEquals("postgres", sysProps.get("vidocq.dev.provided.vidocq.pool.audit.url"));
        assertEquals("postgres", sysProps.get("vidocq.dev.provided.vidocq.pool.audit.password"));
        assertEquals("app", sysProps.get("vidocq.pool.audit.username"));
        assertFalse(sysProps.containsKey("vidocq.dev.provided.vidocq.pool.audit.username"),
                "the child sees the explicit value, not the dev service's");
        assertEquals(6, sysProps.size(), sysProps.toString());
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

    /** A dev service that always applies and provides {@code props}. */
    private static DevService providing(String id, Map<String, String> props) {
        return new DevService() {
            @Override public String id() { return id; }
            @Override public boolean appliesWhen(DevServiceContext ctx) { return true; }
            @Override public Map<String, String> start(DevServiceContext ctx) { return props; }
            @Override public void stop() { }
        };
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
}
