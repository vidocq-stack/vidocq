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
package io.vidocq.runtime.spi.devconsole;

import java.util.Objects;

/**
 * The one choice a panel offers above its sub-tabs, such as the persistence unit every tab works on. The choices are
 * the names of the {@linkplain PanelSample#group groups} the panel writes in its sample, in their order: the page
 * draws a dropdown of them, shows the selected group alone in the <i>Monitoring</i> tab, and, for every action that
 * declares the argument {@link #argument()}, leaves that field out of the form and sends the selected name in its
 * place. The action checks it as it checks any argument: the console only carries it.
 *
 * <p>A panel without a group, or whose sample failed, shows no dropdown, and its forms keep the field.
 *
 * @param argument the name of the action argument the choice fills, by the rule of {@link PanelSample#requireKey},
 *                 such as {@code unit}
 * @param label    what the page writes next to the dropdown, such as {@code Persistence unit}; never blank
 */
public record PanelSelector(String argument, String label) {

    public PanelSelector {
        PanelSample.requireKey(argument);
        Objects.requireNonNull(label, "label");
        if (label.isBlank()) {
            throw new IllegalArgumentException("the selector of argument '" + argument + "' has a blank label");
        }
    }
}
