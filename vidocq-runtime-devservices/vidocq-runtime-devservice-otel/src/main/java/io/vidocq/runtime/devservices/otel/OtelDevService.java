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
package io.vidocq.runtime.devservices.otel;

import io.vidocq.runtime.devservices.spi.DevContainers;
import io.vidocq.runtime.devservices.spi.DevService;
import io.vidocq.runtime.devservices.spi.DevServiceContext;
import io.vidocq.runtime.devservices.spi.DevServiceState;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Starts a Grafana LGTM container ({@code grafana/otel-lgtm}: an OpenTelemetry collector in front of Grafana, Tempo,
 * Loki and Prometheus) for a {@code vidocq:dev} session or a test run, and publishes the OTLP endpoint the application
 * exports its traces, metrics and logs to: {@code otel.exporter.otlp.endpoint} and {@code otel.exporter.otlp.protocol}
 * ({@code http/protobuf}, the protocol Humboldt speaks). Grafana's URL is in the startup report and the dev console.
 *
 * <p>Applies when MicroProfile Telemetry (Humboldt) is on the application's class path and no OTLP endpoint is given
 * ({@code otel.exporter.otlp.endpoint} or {@code OTEL_EXPORTER_OTLP_ENDPOINT} as a {@code -D}, an environment variable
 * or the goal's configuration). MicroProfile Telemetry leaves the SDK off by default: unless the application or the
 * goal sets {@code otel.sdk.disabled} itself, the service publishes {@code otel.sdk.disabled=false}, so that what the
 * application records reaches Grafana.</p>
 *
 * <p>Tunables (all optional): {@code vidocq.dev.otel.image}, and the shared {@code vidocq.dev.reuse} and
 * {@code vidocq.dev.container-prefix} ({@link DevContainers}).</p>
 */
public final class OtelDevService implements DevService {

    /** The image, pinned. */
    static final String DEFAULT_IMAGE = "grafana/otel-lgtm:0.37.0";
    /** The id of this dev service, also the name and label of its container. */
    private static final String ID = "otel";
    private static final int GRAFANA_PORT = 3000;
    private static final int OTLP_GRPC_PORT = 4317;
    private static final int OTLP_HTTP_PORT = 4318;
    /** The class of Humboldt's runtime: on the class path, the application records telemetry. */
    static final String HUMBOLDT = "io.vidocq.humboldt.runtime.HumboldtAutoConfigure";
    static final String ENDPOINT = "otel.exporter.otlp.endpoint";
    static final String ENDPOINT_ENV = "OTEL_EXPORTER_OTLP_ENDPOINT";
    static final String PROTOCOL = "otel.exporter.otlp.protocol";
    static final String SDK_DISABLED = "otel.sdk.disabled";

    private GenericContainer<?> container;
    private String image;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int order() {
        return 300;
    }

    @Override
    public boolean appliesWhen(DevServiceContext ctx) {
        return skipReason(ctx) == null;
    }

    @Override
    public String skipReason(DevServiceContext ctx) {
        if (!ctx.onApplicationClasspath(HUMBOLDT)) {
            return "no MicroProfile Telemetry (" + HUMBOLDT + ") on the class path";
        }
        for (String key : List.of(ENDPOINT, ENDPOINT_ENV)) {
            if (ctx.property(key).isPresent()) {
                return key + " is set";
            }
        }
        return null;
    }

    @Override
    public Map<String, String> start(DevServiceContext ctx) {
        image = ctx.property("vidocq.dev.otel.image").orElse(DEFAULT_IMAGE);
        container = container(ctx, image, ctx.property("vidocq.dev.reuse").map(Boolean::parseBoolean).orElse(false));
        container.start();
        String endpoint = url(OTLP_HTTP_PORT);
        ctx.log().log(System.Logger.Level.INFO, "OpenTelemetry dev service ready: OTLP " + endpoint
                + ", Grafana " + url(GRAFANA_PORT));
        return published(ctx, endpoint);
    }

    /** What the application receives: the endpoint, its protocol, and the SDK switched on unless it says otherwise. */
    static Map<String, String> published(DevServiceContext ctx, String endpoint) {
        Map<String, String> props = new LinkedHashMap<>();
        props.put(ENDPOINT, endpoint);
        props.put(PROTOCOL, "http/protobuf");
        if (ctx.property(SDK_DISABLED).isEmpty() && ctx.applicationProperty(SDK_DISABLED).isEmpty()) {
            props.put(SDK_DISABLED, "false");
        }
        return props;
    }

    /** The container, not started: named and labelled by {@link DevContainers}, a reused one after its image. */
    static GenericContainer<?> container(DevServiceContext ctx, String image, boolean reuse) {
        String name = DevContainers.name(ctx, ID, null, reuse ? image : null);
        // Configured on the instance itself: the caller owns (and stops) this very container.
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(image));
        container.withExposedPorts(GRAFANA_PORT, OTLP_GRPC_PORT, OTLP_HTTP_PORT)
                .withLabels(DevContainers.labels(ctx, ID))
                .withCreateContainerCmdModifier(cmd -> cmd.withName(name));
        container.waitingFor(Wait.forHttp("/api/health").forPort(GRAFANA_PORT).forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(3)));
        if (reuse) {
            container.withReuse(true);
        }
        return container;
    }

    private String url(int port) {
        return "http://" + container.getHost() + ":" + container.getMappedPort(port);
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
            container = null;
        }
    }

    @Override
    public DevServiceState describe(Map<String, String> injected) {
        Map<String, String> endpoints = new LinkedHashMap<>();
        if (container != null) {
            endpoints.put("grafana", url(GRAFANA_PORT));
            endpoints.put("otlp-http", url(OTLP_HTTP_PORT));
            endpoints.put("otlp-grpc", container.getHost() + ":" + container.getMappedPort(OTLP_GRPC_PORT));
        }
        return new DevServiceState(id(), image, endpoints, List.copyOf(injected.keySet()));
    }
}
