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
 * Runtime engine for Mansart Data — bootstrap, repository runtime, connection scope.
 * Filled in M3.
 */
module io.vidocq.mansart.data.core {
    requires transitive io.vidocq.mansart.data.dialect.spi;
    requires java.sql;
    requires jakarta.data;
    // mansart-data-processor emits @Singleton + @Inject on every *RepositoryImpl, so any
    // downstream user module needs jakarta.inject visible at compile time. Re-exporting
    // it transitively keeps the user module-info minimal.
    requires transitive jakarta.inject;
    // M7-29: JPA mapping annotations are read by name via reflection
    // (RuntimeEntityModelBuilder.hasAnnotation), so jakarta.persistence is not strictly required
    // at runtime. We don't `requires` it here to keep mansart-data-core dep-free.

    exports io.vidocq.mansart.data.core;

    uses io.vidocq.mansart.data.dialect.DialectFactory;
}
