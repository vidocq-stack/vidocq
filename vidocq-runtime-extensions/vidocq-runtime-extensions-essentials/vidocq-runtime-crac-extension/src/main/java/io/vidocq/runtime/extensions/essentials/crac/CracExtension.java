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
package io.vidocq.runtime.extensions.essentials.crac;


import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqExtension;
import jdk.crac.Context;
import jdk.crac.Core;
import jdk.crac.Resource;

/**
 * Registers a CRaC {@link Resource} that suspends every {@link io.vidocq.runtime.spi.Suspendable} of the
 * boot before a checkpoint and resumes them after a restore.
 *
 * <p>This is the only module that depends on {@code jdk.crac}; the bricks implement the neutral
 * {@code Suspendable}. Priority 20,000: it starts after every other extension, so its suspendables are all known.
 */
public final class CracExtension implements VidocqExtension {

    private static final System.Logger LOG = System.getLogger(CracExtension.class.getName());

    /** The CRaC global context keeps its resources weakly: this field is the strong reference. */
    private Resource resource;

    @Override
    public String name() {
        return "crac";
    }

    @Override
    public int priority() {
        return 20_000;
    }

    @Override
    public void onStart(ExtensionContext context) {
        SuspendableChain chain = new SuspendableChain(context.suspendables());
        this.resource = new Resource() {
            @Override
            public void beforeCheckpoint(Context<? extends Resource> ctx) {
                chain.suspend();
            }

            @Override
            public void afterRestore(Context<? extends Resource> ctx) {
                chain.resume();
            }
        };
        Core.getGlobalContext().register(resource);
        LOG.log(System.Logger.Level.INFO, "CRaC: " + chain.size() + " suspendable(s) registered");
    }

    @Override
    public void onStop() {
        resource = null;
    }
}
