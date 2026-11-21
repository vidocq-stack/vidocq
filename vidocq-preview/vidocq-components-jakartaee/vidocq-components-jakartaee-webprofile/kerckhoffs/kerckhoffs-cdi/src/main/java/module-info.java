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
/** CDI integration of Jakarta Security 4.0: the identity store handler and the stores declared by annotation. */
module io.vidocq.kerckhoffs.cdi {
    requires io.vidocq.kerckhoffs.core;
    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static jakarta.interceptor;
    requires static jakarta.annotation;

    // The container instantiates the creators and the start check of this package.
    exports io.vidocq.kerckhoffs.cdi;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.kerckhoffs.cdi.KerckhoffsExtension;
}
