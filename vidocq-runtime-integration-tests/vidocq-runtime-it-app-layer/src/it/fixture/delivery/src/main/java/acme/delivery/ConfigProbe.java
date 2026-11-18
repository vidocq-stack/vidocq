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
package acme.delivery;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.concurrent.CompletableFuture;

/**
 * Reads the configuration of the application from a thread of the common pool, as {@code CompletableFuture} and
 * parallel streams do. Under the application layer that thread's context class loader is the JVM's, and misses the
 * application (BUG-20261118-93, open); in an image every module is the JVM's.
 */
@ApplicationScoped
public class ConfigProbe {

    void onStart(@Observes Startup startup) {
        System.out.println("CFG-ASYNC " + CompletableFuture.supplyAsync(() -> ConfigProvider.getConfig()
                .getOptionalValue("acme.key", String.class).orElse("MISSING")).join());
    }
}
