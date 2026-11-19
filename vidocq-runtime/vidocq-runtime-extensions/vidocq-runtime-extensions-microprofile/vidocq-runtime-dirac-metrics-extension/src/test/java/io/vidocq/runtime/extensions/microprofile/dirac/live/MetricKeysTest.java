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
package io.vidocq.runtime.extensions.microprofile.dirac.live;

import io.vidocq.runtime.spi.devconsole.PanelSample;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The key rule of the {@code metrics} panel: a metric's name and tags, made a valid, unique key. */
class MetricKeysTest {

    @Test
    void aNameIsLowercasedAndItsInvalidCharactersBecomeHyphens() {
        assertEquals("memory.usedheap", MetricKeys.normalize(new MetricID("memory.usedHeap")));
        assertEquals("orders-placed-total", MetricKeys.normalize(new MetricID("orders_placed total")));
        assertEquals("com.acme.shop.checkout", MetricKeys.normalize(new MetricID("com.acme.Shop.checkout")));
    }

    @Test
    void tagsFollowTheNameInTheOrderOfTheirNames() {
        MetricID id = new MetricID("orders", new Tag("shop", "EU"), new Tag("channel", "web"));

        assertEquals("orders.channel-web.shop-eu", MetricKeys.normalize(id));
    }

    @Test
    void aNameThatDoesNotStartWithALetterGetsAPrefix() {
        assertEquals("m-2xx.responses", MetricKeys.normalize(new MetricID("2xx.responses")));
        assertEquals("m--private", MetricKeys.normalize(new MetricID("_private")));
    }

    @Test
    void aLongNameIsCutToFortyCharacters() {
        MetricKeys keys = new MetricKeys(Set.of());
        String key = keys.allocate(new MetricID("io.vidocq.examples.shop.CheckoutService.placeOrder"));

        assertEquals("io.vidocq.examples.shop.checkoutservice.", key);
        assertEquals(40, key.length());
        PanelSample.requireKey(key);
    }

    @Test
    void aSuffixedKeyLeavesRoomForItsSuffix() {
        MetricKeys keys = new MetricKeys(Set.of());
        String key = keys.allocate(new MetricID("io.vidocq.examples.shop.CheckoutService.placeOrder"), ".mean");

        assertEquals("io.vidocq.examples.shop.checkoutser", key);
        PanelSample.requireKey(key + ".mean");
    }

    @Test
    void twoMetricsWithTheSameKeyAreToldApartByANumber() {
        MetricKeys keys = new MetricKeys(Set.of());

        assertEquals("hits", keys.allocate(new MetricID("hits")));
        assertEquals("hits-2", keys.allocate(new MetricID("HITS")));
        assertEquals("hits-3", keys.allocate(new MetricID("Hits")));
    }

    @Test
    void theNumberStaysWithinFortyCharacters() {
        MetricKeys keys = new MetricKeys(Set.of());
        String name = "a".repeat(50);

        assertEquals("a".repeat(40), keys.allocate(new MetricID(name)));
        String second = keys.allocate(new MetricID(name.toUpperCase()));
        assertEquals("a".repeat(38) + "-2", second);
        PanelSample.requireKey(second);
    }

    @Test
    void aReservedKeyOrASuffixedKeyAlreadyTakenIsNotReused() {
        MetricKeys keys = new MetricKeys(Set.of("counters"));

        assertEquals("counters-2", keys.allocate(new MetricID("counters")));
        assertEquals("checkout", keys.allocate(new MetricID("checkout"), ".mean"));
        // "checkout.mean" is taken by the timer above: a counter of that name gets the next number.
        assertEquals("checkout.mean-2", keys.allocate(new MetricID("checkout.mean")));
    }

    @Test
    void everyKeyFollowsTheConsoleRule() {
        MetricKeys keys = new MetricKeys(Set.of());
        for (String name : new String[] {"", "é", "Ünïcode.metric", "--", "9", "a b/c:d", "x".repeat(100)}) {
            String key = keys.allocate(new MetricID(name.isEmpty() ? " " : name), ".mean");
            assertTrue(key.length() <= 35, key);
            PanelSample.requireKey(key);
            PanelSample.requireKey(key + ".mean");
        }
    }
}
