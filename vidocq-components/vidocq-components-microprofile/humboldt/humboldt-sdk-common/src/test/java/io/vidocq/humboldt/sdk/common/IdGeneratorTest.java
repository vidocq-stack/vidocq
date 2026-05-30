package io.vidocq.humboldt.sdk.common;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdGeneratorTest {

    private final IdGenerator gen = IdGenerator.random128();

    @Test
    void traceId_is_32_hex_chars() {
        for (int i = 0; i < 100; i++) {
            String id = gen.generateTraceId();
            assertEquals(32, id.length(), "traceId must be 32 characters: " + id);
            assertTrue(id.matches("[0-9a-f]{32}"), "traceId must be lowercase hex: " + id);
            assertFalse(id.equals("0".repeat(32)), "traceId must not be all-zero");
        }
    }

    @Test
    void spanId_is_16_hex_chars() {
        for (int i = 0; i < 100; i++) {
            String id = gen.generateSpanId();
            assertEquals(16, id.length(), "spanId must be 16 characters: " + id);
            assertTrue(id.matches("[0-9a-f]{16}"), "spanId must be lowercase hex: " + id);
            assertFalse(id.equals("0".repeat(16)), "spanId must not be all-zero");
        }
    }

    @Test
    void traceId_is_unique_over_10k_iterations() {
        Set<String> seen = new HashSet<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            assertTrue(seen.add(gen.generateTraceId()), "traceId collision at iteration " + i);
        }
    }

    @Test
    void spanId_is_unique_over_10k_iterations() {
        Set<String> seen = new HashSet<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            assertTrue(seen.add(gen.generateSpanId()), "spanId collision at iteration " + i);
        }
    }
}
