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
package io.vidocq.runtime.maven.idea;

import io.vidocq.runtime.maven.idea.RunConfigurationRenderer.Kind;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the bytes of a generated IntelliJ IDEA shared run configuration, in both kinds.
 *
 * <p>The Maven kind is the default. Its reference was produced by IntelliJ's own serialiser: a candidate
 * file loaded with {@code JDOMUtil.load}, deserialised into {@code MavenRunConfiguration$MavenSettings} and
 * serialised back with {@code XmlSerializer} came out identical, element for element (IntelliJ IDEA
 * Ultimate 2026.2.3, build 262.10968.63). That is what keeps IntelliJ from rewriting the file, and with it
 * the marker that says the file is the goal's.
 *
 * <p>The reference is the hand-written {@code .run/McpTimeServerApp.run.xml} of the
 * {@code lc4jcdi-on-vidocq} example (commit {@code 35da0ef}): run from IntelliJ IDEA 2026.2
 * (IU-262.10968.63) after {@code mvn clean}, it ran Make then {@code vidocq:generate}, and the application
 * started with a complete bean index. The renderer must reproduce it byte for byte, in the format
 * IntelliJ itself writes (2-space indent, {@code " />"}, LF, no XML declaration, nothing after the root
 * element), so that IntelliJ's own re-serialisation equals the generated body and it never rewrites the
 * file for formatting alone.
 */
class RunConfigurationRendererTest {

    /** {@code lc4jcdi-on-vidocq/.run/McpTimeServerApp.run.xml} at commit 35da0ef, 653 bytes. */
    static final String MEASURED_REFERENCE = """
            <component name="ProjectRunConfigurationManager">
              <configuration default="false" name="McpTimeServerApp" type="Application" factoryName="Application">
                <option name="ALTERNATIVE_JRE_PATH" value="temurin-25" />
                <option name="ALTERNATIVE_JRE_PATH_ENABLED" value="true" />
                <option name="MAIN_CLASS_NAME" value="io.vidocq.tools.lc4jcdi.mcptimeserver.McpTimeServerApp" />
                <module name="mcp-time-server" />
                <method v="2">
                  <option name="Make" enabled="true" />
                  <option name="Maven.BeforeRunTask" enabled="true" file="$PROJECT_DIR$/mcp-time-server/pom.xml" goal="vidocq:generate" />
                </method>
              </configuration>
            </component>""";

    static final IdeaApplication MCP_TIME_SERVER = new IdeaApplication(
            "io.vidocq.tools:mcp-time-server",
            "io.vidocq.tools.lc4jcdi.mcptimeserver.McpTimeServerApp",
            "McpTimeServerApp",
            "mcp-time-server",
            "mcp-time-server/pom.xml",
            "vidocq:generate");

    /**
     * The VM options line every Application configuration carries since BUG-20261116-01, at the place IntelliJ
     * writes {@code VM_PARAMETERS}: after the module.
     */
    static final String VM_PARAMETERS_LINE =
            "    <option name=\"VM_PARAMETERS\" value=\"--add-modules ALL-MODULE-PATH\" />\n";

    /** The measured reference with the VM options line the goal adds to it (BUG-20261116-01). */
    static final String REFERENCE = MEASURED_REFERENCE.replace("    <module name=\"mcp-time-server\" />\n",
            "    <module name=\"mcp-time-server\" />\n" + VM_PARAMETERS_LINE);

    @Test
    void body_withThePinnedJre_isTheMeasuredReferenceWithTheVmOptions() {
        String body = RunConfigurationRenderer.body(MCP_TIME_SERVER, Kind.APPLICATION, "temurin-25", true);

        assertEquals(REFERENCE, body);
        assertEquals(653 + VM_PARAMETERS_LINE.length(), body.getBytes(StandardCharsets.UTF_8).length);
    }

