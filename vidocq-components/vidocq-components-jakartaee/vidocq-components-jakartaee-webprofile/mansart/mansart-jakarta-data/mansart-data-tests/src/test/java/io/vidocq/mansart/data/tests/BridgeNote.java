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
package io.vidocq.mansart.data.tests;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Test entity for the JTA transaction-bridge tests (MANSART-007) — routed to the
 * {@code "bridgeds"} named datasource. Distinct entity/table/dataStore from the
 * MANSART-005 fixtures so {@code DataStoreResolver}'s static cache never leaks
 * state between test containers.
 */
@Entity
@Table(name = "bridge_notes")
public class BridgeNote {

    @Id @GeneratedValue
    private Long id;

    @Column(nullable = false, length = 200)
    private String text;

    public Long getId()           { return id; }
    public void setId(Long id)    { this.id = id; }
    public String getText()       { return text; }
    public void setText(String t) { this.text = t; }
}
