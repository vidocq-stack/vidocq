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
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp;

import dev.langchain4j.cdi.mcp.server.registry.McpPromptDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpPromptRegistry;
import dev.langchain4j.cdi.mcp.server.registry.McpResourceDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpResourceRegistry;
import dev.langchain4j.cdi.mcp.server.registry.McpResourceTemplateDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpToolDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpToolRegistry;
import dev.langchain4j.cdi.mcp.server.transport.McpEndpoint;
import dev.langchain4j.cdi.mcp.server.transport.McpMrtrMode;
import dev.langchain4j.cdi.mcp.server.transport.McpServerConfig;
import dev.langchain4j.cdi.mcp.server.transport.McpServerConfigResolver;
import io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.live.McpInspection;
import io.vidocq.runtime.spi.report.Verbosity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** U3 and U4 of Vidocq/vidocq#94: the {@code mcp} section and its anomalies, on a fake report context. */
class McpStartupSectionTest {

    private static final String SECRET = "a-request-state-secret-of-40-characters!";
    /** The real MCP server module: open, and it provides {@code McpServerSPI}, so {@code VIDOCQ-MCP-001} stays silent. */
    private static final Module OPEN_PROVIDING_SERVER = McpEndpoint.class.getModule();

    @Test
    void endpointUrlsKeepEachPathOnceAndDropTheInternalListenRoute() {
        List<String> urls = List.of("http://127.0.0.1:18090/mcp/_listen", "http://127.0.0.1:18090/mcp",
                "http://127.0.0.1:18090/mcp", "http://127.0.0.1:18090/mcp");

        assertEquals(List.of("http://127.0.0.1:18090/mcp"), McpStartupSection.endpointUrls(urls));
    }

    @Test
    void summaryCountsEveryKindWithSingularsAndPlurals() throws Exception {
        RecordingSection section = write(fullContext(Verbosity.SUMMARY, new McpServerConfig()),
                McpInspection.NOTHING, false);

        assertEquals("2 tools, 1 prompt, 1 resource, 1 resource template", section.summary);
        assertEquals(List.of(), section.codes());
        assertTrue(section.rows.isEmpty(), "rows are written at DETAILED only: " + section.rows);
    }

    @Test
    void emptyRegistriesReadAsZeroOfEach() {
        FakeReportContext context = new FakeReportContext(Verbosity.SUMMARY)
                .beanType(McpStartupSection.ENDPOINT)
                .bean(McpToolRegistry.class, new McpToolRegistry());

        RecordingSection section = write(context, McpInspection.NOTHING, false);

        assertEquals("0 tools, 0 prompts, 0 resources, 0 resource templates", section.summary);
        assertEquals(List.of(), section.codes());
    }

    @Test
    void theEndpointUrlJoinsTheSummaryAndTheRowOnceARouteIsDeclared() throws Exception {
        FakeReportContext context = fullContext(Verbosity.DETAILED, new McpServerConfig())
                .route(McpStartupSection.ENDPOINT, "http://127.0.0.1:18090/mcp");

        RecordingSection section = write(context, McpInspection.NOTHING, false);

        assertEquals("2 tools, 1 prompt, 1 resource, 1 resource template at http://127.0.0.1:18090/mcp",
                section.summary);
        assertEquals("http://127.0.0.1:18090/mcp", section.rows.get("endpoint"));
    }

    @Test
    void detailedRowsDescribeTheServerWithItsDefaults() throws Exception {
        RecordingSection section = write(fullContext(Verbosity.DETAILED, new McpServerConfig()),
                McpInspection.NOTHING, false);

        assertEquals(McpStartupSection.ENDPOINT_PATH, section.rows.get("endpoint"));
        assertEquals("2026-07-28 (modern), 2025-03-26 (legacy)", section.rows.get("protocols"));
        assertEquals("langchain4j-cdi unknown (defaults)", section.rows.get("server"));
        assertEquals(List.of("convert_time", "current_time"), section.lists.get("tools"), "sorted");
        assertEquals(List.of("plan_meeting"), section.lists.get("prompts"));
        assertEquals(List.of("time://utc"), section.lists.get("resources"));
        assertEquals(List.of("time://zone/{zone}"), section.lists.get("resource templates"));
        assertEquals("reflection", section.rows.get("invoker"));
        assertEquals("REPLAY, request state TTL PT10M", section.rows.get("mrtr"));
        assertEquals(Map.of("requestStateSecret", false), section.secrets);
        assertEquals("random per-JVM key (single instance only)", section.rows.get("request state key"));
        assertEquals("loopback only", section.rows.get("allowed origins"));
        assertEquals("ttl PT0S, public", section.rows.get("cache hints"));
    }