    /**
     * IntelliJ runs a modular main class with {@code -m}, which resolves only what the application module
     * requires: a module its dependencies need without a {@code requires} of theirs (an SLF4J provider, a
     * service) is left out, and the start fails with {@code NoClassDefFoundError} (BUG-20261116-01).
     * {@code vidocq:run} adds {@code --add-modules ALL-MODULE-PATH}, and so does the configuration, followed
     * by the application's {@code vidocq.run.jvmArgs}.
     */
    @Test
    void body_addsTheWholeModulePathAndTheRunJvmArgs() {
        IdeaApplication application = new IdeaApplication("com.example:app", "com.example.App", "App", "app",
                "pom.xml", "vidocq:generate", null, 0, "-Dvaadin.productionMode=true   -Xmx512m");

        String body = RunConfigurationRenderer.body(application, Kind.APPLICATION, null, true);

        assertTrue(body.contains("""
                    <module name="app" />
                    <option name="VM_PARAMETERS" value="--add-modules ALL-MODULE-PATH -Dvaadin.productionMode=true -Xmx512m" />
                """), body);
    }

    @Test
    void body_byDefault_leavesTheJdkToIntelliJ() {
        String body = RunConfigurationRenderer.body(MCP_TIME_SERVER, Kind.APPLICATION, null, true);

        assertFalse(body.contains("ALTERNATIVE_JRE_PATH"), body);
        assertEquals(REFERENCE
                        .replace("    <option name=\"ALTERNATIVE_JRE_PATH\" value=\"temurin-25\" />\n", "")
                        .replace("    <option name=\"ALTERNATIVE_JRE_PATH_ENABLED\" value=\"true\" />\n", ""),
                body);
    }

    @Test
    void file_startsWithTheMarkerLineThatCarriesTheBodyHash() {
        String body = RunConfigurationRenderer.body(MCP_TIME_SERVER, Kind.APPLICATION, null, true);
        String file = RunConfigurationRenderer.file(MCP_TIME_SERVER, Kind.APPLICATION, null, true);

        assertEquals("<!-- Generated by vidocq:idea. Run \"mvn vidocq:idea\" to update it; once edited, it is left"
                        + " alone. sha256:" + RunConfigurationRenderer.sha256(body) + " -->\n" + body,
                file);
    }

    @Test
    void markerComment_neverContainsADoubleHyphen() {
        String marker = RunConfigurationRenderer.markerLine(
                RunConfigurationRenderer.body(MCP_TIME_SERVER, Kind.APPLICATION, null, true));
        String commentText = marker.substring("<!--".length(), marker.length() - "-->".length());

        assertFalse(commentText.contains("--"), "'--' is not allowed inside an XML comment: " + marker);
    }

    @Test
    void body_withoutTheBeforeLaunchStep_keepsOnlyMake() {
        String body = RunConfigurationRenderer.body(MCP_TIME_SERVER, Kind.APPLICATION, null, false);

        assertTrue(body.contains("""
                    <method v="2">
                      <option name="Make" enabled="true" />
                    </method>
                """), body);
        assertFalse(body.contains("Maven.BeforeRunTask"), body);
    }

    @Test
    void body_runsTheExecutionThatCarriesTheGenerateConfiguration() {
        IdeaApplication application = new IdeaApplication("com.example:beta", "com.example.beta.BetaApp",
                "BetaApp", "beta", "apps/beta/pom.xml", "vidocq:generate@generate");

        String body = RunConfigurationRenderer.body(application, Kind.APPLICATION, null, true);

        assertTrue(body.contains("<option name=\"Maven.BeforeRunTask\" enabled=\"true\""
                + " file=\"$PROJECT_DIR$/apps/beta/pom.xml\" goal=\"vidocq:generate@generate\" />"), body);
    }

    /**
     * IntelliJ's gutter reuses a configuration only when {@code MAIN_CLASS_NAME} equals the class's
     * qualified name, which uses dots for a nested class ({@code AbstractApplicationConfigurationProducer}),
     * while Maven properties carry the binary name with {@code $}.
     */
    @Test
    void body_writesANestedMainClassWithDots() {
        IdeaApplication application = new IdeaApplication("com.example:app", "com.example.Outer$Main",
                "Main", "app", "pom.xml", "vidocq:generate");

        String body = RunConfigurationRenderer.body(application, Kind.APPLICATION, null, true);

        assertTrue(body.contains("<option name=\"MAIN_CLASS_NAME\" value=\"com.example.Outer.Main\" />"), body);
    }

