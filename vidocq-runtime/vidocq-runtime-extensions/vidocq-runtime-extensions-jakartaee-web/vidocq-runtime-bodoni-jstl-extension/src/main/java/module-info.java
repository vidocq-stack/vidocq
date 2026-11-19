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
/**
 * Jakarta Standard Tag Library 3.0 through Bodoni: the pages that {@code vidocq:generate} compiles with Ibarra
 * use the {@code c:}, {@code fmt:}, {@code fn:}, {@code x:} and {@code sql:} tags of Bodoni. The application
 * module requires {@code io.vidocq.bodoni.core}.
 */
module io.vidocq.runtime.extensions.jakartaee.web.bodoni {
    // The pages are compiled against the tag handlers and the TLDs of Bodoni.
    requires transitive io.vidocq.bodoni.core;
    // The accessor tables Peano needs under peano.reflection=deny (varStatus, Map.Entry, sql:query results).
    requires io.vidocq.bodoni.peano;
    requires io.vidocq.runtime.spi;
    // Ibarra compiles and serves the pages, Peano evaluates their expressions.
    requires transitive io.vidocq.runtime.extensions.jakartaee.web.ibarra;

    provides io.vidocq.runtime.spi.VidocqExtension
            with io.vidocq.runtime.extensions.jakartaee.web.bodoni.BodoniJstlExtension;
}
