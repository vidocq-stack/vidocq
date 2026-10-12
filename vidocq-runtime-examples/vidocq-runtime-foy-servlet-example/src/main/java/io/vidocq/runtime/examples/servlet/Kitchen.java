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
package io.vidocq.runtime.examples.servlet;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** The orders sent to the kitchen and the number of open sessions, shared by every visitor. */
@ApplicationScoped
public class Kitchen {

    /** An order: its number, time, lines and total. */
    public record Order(int number, LocalTime at, Map<Dish, Integer> lines, int totalCents) {
    }

    private final List<Order> orders = new ArrayList<>();
    private final AtomicInteger nextNumber = new AtomicInteger(1);
    private final AtomicInteger openSessions = new AtomicInteger();

    public synchronized Order place(Basket basket) {
        Order order = new Order(nextNumber.getAndIncrement(), LocalTime.now().truncatedTo(ChronoUnit.SECONDS),
                basket.lines(), basket.totalCents());
        orders.addFirst(order);
        return order;
    }

    public synchronized List<Order> orders() {
        return List.copyOf(orders);
    }

    public synchronized int revenueCents() {
        return orders.stream().mapToInt(Order::totalCents).sum();
    }

    void sessionOpened() {
        openSessions.incrementAndGet();
    }

    void sessionClosed() {
        openSessions.decrementAndGet();
    }

    public int openSessions() {
        return openSessions.get();
    }
}
