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
package io.vidocq.grimm.bench;

import io.vidocq.grimm.cdi.GrimmModelCache;
import io.vidocq.grimm.cdi.OpenApiResource;
import io.vidocq.grimm.internal.factory.GrimmOASFactoryResolver;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.spi.OASFactoryResolver;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * M11 benchmark baseline for Grimm:
 * - model build throughput from annotation scan pipeline
 * - serialization latency on the cached /openapi resource rendering
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 700, timeUnit = TimeUnit.MILLISECONDS)
public class OpenApiPipelineBenchmark {

    private static final List<Class<?>> SCANNED_TYPES = List.of(
        UsersResource.class,
        BookingsResource.class,
        ReviewsResource.class,
        HealthResource.class,
        User.class,
        Booking.class,
        Review.class,
        HealthStatus.class
    );

    @State(Scope.Thread)
    public static class BuildState {
        GrimmModelCache cache;

        @Setup(Level.Invocation)
        public void setup() {
            OASFactoryResolver.setInstance(new GrimmOASFactoryResolver());
            cache = new GrimmModelCache();
        }
    }

    @State(Scope.Benchmark)
    public static class RenderState {
        OpenApiResource resource;

        @Setup(Level.Trial)
        public void setup() {
            OASFactoryResolver.setInstance(new GrimmOASFactoryResolver());
            GrimmModelCache cache = new GrimmModelCache();
            cache.initialize(null, SCANNED_TYPES);
            resource = new OpenApiResource(cache);
        }
    }

    @Benchmark
    public void buildModelThroughput(BuildState state, Blackhole blackhole) {
        state.cache.initialize(null, SCANNED_TYPES);
        blackhole.consume(state.cache.getDocument());
    }

    @Benchmark
    public void renderYamlThroughput(RenderState state, Blackhole blackhole) {
        OpenApiResource.RenderedDocument rendered = state.resource.render(null, null);
        blackhole.consume(rendered.body());
        blackhole.consume(rendered.mediaType());
    }

    @Benchmark
    public void renderJsonThroughput(RenderState state, Blackhole blackhole) {
        OpenApiResource.RenderedDocument rendered = state.resource.render("json", "application/json");
        blackhole.consume(rendered.body());
        blackhole.consume(rendered.mediaType());
    }

    @Path("/users")
    @Produces("application/json")
    static class UsersResource {
        @GET
        @Path("/{id}")
        @Operation(summary = "Find user", operationId = "findUser")
        @APIResponse(responseCode = "200", description = "User found", content = @Content(schema = @Schema(implementation = User.class)))
        public User byId(@PathParam("id") String id) {
            return null;
        }

        @POST
        @Consumes("application/json")
        @Operation(summary = "Create user", operationId = "createUser")
        @APIResponse(responseCode = "201", description = "User created")
        public void create(@RequestBody(required = true, content = @Content(schema = @Schema(implementation = User.class))) User user) {
        }
    }

    @Path("/bookings")
    @Produces("application/json")
    static class BookingsResource {
        @GET
        @Path("/{id}")
        @Operation(summary = "Find booking", operationId = "findBooking")
        @APIResponse(responseCode = "200", description = "Booking found", content = @Content(schema = @Schema(implementation = Booking.class)))
        public Booking byId(@PathParam("id") String id) {
            return null;
        }

        @POST
        @Consumes("application/json")
        @Operation(summary = "Create booking", operationId = "createBooking")
        @APIResponse(responseCode = "201", description = "Booking created")
        public void create(@RequestBody(required = true, content = @Content(schema = @Schema(implementation = Booking.class))) Booking booking) {
        }
    }

    @Path("/reviews")
    @Produces("application/json")
    static class ReviewsResource {
        @POST
        @Consumes("application/json")
        @Operation(summary = "Create review", operationId = "createReview")
        @APIResponse(responseCode = "201", description = "Review created")
        public void create(@RequestBody(required = true, content = @Content(schema = @Schema(implementation = Review.class))) Review review) {
        }
    }

    @Path("/health")
    @Produces("application/json")
    static class HealthResource {
        @GET
        @Operation(summary = "Readiness", operationId = "readiness")
        @APIResponse(responseCode = "200", description = "Service status", content = @Content(schema = @Schema(implementation = HealthStatus.class)))
        public HealthStatus status() {
            return null;
        }
    }

    @Schema(name = "User")
    static class User {
        @Schema(required = true)
        public String id;
        public String name;
        public String email;
        public Instant createdAt;
    }

    @Schema(name = "Booking")
    static class Booking {
        public String id;
        public String userId;
        public String reference;
        public Instant createdAt;
    }

    @Schema(name = "Review")
    static class Review {
        public String id;
        public String bookingId;
        public int stars;
        public String comment;
    }

    @Schema(name = "HealthStatus")
    static class HealthStatus {
        public boolean ready;
        public String service;
    }
}
