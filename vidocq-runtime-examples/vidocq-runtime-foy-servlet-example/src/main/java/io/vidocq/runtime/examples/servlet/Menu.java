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

import java.util.List;
import java.util.Optional;

/** The dishes of the day, one instance for the whole application. */
@ApplicationScoped
public class Menu {

    private final List<Dish> dishes = List.of(
            new Dish("soup", "French onion soup", 750),
            new Dish("quiche", "Quiche lorraine", 1100),
            new Dish("bourguignon", "Beef bourguignon", 1850),
            new Dish("ratatouille", "Ratatouille", 1400),
            new Dish("tarte", "Tarte Tatin", 800),
            new Dish("brulee", "Crème brûlée", 750));

    public List<Dish> dishes() {
        return dishes;
    }

    public Optional<Dish> find(String id) {
        return dishes.stream().filter(d -> d.id().equals(id)).findFirst();
    }
}
