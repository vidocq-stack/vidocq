/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
/**
 * Vidocq lifecycle integration for Mansart Jakarta Persistence.
 */
module io.vidocq.runtime.extensions.jakartaee.web.mansart.persistence {
    requires transitive io.vidocq.runtime.spi;
    requires io.vidocq.mansart.jpa.cdi;
    requires io.vidocq.mansart.jpa.core;
    requires io.vidocq.mansart.transactions.cdi;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.persistence;
    requires jakarta.transaction;
    requires io.vidocq.vauban.core;
    requires java.sql;
    requires java.xml;

    provides io.vidocq.runtime.spi.VidocqExtension
            with io.vidocq.runtime.extensions.jakartaee.web.mansart.persistence.MansartPersistenceIntegrationExtension;
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.runtime.extensions.jakartaee.web.mansart.persistence._VaubanComponents;
}
