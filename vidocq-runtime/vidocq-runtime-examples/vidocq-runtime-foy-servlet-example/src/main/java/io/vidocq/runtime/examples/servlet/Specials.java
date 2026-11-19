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
package io.vidocq.runtime.examples.servlet;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Named;

/**
 * Tonight's special, a {@code @Named} request-scoped bean that {@code specials.jsp} reads by name: {@code ${specials.dish}}.
 * No servlet puts it in a request attribute; the page reaches it through CDI's bean names, and Peano reads it through
 * the accessor table it generates for a {@code @Named} bean, served for the bean's client proxy.
 */
@Named("specials")
@RequestScoped
public class Specials {

    /** @return the dish of the evening */
    public String getDish() {
        return "beef bourguignon";
    }

    /** @return its price, in cents */
    public int getPriceCents() {
        return 1850;
    }
}
