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
package acme.placed;

import acme.gadget.Gadget;
import acme.gadget.Gadgets;
import acme.gadget.Strict;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.inject.Inject;

/** Prints what the client proxy of the third-party class answers, and where its class lives. */
@ApplicationScoped
public class PlacedProbe {

    @Inject
    Gadget gadget;

    @Inject
    Strict strict;

    void onStart(@Observes Startup startup) {
        System.out.println("GADGET-NAME " + gadget.name());
        // The library calls its package-private member on the proxy: only a proxy of the library's package
        // overrides it, and so answers with the name of the produced instance.
        System.out.println("GADGET-LABEL " + Gadgets.labelOf(gadget, "x"));
        // One gadget was produced. A second run of a constructor is the client proxy's own.
        System.out.println("GADGET-CONSTRUCTED " + Gadget.constructed());
        // The proxy of a class whose constructor refuses null was created, so it ran no constructor of the class.
        System.out.println("STRICT-NAME " + strict.name() + " constructed " + Strict.constructed());
        Class<?> proxy = gadget.getClass();
        System.out.println("GADGET-PROXY " + proxy.getName() + " in " + proxy.getModule().getName());
    }
}
