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

import jakarta.enterprise.context.SessionScoped;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The visitor's basket: one instance per HTTP session, kept by the session context that
 * {@code vauban-webcontexts} registers.
 */
@SessionScoped
public class Basket implements Serializable {

    private final Map<Dish, Integer> lines = new LinkedHashMap<>();

    public synchronized void add(Dish dish) {
        lines.merge(dish, 1, Integer::sum);
    }

    public synchronized void remove(Dish dish) {
        lines.computeIfPresent(dish, (d, n) -> n > 1 ? n - 1 : null);
    }

    public synchronized Map<Dish, Integer> lines() {
        return Map.copyOf(lines);
    }

    public synchronized int count() {
        return lines.values().stream().mapToInt(Integer::intValue).sum();
    }

    public synchronized int totalCents() {
        return lines.entrySet().stream().mapToInt(e -> e.getKey().cents() * e.getValue()).sum();
    }

    public synchronized void clear() {
        lines.clear();
    }
}
