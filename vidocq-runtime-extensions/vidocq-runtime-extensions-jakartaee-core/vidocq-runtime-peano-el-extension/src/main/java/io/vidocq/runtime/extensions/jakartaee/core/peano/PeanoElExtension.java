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

package io.vidocq.runtime.extensions.jakartaee.core.peano;

import jakarta.el.ExpressionFactory;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportSection;

/**
 * Activates Peano's standard Jakarta EL provider through the module graph.
 * Provider selection stays with the Jakarta API; explicit application overrides are respected.
 * The startup report reads a lifecycle snapshot and does not discover providers while rendering.
 */
public final class PeanoElExtension implements VidocqExtension, StartupReportContributor {
    private volatile String providerModule;

    /** Created by the runtime's service loader. */
    public PeanoElExtension() {}

    @Override public String name() { return "peano-el"; }
    @Override public String id() { return "el"; }
    @Override public String title() { return "Expression Language (Peano)"; }

    @Override public void onStart(ExtensionContext context) {
        var factory=ExpressionFactory.newInstance();
        providerModule=factory.getClass().getModule().getName();
    }
    @Override public void onStop() { providerModule=null; }
    @Override public void contribute(StartupReportContext context,StartupReportSection section) {
        String selected=providerModule;
        section.summary("Jakarta EL 6.0").row("provider module",selected==null ? "not initialized" : selected)
                .row("reflection policy",System.getProperty("peano.reflection","allow"));
    }
}
