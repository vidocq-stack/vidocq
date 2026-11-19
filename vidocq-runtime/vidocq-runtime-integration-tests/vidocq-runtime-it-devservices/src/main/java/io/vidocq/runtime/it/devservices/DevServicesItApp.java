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
package io.vidocq.runtime.it.devservices;

import io.vidocq.runtime.core.Vidocq;
import io.vidocq.runtime.spi.VidocqMain;

/**
 * The trampoline {@code vidocq:run} and {@code vidocq:dev} launch (DevServicesRunGoalIT): no application
 * datasource of its own, just enough of a boot for the dev services host to attach to and for the startup
 * report's {@code devservices} section to reach the log.
 */
@VidocqMain
public final class DevServicesItApp {

    private DevServicesItApp() {}

    /**
     * Boots Vidocq and blocks until shutdown.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        Vidocq.run(args);
    }
}
