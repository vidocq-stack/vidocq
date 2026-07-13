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
 * Humboldt Context — provides the OpenTelemetry {@code ContextStorageProvider}
 * implementation for the Humboldt runtime.
 *
 * <p>Discovered by OTel via {@link java.util.ServiceLoader} (Java Modules
 * {@code provides} binding + {@code META-INF/services} fallback for
 * classpath environments).</p>
 *
 * <p>The implementation relies on a {@link ThreadLocal}; since JDK 21,
 * {@code ThreadLocal} no longer causes carrier-thread pinning for virtual
 * threads in pure Java code (see JEP 444). The ADR for a possible migration
 * to {@code ScopedValue} (JEP 506) is postponed
 * to M8 (see PLAN.md §7 and §15.1).</p>
 */
module io.vidocq.humboldt.context {

    requires transitive io.opentelemetry.context;
    requires java.logging;

    exports io.vidocq.humboldt.context;

    provides io.opentelemetry.context.ContextStorageProvider
            with io.vidocq.humboldt.context.HumboldtContextStorageProvider;
}
