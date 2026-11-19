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
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.testng.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

/**
 * Test M7b.4b.2 — verifies that at {@code deploy(Archive)} time the Humboldt
 * Arquillian container:
 * <ol>
 *   <li>Extracts classes from the ShrinkWrap WAR (here {@link Greeter})</li>
 *   <li>Starts Vauban CDI with those classes</li>
 *   <li>Starts {@code AutoConfiguredHumboldt} with an in-memory exporter</li>
 *   <li>Registers that OpenTelemetry as {@link GlobalOpenTelemetry}</li>
 * </ol>
 *
 * <p>Direct CDI resolution ({@link VaubanContainer#current()}) is used
 * while waiting for the {@code TestEnricher} to provide {@code @Inject} in M7b.4b.4.</p>
 */
public class HumboldtCdiBootTest extends Arquillian {

    @Deployment
    public static JavaArchive deployment() {
        return ShrinkWrap.create(JavaArchive.class, "humboldt-cdi-boot.jar")
                .addClasses(HumboldtCdiBootTest.class, Greeter.class);
    }

    @Test
    public void vauban_container_is_booted_with_archive_beans() {
        VaubanContainer container = VaubanContainer.current();
        assertNotNull(container, "VaubanContainer.current() must be available after deploy");

        Greeter g = container.select(Greeter.class);
        assertNotNull(g, "Greeter bean must be resolved by Vauban");
        assertEquals(g.hello(), "hello");
    }

    @Test
    public void humboldt_is_booted_and_set_as_global_open_telemetry() {
        OpenTelemetry global = GlobalOpenTelemetry.get();
        assertNotNull(global, "GlobalOpenTelemetry must be set by deployed container");

        Tracer t = global.getTracer("io.vidocq.humboldt.tck.bootcheck");
        var span = t.spanBuilder("boot-check-span").startSpan();
        try {
            assertEquals(span.getSpanContext().getTraceId().length(), 32,
                    "Humboldt span must produce a 32-char hex traceId");
        } finally {
            span.end();
        }
    }

    @ApplicationScoped
    public static class Greeter {
        public String hello() {
            return "hello";
        }
    }
}
