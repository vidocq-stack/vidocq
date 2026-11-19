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
package io.vidocq.runtime.core;

import io.vidocq.runtime.dev.agent.HotSwap;
import io.vidocq.runtime.dev.agent.RedefinitionRefused;

import java.util.Collection;
import java.util.function.Function;

/**
 * {@link ClassRedefinition.Redefiner} through {@link HotSwap}. Loaded only once {@link ClassRedefinition#agent} found
 * the agent module in the boot layer: it is the one class of the core that names the agent's types.
 */
final class AgentRedefiner implements ClassRedefinition.Redefiner {

    /** {@link HotSwap#redefine}, or a stand-in in tests. */
    @FunctionalInterface
    interface Call {
        HotSwap.Outcome redefine(ClassLoader loader, Collection<String> names, Function<String, byte[]> bytes)
                throws RedefinitionRefused;
    }

    private final Call call;

    AgentRedefiner() {
        this(HotSwap::redefine);
    }

    AgentRedefiner(Call call) {
        this.call = call;
    }

    static boolean installed() {
        return HotSwap.installed();
    }

    @Override
    public ClassRedefinition.Result redefine(ClassLoader loader, Collection<String> names,
            Function<String, byte[]> bytes) {
        try {
            HotSwap.Outcome outcome = call.redefine(loader, names, bytes);
            return new ClassRedefinition.Done(outcome.redefined(), outcome.notLoaded());
        } catch (RedefinitionRefused e) {
            return new ClassRedefinition.Refused(e.getMessage());
        }
    }
}
