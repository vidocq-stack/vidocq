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
package io.vidocq.runtime.spi;

/**
 * Application logic executed after the runtime has booted, <em>inside</em> the Vauban
 * class loader — the safe place for code that a {@link VidocqMain} trampoline must not
 * contain:
 *
 * <pre>{@code
 * @VidocqMain
 * public final class App implements VidocqApp {
 *     public static void main(String[] args) {
 *         Vidocq.run(App.class, args);
 *     }
 *
 *     @Override
 *     public int run(String... args) throws Exception {
 *         // runs after boot, in the Vauban layer, with woven classes
 *         Vidocq.waitForExit();     // block for a server application
 *         return 0;
 *     }
 * }
 * }</pre>
 *
 * <p>The class named in {@code Vidocq.run(Class, args)} is re-loaded through the
 * application layer and instantiated there — as a CDI bean when it is one, through its
 * public no-arg constructor otherwise. When {@link #run} returns, the runtime shuts
 * down and its value becomes the process exit code.
 */
public interface VidocqApp {

    int run(String... args) throws Exception;
}