    @Test
    void attributes_areEscapedAsIntelliJEscapesThem() {
        assertEquals("A &amp; &lt;B&gt; &quot;C&quot; it's&#10;x&#13;y&#9;z",
                RunConfigurationRenderer.escapeAttribute("A & <B> \"C\" it's\nx\ry\tz"));

        IdeaApplication application = new IdeaApplication("com.example:app", "com.example.App",
                "A & <B>", "m\"odule", "pom.xml", "vidocq:generate");
        String body = RunConfigurationRenderer.body(application, Kind.APPLICATION, "my \"jdk\"", true);

        assertTrue(body.contains("name=\"A &amp; &lt;B&gt;\""), body);
        assertTrue(body.contains("<module name=\"m&quot;odule\" />"), body);
        assertTrue(body.contains("value=\"my &quot;jdk&quot;\""), body);
    }

    @Test
    void format_isTheOneIntelliJWrites() {
        String file = RunConfigurationRenderer.file(MCP_TIME_SERVER, Kind.APPLICATION, "temurin-25", true);

        assertFalse(file.contains("\r"), "LF only");
        assertFalse(file.contains("<?xml"), "no XML declaration");
        assertFalse(file.endsWith("\n"), "nothing after the root element");
        assertTrue(file.split("\n", -1)[1].startsWith("<component"), "the body follows the marker line");
    }

    @Test
    void fileName_followsIntelliJsSanitisation() {
        assertEquals("McpTimeServerApp.run.xml", RunConfigurationRenderer.fileName("McpTimeServerApp"));
        assertEquals("Beta_ server_api.run.xml", RunConfigurationRenderer.fileName("Beta: server/api"));
        assertEquals("a_b_c_d_e_f_g_h_i_j.run.xml", RunConfigurationRenderer.fileName("a/b\\c?d<e>f*g|h\"i\tj"));
        assertEquals("x".repeat(255) + ".run.xml", RunConfigurationRenderer.fileName("x".repeat(300)));
    }

    /**
     * {@code XmlSerializer.serialize(MavenSettings)} of IntelliJ IDEA 2026.2.3 writes exactly this — options
     * left at their default included, in this order. {@code myGeneralSettings} stays null on purpose: a run
     * configuration that carries its own would replace the user's Maven settings.
     */
    static final String MEASURED_MAVEN_REFERENCE = """
            <component name="ProjectRunConfigurationManager">
              <configuration default="false" name="McpTimeServerApp" type="MavenRunConfiguration" factoryName="Maven">
                <MavenSettings>
                  <option name="myGeneralSettings" />
                  <option name="myRunnerSettings" />
                  <option name="myRunnerParameters">
                    <MavenRunnerParameters>
                      <option name="cmdOptions" />
                      <option name="profiles">
                        <set />
                      </option>
                      <option name="goals">
                        <list>
                          <option value="vidocq:run" />
                        </list>
                      </option>
                      <option name="multimoduleDir" />
                      <option name="pomFileName" value="mcp-time-server/pom.xml" />
                      <option name="profilesMap">
                        <map />
                      </option>
                      <option name="projectsCmdOptionValues">
                        <list />
                      </option>
                      <option name="resolveToWorkspace" value="false" />
                      <option name="workingDirPath" value="$PROJECT_DIR$" />
                    </MavenRunnerParameters>
                  </option>
                </MavenSettings>
                <method v="2" />
              </configuration>
            </component>""";

    @Test
    void mavenBody_isWhatIntelliJsOwnSerialiserWrites() {
        String body = RunConfigurationRenderer.body(MCP_TIME_SERVER, Kind.MAVEN, null, true);

        assertEquals(MEASURED_MAVEN_REFERENCE, body);
    }

