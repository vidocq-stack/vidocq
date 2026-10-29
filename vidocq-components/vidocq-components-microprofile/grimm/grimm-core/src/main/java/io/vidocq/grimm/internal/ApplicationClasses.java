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
package io.vidocq.grimm.internal;

/**
 * Loads a class the application names in its configuration (a model reader, a filter, a schema type). Grimm's own
 * loader does not see the application's classes when Grimm is provided by the server or sits in a parent module
 * layer, so the thread context class loader, the application's, is asked first.
 */
public final class ApplicationClasses {

    private ApplicationClasses() {
    }

    /**
     * The class {@code name}, without initializing it: through the thread context class loader, then Grimm's own.
     *
     * @throws ClassNotFoundException when neither loader sees it
     */
    public static Class<?> load(String name) throws ClassNotFoundException {
        ClassLoader application = Thread.currentThread().getContextClassLoader();
        if (application != null) {
            try {
                return Class.forName(name, false, application);
            } catch (ClassNotFoundException _) {
                // not the application's: Grimm's own loader may still see it
            }
        }
        return Class.forName(name, false, ApplicationClasses.class.getClassLoader());
    }
}