    @Test
    void detailedRowsFollowAMappedConfiguration() throws Exception {
        McpServerConfig config = McpServerConfig.builder()
                .serverName("time-server").serverVersion("1.2.3")
                .mrtrMode(McpMrtrMode.CONTINUATION).continuationTimeout(Duration.ofMinutes(2))
                .allowedOrigins(List.of("https://a.example", "https://b.example"))
                .cacheTtl(Duration.ofSeconds(30)).cacheScope("private")
                .build();
        FakeReportContext context = fullContext(Verbosity.DETAILED, config)
                .beanType(McpStartupSection.INVOKER_PROVIDER);

        RecordingSection section = write(context, new McpInspection(false, 1, true), true);

        assertEquals("time-server 1.2.3 (vidocq.mcp.*)", section.rows.get("server"));
        assertEquals("CDI 4.1 invoker registered (built at the first call)", section.rows.get("invoker"));
        assertEquals("CONTINUATION, timeout PT2M", section.rows.get("mrtr"));
        assertNull(section.rows.get("request state key"), "CONTINUATION signs no request state");
        assertEquals("https://a.example, https://b.example", section.rows.get("allowed origins"));
        assertEquals("ttl PT30S, private", section.rows.get("cache hints"));
    }

    @Test
    void anApplicationProducerIsNamedAsTheOrigin() throws Exception {
        RecordingSection section = write(fullContext(Verbosity.DETAILED, new McpServerConfig("app", "2.0")),
                new McpInspection(false, 1, false), false);

        assertEquals("app 2.0 (application bean)", section.rows.get("server"));
        assertEquals(List.of(), section.codes());
    }

    @Test
    void theSecretIsOnlyEverSaidToBeConfigured() throws Exception {
        McpServerConfig config = McpServerConfig.builder().requestStateSecret(SECRET).build();

        RecordingSection section = write(fullContext(Verbosity.DETAILED, config),
                new McpInspection(false, 1, true), true);

        assertEquals(Map.of("requestStateSecret", true), section.secrets);
        assertNull(section.rows.get("request state key"));
        assertFalse(section.everything().contains(SECRET), section.everything());
    }

    @Test
    void noEndpointBeanMeansNotDeployed() {
        RecordingSection section = write(new FakeReportContext(Verbosity.DETAILED),
                McpInspection.NOTHING, false);

        assertEquals("not deployed", section.summary);
        assertEquals(List.of(McpStartupSection.CODE_NOT_DEPLOYED), section.codes());
        assertTrue(section.rows.isEmpty());
    }

    @Test
    void aTwinLeavesTheSectionEmptyButForItsAnomaly() throws Exception {
        RecordingSection section = write(fullContext(Verbosity.DETAILED, new McpServerConfig()),
                new McpInspection(true, 0, false), false);

        assertEquals("loaded twice", section.summary);
        assertEquals(List.of(McpStartupSection.CODE_TWIN), section.codes());
        assertTrue(section.rows.isEmpty() && section.lists.isEmpty(), section.everything());
    }

    @Test
    void keysAndAnApplicationProducerTogetherAreReportedAndTheKeysWin() throws Exception {
        RecordingSection section = write(fullContext(Verbosity.DETAILED, new McpServerConfig("time-server", "1.2.3")),
                new McpInspection(false, 2, true), true);

        assertEquals(List.of(McpStartupSection.CODE_TWO_CONFIGS), section.codes());
        assertEquals("time-server 1.2.3 (vidocq.mcp.*)", section.rows.get("server"));
    }

    @Test
    void twoApplicationProducersWithoutKeysAreNotTheExtensionsBusiness() throws Exception {
        RecordingSection section = write(fullContext(Verbosity.DETAILED, new McpServerConfig("app", "2.0")),
                new McpInspection(false, 2, false), false);

        assertEquals(List.of(), section.codes());
        assertEquals("app 2.0 (application bean)", section.rows.get("server"));
    }

