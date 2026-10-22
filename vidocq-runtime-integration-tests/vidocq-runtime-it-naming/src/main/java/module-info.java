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
 * vidocq#297: an application that reaches its {@code "audit"} pool through {@code java:comp/env/jdbc/audit}.
 */
module io.vidocq.runtime.it.naming {
    // The generated repository implementation imports @Generated, a source-retention annotation.
    requires static java.compiler;
    requires java.naming;
    requires java.sql;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.data;
    requires jakarta.persistence;

    requires io.vidocq.runtime.core;
    requires io.vidocq.runtime.extensions.jakartaee.web.naming;
    requires io.vidocq.runtime.extensions.jakartaee.web.mansart.pool;
    requires io.vidocq.runtime.extensions.jakartaee.web.mansart.data;
    requires io.vidocq.mansart.data.core;
    requires io.vidocq.vauban.api;

    // Mansart Data creates the generated AuditJournalImpl through its public constructor, and reads the
    // entity model from AuditEntry's fields.
    opens io.vidocq.runtime.it.naming to io.vidocq.mansart.data.cdi, io.vidocq.mansart.data.core;

    // The test boots the runtime without the application layer that `Vidocq.run` creates, so the module
    // declares the bean provider itself.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.runtime.it.naming._VaubanComponents;
}
