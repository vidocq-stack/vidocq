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
            assertEquals(32, id.length(), "traceId doit faire 32 caractères : " + id);
            assertTrue(id.matches("[0-9a-f]{32}"), "traceId doit être hex lowercase : " + id);
            assertFalse(id.equals("0".repeat(32)), "traceId ne doit pas être tout-zéro");
        }
    }

    @Test
    void spanId_is_16_hex_chars() {
        for (int i = 0; i < 100; i++) {
            String id = gen.generateSpanId();
            assertEquals(16, id.length(), "spanId doit faire 16 caractères : " + id);
            assertTrue(id.matches("[0-9a-f]{16}"), "spanId doit être hex lowercase : " + id);
            assertFalse(id.equals("0".repeat(16)), "spanId ne doit pas être tout-zéro");
        }
    }

    @Test
    void traceId_is_unique_over_10k_iterations() {
        Set<String> seen = new HashSet<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            assertTrue(seen.add(gen.generateTraceId()), "collision traceId à l'itération " + i);
        }
    }

    @Test
    void spanId_is_unique_over_10k_iterations() {
        Set<String> seen = new HashSet<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            assertTrue(seen.add(gen.generateSpanId()), "collision spanId à l'itération " + i);
        }
    }
}
