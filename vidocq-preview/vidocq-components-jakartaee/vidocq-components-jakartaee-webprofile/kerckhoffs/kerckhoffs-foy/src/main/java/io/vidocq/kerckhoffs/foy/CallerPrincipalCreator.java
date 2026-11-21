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
package io.vidocq.kerckhoffs.foy;

import io.vidocq.foy.spi.security.CurrentRequest;
import io.vidocq.kerckhoffs.core.internal.context.CurrentCallerPrincipal;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.servlet.http.HttpServletRequest;

import java.security.Principal;

/** Creates the built-in {@code Principal} of an application served by Foy: the caller of the request being served. */
public class CallerPrincipalCreator implements SyntheticBeanCreator<Principal> {

    @Override
    public Principal create(Instance<Object> lookup, Parameters params) {
        return new CurrentCallerPrincipal(() -> CurrentRequest.get().map(HttpServletRequest::getUserPrincipal));
    }
}