    /** No before-launch step: IntelliJ disables its Build step for a Maven configuration, and vidocq:run indexes. */
    @Test
    void mavenBody_runsVidocqRunOnTheApplicationPomAndNothingBeforeLaunch() {
        String body = RunConfigurationRenderer.body(MCP_TIME_SERVER, Kind.MAVEN, null, true);

        assertTrue(body.contains("<option value=\"vidocq:run\" />"), body);
        assertTrue(body.contains("<option name=\"pomFileName\" value=\"mcp-time-server/pom.xml\" />"), body);
        assertTrue(body.contains("<method v=\"2\" />"), body);
        assertFalse(body.contains("Maven.BeforeRunTask"), body);
        assertFalse(body.contains("\"Make\""), body);
        assertEquals(MEASURED_MAVEN_REFERENCE, RunConfigurationRenderer.body(MCP_TIME_SERVER, Kind.MAVEN, null, false),
                "generateBeforeLaunch does not concern the Maven kind");
    }

    /** The Maven runner JRE is also the JDK the application runs on: vidocq:run forks it from Maven's JVM. */
    @Test
    void mavenBody_withAJre_pinsTheMavenRunnerJre() {
        String body = RunConfigurationRenderer.body(MCP_TIME_SERVER, Kind.MAVEN, "temurin-25", true);

        assertEquals(MEASURED_MAVEN_REFERENCE.replace(
                        "      <option name=\"myRunnerSettings\" />\n",
                        """
                              <option name="myRunnerSettings">
                                <MavenRunnerSettings>
                                  <option name="delegateBuildToMaven" value="false" />
                                  <option name="environmentProperties">
                                    <map />
                                  </option>
                                  <option name="jreName" value="temurin-25" />
                                  <option name="mavenProperties">
                                    <map />
                                  </option>
                                  <option name="passParentEnv" value="true" />
                                  <option name="skipTests" value="false" />
                                  <option name="vmOptions" value="" />
                                </MavenRunnerSettings>
                              </option>
                        """),
                body);
        assertEquals("temurin-25", RunConfigurationFiles.pinnedJre(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void mavenBody_escapesItsAttributes() {
        IdeaApplication application = new IdeaApplication("com.example:app", "com.example.App", "A & <B>", "app",
                "apps/a b/pom.xml", "vidocq:generate");

        String body = RunConfigurationRenderer.body(application, Kind.MAVEN, "my \"jdk\"", true);

        assertTrue(body.contains("name=\"A &amp; &lt;B&gt;\" type=\"MavenRunConfiguration\""), body);
        assertTrue(body.contains("<option name=\"pomFileName\" value=\"apps/a b/pom.xml\" />"), body);
        assertTrue(body.contains("<option name=\"jreName\" value=\"my &quot;jdk&quot;\" />"), body);
    }

    @Test
    void mavenFile_carriesTheSameMarkerAsAnyOtherKind() {
        String file = RunConfigurationRenderer.file(MCP_TIME_SERVER, Kind.MAVEN, null, true);

        assertEquals(RunConfigurationRenderer.markerLine(MEASURED_MAVEN_REFERENCE) + "\n" + MEASURED_MAVEN_REFERENCE,
                file);
        assertFalse(file.contains("\r"), "LF only");
        assertFalse(file.contains("<?xml"), "no XML declaration");
        assertFalse(file.endsWith("\n"), "nothing after the root element");
    }

    /** The default is the Maven kind: an IDE build never runs vidocq:generate (Vidocq/vidocq#83). */
    @Test
    void kind_defaultsToMavenAndRefusesAnythingElse() {
        assertEquals(Optional.of(Kind.MAVEN), Kind.parse(null));
        assertEquals(Optional.of(Kind.MAVEN), Kind.parse("  "));
        assertEquals(Optional.of(Kind.MAVEN), Kind.parse("Maven"));
        assertEquals(Optional.of(Kind.APPLICATION), Kind.parse(" APPLICATION "));
        assertEquals(Optional.empty(), Kind.parse("gradle"));
        assertEquals("maven", Kind.MAVEN.property());
        assertEquals("application", Kind.APPLICATION.property());
    }

    // ---- Dev, run and debug configurations (Vidocq/vidocq#143) ----------------------------------

    @Test
    void constants_haveTheDocumentedValues() {
        assertEquals("vidocq:dev", RunConfigurationRenderer.DEV_GOAL);
        assertEquals(List.of("process-classes", "vidocq:dev"), List.copyOf(RunConfigurationRenderer.DEV_GOALS));
        assertEquals(List.of("vidocq:run"), List.copyOf(RunConfigurationRenderer.RUN_GOALS));
        assertEquals(" (dev)", RunConfigurationRenderer.DEV_SUFFIX);
        assertEquals(" (run)", RunConfigurationRenderer.RUN_SUFFIX);
        assertEquals(" (debug)", RunConfigurationRenderer.DEBUG_SUFFIX);
    }

    @Test
    void theDebugConfigurationAttachesToTheDevAgent() {
        String body = RunConfigurationRenderer.debugBody("ServerApp (debug)", "127.0.0.1", 5005);

        assertTrue(body.contains("name=\"ServerApp (debug)\" type=\"Remote\""), body);
        assertTrue(body.contains("<option name=\"HOST\" value=\"127.0.0.1\" />"), body);
        assertTrue(body.contains("<option name=\"PORT\" value=\"5005\" />"), body);
    }

    @Test
    void debugBody_escapesItsNameAndHost() {
        String body = RunConfigurationRenderer.debugBody("A & <B>", "my \"host\"", 5005);

        assertTrue(body.contains("name=\"A &amp; &lt;B&gt;\" type=\"Remote\""), body);
        assertTrue(body.contains("<option name=\"HOST\" value=\"my &quot;host&quot;\" />"), body);
    }

    @Test
    void theDevConfigurationRunsVidocqDevAndTheRunOneVidocqRun() {
        IdeaApplication application = new IdeaApplication("com.example:app", "com.example.App", "ServerApp", "app",
                "pom.xml", "vidocq:generate");

        String dev = RunConfigurationRenderer.mavenBody(application, null, RunConfigurationRenderer.DEV_GOALS,
                "ServerApp (dev)");
        String runConfiguration = RunConfigurationRenderer.mavenBody(application, null,
                RunConfigurationRenderer.RUN_GOALS, "ServerApp (run)");

        // vidocq:dev starts from target/classes as it is: the configuration compiles first, as the CLI does
        // (Vidocq/vidocq#237).
        assertTrue(dev.contains("""
                            <list>
                              <option value="process-classes" />
                              <option value="vidocq:dev" />
                            </list>
                """), dev);
        assertTrue(dev.contains("name=\"ServerApp (dev)\""), dev);
        assertTrue(runConfiguration.contains("<option value=\"vidocq:run\" />"), runConfiguration);
        assertTrue(runConfiguration.contains("name=\"ServerApp (run)\""), runConfiguration);
    }

    /**
     * IntelliJ IDEA 2026.3 runs the tests of a module with a descriptor on the module path and fails to start them
     * (IDEA-391927). The shared JUnit template names the test agent, which completes the module's access from inside
     * the launched JVM, in the local Maven repository: every JUnit run created from it gets the agent. It keeps the
     * module path: no {@code useClassPathOnly}, the former workaround.
     */
    @Test
    void theJunitTemplateNamesTheTestAgentInTheLocalRepository() {
        String body = RunConfigurationRenderer.junitTemplateBody("1.2.3-SNAPSHOT");

        assertEquals("""
                <component name="ProjectRunConfigurationManager">
                  <configuration default="true" type="JUnit" factoryName="JUnit">
                    <option name="MAIN_CLASS_NAME" value="" />
                    <option name="METHOD_NAME" value="" />
                    <option name="TEST_OBJECT" value="class" />
                    <option name="VM_PARAMETERS" value="-ea &quot;-javaagent:$MAVEN_REPOSITORY$/io/vidocq/runtime/\
                vidocq-runtime-test-agent/1.2.3-SNAPSHOT/vidocq-runtime-test-agent-1.2.3-SNAPSHOT.jar&quot;" />
                    <method v="2">
                      <option name="Make" enabled="true" />
                    </method>
                  </configuration>
                </component>""", body);
        assertFalse(body.contains("useClassPathOnly"), body);
        assertEquals("Template JUnit.run.xml",
                RunConfigurationRenderer.fileName(RunConfigurationRenderer.JUNIT_TEMPLATE_NAME));
    }
}
