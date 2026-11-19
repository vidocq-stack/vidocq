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
package io.vidocq.kerckhoffs.authorization;

import jakarta.security.jacc.Policy;
import jakarta.security.jacc.PolicyContext;
import jakarta.security.jacc.PolicyContextException;
import jakarta.security.jacc.PolicyFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The {@link PolicyFactory} of Kerckhoffs: one policy for the runtime, {@link ConstraintPolicy} until an application
 * sets another, and a policy per context identifier for an application that registers its own.
 */
public final class ConstraintPolicyFactory extends PolicyFactory {

    private final Map<String, Policy> perContext = new ConcurrentHashMap<>();
    private final AtomicReference<Policy> policy = new AtomicReference<>(new ConstraintPolicy());

    /**
     * Makes this factory the one of the runtime unless the platform already has one, and registers the handler that
     * answers the {@code PrincipalMapper} of the policy context.
     *
     * @return the factory in service
     */
    public static synchronized PolicyFactory install() {
        try {
            PolicyContext.registerHandler(PolicyContext.PRINCIPAL_MAPPER, new PrincipalMapperHandler(), true);
        } catch (PolicyContextException e) {
            throw new IllegalStateException("the principal mapper could not be registered", e);
        }
        PolicyFactory current = PolicyFactory.getPolicyFactory();
        if (current != null) {
            return current;
        }
        PolicyFactory ours = new ConstraintPolicyFactory();
        PolicyFactory.setPolicyFactory(ours);
        return ours;
    }

    @Override
    public Policy getPolicy() {
        return policy.get();
    }

    @Override
    public void setPolicy(Policy policy) {
        this.policy.set(policy);
    }

    @Override
    public Policy getPolicy(String contextId) {
        return perContext.getOrDefault(contextId, policy.get());
    }

    @Override
    public void setPolicy(String contextId, Policy policy) {
        perContext.put(contextId, policy);
    }
}
