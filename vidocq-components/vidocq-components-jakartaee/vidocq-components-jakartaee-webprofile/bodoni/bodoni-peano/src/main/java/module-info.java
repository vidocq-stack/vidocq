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
 * Bodoni :: Peano — optional engine adapter between Bodoni and Peano.
 *
 * <p>Under {@code -Dpeano.reflection=deny} Peano reads bean properties only through
 * accessor tables. {@code LoopTagStatus}, the {@code varStatus} object of Tags 3.0
 * section 6.1, is implemented by an anonymous class of the JSTL API that no application
 * table can name, so this module contributes a table keyed on the interface. Nothing is
 * exported: Peano discovers the provider through the service declaration.
 */
module io.vidocq.bodoni.peano {
    requires jakarta.servlet.jsp.jstl;
    requires io.vidocq.peano.api;

    provides io.vidocq.peano.spi.AccessorProvider with io.vidocq.bodoni.peano.internal.BodoniAccessorProvider;
}
