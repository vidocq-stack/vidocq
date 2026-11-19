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
package io.vidocq.vauban.moduleit.foreign;

import io.vidocq.vauban.moduleit.ReceiverLabeled;

/**
 * Binds {@link ReceiverLabeled} to the package-private {@link HiddenArgument}, with no shadow: a
 * bean in another package inherits the default {@code label(HiddenArgument)} as its member, a
 * signature its rendered client proxy cannot write (BUG-20261004-02). The default names the class
 * of the instance it runs on, which tells a forwarded call from one that is not.
 */
public class HiddenDefaultBase implements ReceiverLabeled<HiddenArgument> {

    /** {@code bean.label(…)} as this package writes it, with an argument only it can make. */
    public static String callLabel(HiddenDefaultBase bean) {
        return bean.label(new HiddenArgument());
    }
}
