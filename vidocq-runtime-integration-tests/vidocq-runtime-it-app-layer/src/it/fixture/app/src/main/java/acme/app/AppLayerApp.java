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
package acme.app;

import io.vidocq.runtime.core.Vidocq;

/**
 * A trampoline main, which the fixture names as {@code vidocq.mainClass}: the packaged launchers hand it to the
 * runtime as {@code vidocq.app.main}, and the launches without a layer ({@code vidocq.package.layer=false},
 * {@code vidocq.dev.layer=false}) run it from the module path.
 */
public final class AppLayerApp {

    private AppLayerApp() {}

    public static void main(String[] args) {
        Vidocq.run(args);
    }
}
