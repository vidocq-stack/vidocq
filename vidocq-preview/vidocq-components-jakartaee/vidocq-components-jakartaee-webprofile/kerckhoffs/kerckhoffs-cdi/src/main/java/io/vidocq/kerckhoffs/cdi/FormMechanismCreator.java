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
package io.vidocq.kerckhoffs.cdi;

import io.vidocq.kerckhoffs.core.internal.mechanism.FormAuthenticationMechanism;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import jakarta.security.enterprise.identitystore.IdentityStoreHandler;

import java.util.function.Supplier;

/**
 * Creates the FORM mechanism of a {@code @FormAuthenticationMechanismDefinition}, or the custom FORM one of a
 * {@code @CustomFormAuthenticationMechanismDefinition}, whose application's login page shows the failures itself.
 */
public class FormMechanismCreator implements SyntheticBeanCreator<HttpAuthenticationMechanism> {

    @Override
    public HttpAuthenticationMechanism create(Instance<Object> lookup, Parameters params) {
        String loginPage = params.get("loginPage", String.class);
        boolean forwardToLogin = params.get("forwardToLogin", Boolean.class);
        Supplier<IdentityStoreHandler> handler = () -> lookup.select(IdentityStoreHandler.class).get();
        if (params.get("custom", Boolean.class, false)) {
            return FormAuthenticationMechanism.customForm(loginPage, forwardToLogin, handler);
        }
        return new FormAuthenticationMechanism(loginPage, params.get("errorPage", String.class), forwardToLogin, handler);
    }
}
