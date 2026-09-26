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

import io.vidocq.runtime.spi.ExtensionContext;

import java.util.List;

/**
 * The live half of a section of the startup report, from a {@code -dev} module (Vidocq/vidocq#143): values sampled
 * on every poll, charts and actions, for the section of the same {@link #id()}, which the runtime extension still
 * writes. A live panel writes no section of its own: the section gives the title and the boot facts.
 *
 * <p>Declared as a service by a module that only {@code vidocq:dev} adds, it never reaches a binary. The console
 * {@linkplain #start starts} it the first time it reads the written report, after every extension started, and
 * {@linkplain #stop stops} it with itself: once per boot, a dev reload included.
 *
 * <p>The rules of {@link DevConsolePanel} apply: {@link #sample} reads memory only, never blocks, never creates a
 * bean; a value never carries a secret.
 */
public interface LivePanel {

    /** The id of the startup-report section this panel makes live, such as {@code mansart-pool}. */
    String id();

    /** Called once per boot, before the first sample; the default does nothing. */
    default void start(ExtensionContext context) {}

    /** Called once per boot when the console stops; the default does nothing. */
    default void stop() {}

    /** The charts, in page order; none by default. */
    default List<Chart> charts() {
        return List.of();
    }

    /** Writes the current values, read from memory. */
    void sample(PanelSample sample);

    /** The actions, in page order, offered in a dev launch only; none by default. */
    default List<PanelAction> actions() {
        return List.of();
    }
}
