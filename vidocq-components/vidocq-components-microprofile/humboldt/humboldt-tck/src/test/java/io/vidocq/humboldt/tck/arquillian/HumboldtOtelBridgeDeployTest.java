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
package io.vidocq.humboldt.tck.arquillian;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.testng.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

/**
 * Test M7b.4b.3 — reproduces exactly the pattern used by the official
 * MP Telemetry TCK (see decompiled {@code ExporterSpiTest.createDeployment()}):
 *
 * <ol>
 *   <li>Includes in the WAR an implementation of {@link SpanExporter} (in-memory)
 *       and of {@link ConfigurableSpanExporterProvider}</li>
 *   <li>Registers the provider through {@code addAsServiceProvider(ConfigurableSpanExporterProvider.class, ...)}</li>
 *   <li>Configures {@code otel.traces.exporter=in-memory} in
 *       {@code META-INF/microprofile-config.properties}</li>
 *   <li>The Humboldt Arquillian container must then:
 *     <ul>
 *       <li>Parse microprofile-config.properties</li>
 *       <li>Load {@link TckInMemorySpanExporterProvider} through the services file</li>
 *       <li>Instantiate the exporter and wrap it in {@code OtelSpanExporterBridge}</li>
 *       <li>Attach it to the Humboldt pipeline (through hook M7b.3)</li>
 *     </ul>
 *   </li>
 *   <li>Spans produced by {@link GlobalOpenTelemetry#get()} must therefore
 *       end up in {@link TckInMemorySpanExporter#SPANS}</li>
 * </ol>
 */
public class HumboldtOtelBridgeDeployTest extends Arquillian {

    @Deployment
    public static JavaArchive deployment() {
        return ShrinkWrap.create(JavaArchive.class, "humboldt-otel-bridge.jar")
                .addClasses(HumboldtOtelBridgeDeployTest.class,
                        TckInMemorySpanExporter.class,
                        TckInMemorySpanExporterProvider.class)
                .addAsServiceProvider(ConfigurableSpanExporterProvider.class,
                        TckInMemorySpanExporterProvider.class)
                .addAsResource(new StringAsset(
                                // MP Telemetry 2.2 §"Enabling OpenTelemetry support": SDK disabled by default —
                                // must be explicitly enabled for this bridge test to see spans.
                                "otel.sdk.disabled=false\n" +
                                        "otel.traces.exporter=in-memory\n" +
                                        "otel.service.name=humboldt-tck-bridge-test\n"),
                        "META-INF/microprofile-config.properties");
    }

    @Test
    public void spans_from_global_open_telemetry_arrive_in_tck_in_memory_exporter() {
        TckInMemorySpanExporter.SPANS.clear();
        Tracer tracer = GlobalOpenTelemetry.get().getTracer("io.vidocq.tck.bridge.assert");
        Span s = tracer.spanBuilder("hello-bridge").startSpan();
        s.end();

        List<SpanData> captured = TckInMemorySpanExporter.SPANS;
        assertEquals(captured.size(), 1, "The span must land in the TCK exporter through the bridge");
        SpanData first = captured.getFirst();
        assertNotNull(first);
        assertEquals(first.getName(), "hello-bridge");
        // service.name does come from microprofile-config.properties
        assertEquals(first.getResource().getAttribute(
                io.opentelemetry.api.common.AttributeKey.stringKey("service.name")),
                "humboldt-tck-bridge-test");
    }

    /** Mime exactly the TCK pattern : implements io.opentelemetry.sdk.trace.export.SpanExporter. */
    public static class TckInMemorySpanExporter implements SpanExporter {
        static final List<SpanData> SPANS = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            SPANS.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }

        @Override
        public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }

    /** Mime exactly the TCK pattern : implements ConfigurableSpanExporterProvider. */
    public static class TckInMemorySpanExporterProvider implements ConfigurableSpanExporterProvider {
        @Override
        public SpanExporter createExporter(ConfigProperties config) {
            return new TckInMemorySpanExporter();
        }

        @Override
        public String getName() { return "in-memory"; }
    }
}
