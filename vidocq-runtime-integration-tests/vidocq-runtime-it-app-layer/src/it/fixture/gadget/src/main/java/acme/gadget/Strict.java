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
package acme.gadget;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A library class whose only constructor refuses the default values a client proxy would pass it: a proxy that runs
 * it fails to be created, and the application does not start (BUG-20261118-91).
 */
public class Strict {

    private static final AtomicInteger CONSTRUCTED = new AtomicInteger();

    private final String name;

    public Strict(String name) {
        this.name = Objects.requireNonNull(name, "name");
        CONSTRUCTED.incrementAndGet();
    }

    public String name() {
        return name;
    }

    /** Package-private: only a proxy of this package can override it, so the proxy is placed. */
    String label(String suffix) {
        return name + ":" + suffix;
    }

    /** How many times the constructor ran. */
    public static int constructed() {
        return CONSTRUCTED.get();
    }
}