    @Test
    void unnamedParametersAreReportedOnceWithTheirMethods() throws Exception {
        McpToolRegistry tools = new McpToolRegistry();
        tools.register(tool(FixtureMcpBeans.Unnamed.class, "echo", "echo", String.class));
        McpPromptRegistry prompts = new McpPromptRegistry();
        prompts.register(new McpPromptDescriptor("greet", "", List.of(), FixtureMcpBeans.Unnamed.class,
                FixtureMcpBeans.Unnamed.class.getMethod("greet", String.class)));
        FakeReportContext context = new FakeReportContext(Verbosity.SUMMARY)
                .beanType(McpStartupSection.ENDPOINT)
                .bean(McpToolRegistry.class, tools)
                .bean(McpPromptRegistry.class, prompts);

        RecordingSection section = write(context, McpInspection.NOTHING, false);

        assertEquals(List.of(McpStartupSection.CODE_UNNAMED_PARAMETERS), section.codes());
        assertTrue(section.anomalies.getFirst().message().startsWith("2 MCP method(s) have parameters without a name "
                + "(Unnamed#echo, Unnamed#greet)"), section.anomalies.getFirst().message());
    }

    @Test
    void aServerModuleThatIsNotOpenAndProvidesNothingIsReported() throws Exception {
        RecordingSection section = new RecordingSection();

        McpStartupSection.write(McpInspection.NOTHING, false, Object.class.getModule(),
                fullContext(Verbosity.SUMMARY, new McpServerConfig()), section);

        assertEquals(List.of(McpStartupSection.CODE_DESCRIPTOR), section.codes());
        assertTrue(section.anomalies.getFirst().message().startsWith("Module java.base does not provide "
                + "org.mcpjava.server.spi.McpServerSPI"), section.anomalies.getFirst().message());
        assertTrue(section.anomalies.getFirst().message().contains("; it is not an open module"),
                section.anomalies.getFirst().message());
        assertEquals("2 tools, 1 prompt, 1 resource, 1 resource template", section.summary,
                "the section is still written");
    }

    // ------------------------------------------------------------------------------------------- fixtures

    private static RecordingSection write(FakeReportContext context, McpInspection inspection, boolean mapped) {
        RecordingSection section = new RecordingSection();
        McpStartupSection.write(inspection, mapped, OPEN_PROVIDING_SERVER, context, section);
        return section;
    }

    /** Two tools, one prompt, one resource, one template, and a resolver serving {@code config}. */
    private static FakeReportContext fullContext(Verbosity verbosity, McpServerConfig config) throws Exception {
        Class<?> beans = FixtureMcpBeans.Named.class;
        McpToolRegistry tools = new McpToolRegistry();
        tools.register(tool(beans, "current_time", "currentTime", String.class,
                dev.langchain4j.cdi.mcp.server.api.McpLog.class));
        tools.register(tool(beans, "convert_time", "convertTime", String.class, String.class));
        McpPromptRegistry prompts = new McpPromptRegistry();
        prompts.register(new McpPromptDescriptor("plan_meeting", "", List.of(), beans,
                beans.getMethod("planMeeting", String.class)));
        McpResourceRegistry resources = new McpResourceRegistry();
        resources.register(new McpResourceDescriptor("time://utc", "utc", "", "text/plain", beans,
                beans.getMethod("utc")));
        resources.registerTemplate(new McpResourceTemplateDescriptor("time://zone/{zone}", "time-in-zone", "",
                "text/plain", beans, beans.getMethod("timeInZone", String.class)));
        return new FakeReportContext(verbosity)
                .beanType(McpStartupSection.ENDPOINT)
                .bean(McpToolRegistry.class, tools)
                .bean(McpPromptRegistry.class, prompts)
                .bean(McpResourceRegistry.class, resources)
                .bean(McpServerConfigResolver.class, new McpServerConfigResolver(config));
    }

    private static McpToolDescriptor tool(Class<?> beans, String name, String method, Class<?>... parameters)
            throws NoSuchMethodException {
        Method m = beans.getMethod(method, parameters);
        return new McpToolDescriptor(name, "", null, beans, m);
    }
}
