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
package io.vidocq.mansart.transactions.jpmsit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.TransactionManager;

/**
 * A {@code @Transactional} bean whose {@code $$Intercepted} subclass is generated at BUILD time by
 * the Vauban APT ({@code jakarta.transaction.Transactional} is an {@code @InterceptorBinding}).
 *
 * <p>Used by {@code TxModulePathIT} to prove that {@code @Transactional} interception fires on the
 * module path with no {@code opens} directive and no runtime-generated subclass: the container
 * instantiates the build-time subclass and field-injects {@link #tm} through the generated
 * {@code _VaubanComponents} provider, entirely in-module.</p>
 */
@ApplicationScoped
public class TxService {

    /** Package-private so the generated provider can field-inject it in-module (no {@code opens}). */
    @Inject
    TransactionManager tm;

    @Transactional(Transactional.TxType.REQUIRED)
    public void required(Block body) throws Exception {
        body.run();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void requiresNew(Block body) throws Exception {
        body.run();
    }
}
