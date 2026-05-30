package io.vidocq.humboldt.sdk.trace;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;
import io.vidocq.humboldt.sdk.trace.samplers.SamplingResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamplerTest {

    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";

    @Test
    void alwaysOn_records_and_samples() {
        SamplingResult r = Sampler.alwaysOn().shouldSample(
                Context.root(), TRACE_ID, "test", SpanKind.INTERNAL,
                Attributes.empty(), List.of());
        assertEquals(SamplingResult.Decision.RECORD_AND_SAMPLE, r.decision());
    }

    @Test
    void alwaysOff_drops() {
        SamplingResult r = Sampler.alwaysOff().shouldSample(
                Context.root(), TRACE_ID, "test", SpanKind.INTERNAL,
                Attributes.empty(), List.of());
        assertEquals(SamplingResult.Decision.DROP, r.decision());
    }

    @Test
    void traceIdRatio_zero_drops_all() {
        Sampler s = Sampler.traceIdRatioBased(0.0);
        for (int i = 0; i < 100; i++) {
            String tid = String.format("%032x", (long) i);
            assertEquals(SamplingResult.Decision.DROP,
                    s.shouldSample(Context.root(), tid, "x", SpanKind.INTERNAL,
                            Attributes.empty(), List.of()).decision());
        }
    }

    @Test
    void traceIdRatio_one_samples_all() {
        Sampler s = Sampler.traceIdRatioBased(1.0);
        for (int i = 0; i < 100; i++) {
            String tid = String.format("%032x", (long) i);
            assertEquals(SamplingResult.Decision.RECORD_AND_SAMPLE,
                    s.shouldSample(Context.root(), tid, "x", SpanKind.INTERNAL,
                            Attributes.empty(), List.of()).decision());
        }
    }

    @Test
    void traceIdRatio_rejects_invalid() {
        assertThrows(IllegalArgumentException.class, () -> Sampler.traceIdRatioBased(-0.1));
        assertThrows(IllegalArgumentException.class, () -> Sampler.traceIdRatioBased(1.1));
    }

    @Test
    void traceIdRatio_consistent_for_same_trace_id() {
        Sampler s = Sampler.traceIdRatioBased(0.5);
        SamplingResult r1 = s.shouldSample(Context.root(), TRACE_ID, "a", SpanKind.INTERNAL,
                Attributes.empty(), List.of());
        SamplingResult r2 = s.shouldSample(Context.root(), TRACE_ID, "b", SpanKind.INTERNAL,
                Attributes.empty(), List.of());
        assertEquals(r1.decision(), r2.decision(),
                "same traceId must yield the same decision (per-trace consistency)");
    }

    @Test
    void parentBased_delegates_to_root_when_no_parent() {
        Sampler s = Sampler.parentBased(Sampler.alwaysOn());
        assertEquals(SamplingResult.Decision.RECORD_AND_SAMPLE,
                s.shouldSample(Context.root(), TRACE_ID, "x", SpanKind.INTERNAL,
                        Attributes.empty(), List.of()).decision());
    }

    @Test
    void descriptions_are_distinct() {
        assertNotEquals(Sampler.alwaysOn().description(), Sampler.alwaysOff().description());
        assertTrue(Sampler.traceIdRatioBased(0.42).description().contains("0.42"));
        assertTrue(Sampler.parentBased(Sampler.alwaysOff()).description().contains("AlwaysOff"));
    }
}
