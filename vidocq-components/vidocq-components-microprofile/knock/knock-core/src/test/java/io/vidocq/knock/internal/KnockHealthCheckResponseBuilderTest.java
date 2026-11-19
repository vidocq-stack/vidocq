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
package io.vidocq.knock.internal;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD — {@link KnockHealthCheckResponseBuilder}.
 *
 * <p>Spec MicroProfile Health 4.0 §3 : « The response to a health check invocation consists
 * of a status UP or DOWN and an optional map of key/value pairs, including a name. »</p>
 */
class KnockHealthCheckResponseBuilderTest {

    // -----------------------------------------------------------------------
    // §3 — statut UP
    // -----------------------------------------------------------------------

    @Test
    void up_spec_section3_status() {
        // Spec §3 : named("x").up().build() → status UP
        HealthCheckResponse response = new KnockHealthCheckResponseBuilder()
                .name("my-check")
                .up()
                .build();

        assertEquals("my-check", response.getName());
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    }

    // -----------------------------------------------------------------------
    // §3 — statut DOWN
    // -----------------------------------------------------------------------

    @Test
    void down_spec_section3_status() {
        // Spec §3 : named("x").down().build() → status DOWN
        HealthCheckResponse response = new KnockHealthCheckResponseBuilder()
                .name("failing-check")
                .down()
                .build();

        assertEquals("failing-check", response.getName());
        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
    }

    // -----------------------------------------------------------------------
    // §3 — status(boolean)
    // -----------------------------------------------------------------------

    @Test
    void status_boolean_true_spec_section3() {
        HealthCheckResponse response = new KnockHealthCheckResponseBuilder()
                .name("bool-check")
                .status(true)
                .build();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    }

    @Test
    void status_boolean_false_spec_section3() {
        HealthCheckResponse response = new KnockHealthCheckResponseBuilder()
                .name("bool-check")
                .status(false)
                .build();
        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
    }

    // -----------------------------------------------------------------------
    // §3.1 — data present (string, long, boolean)
    // -----------------------------------------------------------------------

    @Test
    void withData_string_spec_section3_1() {
        // Spec §3.1: String key/value data pairs are serializable
        HealthCheckResponse response = new KnockHealthCheckResponseBuilder()
                .name("db")
                .up()
                .withData("latency", "12ms")
                .build();

        Optional<Map<String, Object>> data = response.getData();
        assertTrue(data.isPresent(), "data must be present");
        assertEquals("12ms", data.get().get("latency"));
    }

    @Test
    void withData_long_spec_section3_1() {
        HealthCheckResponse response = new KnockHealthCheckResponseBuilder()
                .name("db")
                .up()
                .withData("connections", 42L)
                .build();

        Optional<Map<String, Object>> data = response.getData();
        assertTrue(data.isPresent());
        assertEquals(42L, data.get().get("connections"));
    }

    @Test
    void withData_boolean_spec_section3_1() {
        HealthCheckResponse response = new KnockHealthCheckResponseBuilder()
                .name("db")
                .up()
                .withData("pooled", true)
                .build();

        Optional<Map<String, Object>> data = response.getData();
        assertTrue(data.isPresent());
        assertEquals(true, data.get().get("pooled"));
    }

    @Test
    void withData_multiple_entries_spec_section3_1() {
        HealthCheckResponse response = new KnockHealthCheckResponseBuilder()
                .name("multi")
                .up()
                .withData("key1", "val1")
                .withData("key2", 99L)
                .build();

        Optional<Map<String, Object>> data = response.getData();
        assertTrue(data.isPresent());
        assertEquals("val1", data.get().get("key1"));
        assertEquals(99L, data.get().get("key2"));
    }

    // -----------------------------------------------------------------------
    // §3.1 — data absent when not provided
    // -----------------------------------------------------------------------

    @Test
    void data_absent_when_not_provided_spec_section3_1() {
        // Spec §3.1: "data is omitted if empty"
        HealthCheckResponse response = new KnockHealthCheckResponseBuilder()
                .name("simple")
                .up()
                .build();

        // data must be Optional.empty() if no withData was provided
        assertTrue(response.getData().isEmpty(), "data must be absent when not provided");
    }

    // -----------------------------------------------------------------------
    // §3.1 — withData overrides the existing value for the same key
    // -----------------------------------------------------------------------

    @Test
    void withData_overrides_existing_key_spec_section3_1() {
        // Spec §3.1: "Additional invocations with the same key override the key-value pair."
        HealthCheckResponse response = new KnockHealthCheckResponseBuilder()
                .name("override")
                .up()
                .withData("key", "first")
                .withData("key", "second")
                .build();

        Map<String, Object> data = response.getData().orElseThrow();
        assertEquals("second", data.get("key"));
    }

    // -----------------------------------------------------------------------
    // §3.1 — data keeps the order of the withData calls (BUG-20261114-01)
    // -----------------------------------------------------------------------

    /**
     * The data map keeps the order of the {@code withData} calls, so that {@code /health} renders the
     * same document at every start. {@code Map.copyOf} iterated in an order salted per JVM
     * (BUG-20261114-01). Twenty keys, in neither alphabetical nor reverse order, leave no room for
     * an accidental match.
     */
    @Test
    void withData_keeps_the_order_of_the_calls_spec_section3_1() {
        List<String> keys = shuffledKeys();
        KnockHealthCheckResponseBuilder builder = new KnockHealthCheckResponseBuilder();
        builder.name("heap").up();
        for (int i = 0; i < keys.size(); i++) {
            builder.withData(keys.get(i), i);
        }

        Map<String, Object> data = builder.build().getData().orElseThrow();

        assertEquals(keys, List.copyOf(data.keySet()));
    }

    @Test
    void build_still_rejects_a_null_data_key_or_value() {
        HealthCheckResponseBuilder nullValue = new KnockHealthCheckResponseBuilder()
                .name("nulls").up().withData("key", (String) null);
        HealthCheckResponseBuilder nullKey = new KnockHealthCheckResponseBuilder()
                .name("nulls").up().withData(null, "value");

        assertThrows(NullPointerException.class, nullValue::build);
        assertThrows(NullPointerException.class, nullKey::build);
    }

    /** {@code key-0} to {@code key-19}, stepping by 7 modulo 20: neither sorted nor reverse-sorted. */
    static List<String> shuffledKeys() {
        return IntStream.range(0, 20).mapToObj(i -> "key-" + (i * 7 % 20)).toList();
    }

    // -----------------------------------------------------------------------
    // Integration through HealthCheckResponse.named() (ServiceLoader SPI)
    // -----------------------------------------------------------------------

    @Test
    void healthCheckResponse_named_uses_knock_provider() {
        // Validates that the ServiceLoader finds KnockHealthCheckResponseProvider
        HealthCheckResponse response = HealthCheckResponse.named("spi-check").up().build();
        assertEquals("spi-check", response.getName());
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    }

    @Test
    void healthCheckResponse_up_convenience_method() {
        HealthCheckResponse response = HealthCheckResponse.up("quick-check");
        assertEquals("quick-check", response.getName());
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    }

    @Test
    void healthCheckResponse_down_convenience_method() {
        HealthCheckResponse response = HealthCheckResponse.down("fail-check");
        assertEquals("fail-check", response.getName());
        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
    }
}
