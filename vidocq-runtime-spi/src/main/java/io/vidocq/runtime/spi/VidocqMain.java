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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the application's official entry point — a <b>trampoline</b> whose {@code main}
 * contains nothing but {@code Vidocq.run(...)}:
 *
 * <pre>{@code
 * @VidocqMain
 * public final class App {
 *     public static void main(String[] args) {
 *         Vidocq.run(args);
 *     }
 * }
 * }</pre>
 *
 * <p>This is what makes a plain IDE launch (right-click → Run, everything on the module
 * path) work: {@code Vidocq.run()} detects that the application was resolved into the
 * boot layer, re-resolves its archives into a child module layer defined by the Vauban
 * class loader, and boots there — application classes are transformed at definition
 * (client-proxy weaving…), no instrumentation agent, no special launch configuration.
 *
 * <p><b>Contract:</b> put no business logic before {@code Vidocq.run(...)}. The
 * trampoline class is loaded by the IDE's boot layer while the application runs in the
 * Vauban layer — code executed before {@code run()} lives in the wrong class loader and
 * sees untransformed classes. Application logic that must run after boot goes into a
 * {@link VidocqApp} passed to {@code Vidocq.run(Class, String...)}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface VidocqMain {
}
