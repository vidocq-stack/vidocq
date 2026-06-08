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

import io.smallrye.openapi.runtime.io.Format;
import io.smallrye.openapi.runtime.io.OpenApiParser;
import io.smallrye.openapi.runtime.io.OpenApiSerializer;
import io.smallrye.openapi.spi.OASFactoryResolverImpl;
import io.vidocq.grimm.cdi.GrimmModelCache;
import io.vidocq.grimm.cdi.OpenApiResource;
import io.vidocq.grimm.internal.factory.GrimmOASFactoryResolver;
import org.eclipse.microprofile.openapi.models.OpenAPI;
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Serialization-only comparison using the same OpenAPI model:
 * - Grimm rendering path through OpenApiResource
 * - SmallRye serializer over the identical model instance
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 700, timeUnit = TimeUnit.MILLISECONDS)
public class SerializationComparisonBenchmark {

    @State(Scope.Benchmark)
    public static class SharedModelState {
        OpenApiResource resource;
        OpenAPI model;
        OpenAPI smallryeModel;

        @Setup(Level.Trial)
        public void setup() {
            OASFactoryResolver.setInstance(new GrimmOASFactoryResolver());
            GrimmModelCache cache = new GrimmModelCache();
            cache.initialize(
                null,
                List.of(
                    OpenApiPipelineBenchmark.UsersResource.class,
                    OpenApiPipelineBenchmark.BookingsResource.class,
                    OpenApiPipelineBenchmark.ReviewsResource.class,
                    OpenApiPipelineBenchmark.HealthResource.class,
                    OpenApiPipelineBenchmark.User.class,
                    OpenApiPipelineBenchmark.Booking.class,
                    OpenApiPipelineBenchmark.Review.class,
                    OpenApiPipelineBenchmark.HealthStatus.class
                )
            );
            model = cache.getDocument();
            resource = new OpenApiResource(cache);

            // Build a SmallRye-native model from Grimm JSON to keep serialization comparison runnable.
            String grimmJson = resource.render("json", "application/json").body();
            OASFactoryResolver.setInstance(new OASFactoryResolverImpl());
            smallryeModel = OpenApiParser.parse(
                new ByteArrayInputStream(grimmJson.getBytes(StandardCharsets.UTF_8)),
                Format.JSON,
                null
            );
            OASFactoryResolver.setInstance(new GrimmOASFactoryResolver());
        }
    }

    @Benchmark
    public void grimmJson(SharedModelState state, Blackhole blackhole) {
        OpenApiResource.RenderedDocument rendered = state.resource.render("json", "application/json");
        blackhole.consume(rendered.body());
    }

    @Benchmark
    public void grimmYaml(SharedModelState state, Blackhole blackhole) {
        OpenApiResource.RenderedDocument rendered = state.resource.render("yaml", "application/yaml");
        blackhole.consume(rendered.body());
    }

    @Benchmark
    public void smallryeJson(SharedModelState state, Blackhole blackhole) {
        blackhole.consume(serialize(state.smallryeModel, Format.JSON));
    }

    @Benchmark
    public void smallryeYaml(SharedModelState state, Blackhole blackhole) {
        blackhole.consume(serialize(state.smallryeModel, Format.YAML));
    }

    private static String serialize(OpenAPI model, Format format) {
        try {
            return OpenApiSerializer.serialize(model, format);
        } catch (IOException e) {
            throw new IllegalStateException("SmallRye serialization failed", e);
        }
    }
}
