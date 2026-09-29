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
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * What {@link JdqlRunTest} runs statements on: one attribute of each type a statement given as text converts a value
 * to. Not an {@code @Entity}, so that the processor generates no metamodel and Mansart builds its model at run time,
 * as it does for a tool's entity; the table is created by the test.
 */
@Table(name = "jdql_shipments")
public class Shipment {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "reference")
    private String reference;

    @Column(name = "status")
    private ShipmentStatus status;

    @Column(name = "shipped_on")
    private LocalDate shippedOn;

    @Column(name = "logged_at")
    private Instant loggedAt;

    @Column(name = "weight")
    private BigDecimal weight;

    @Column(name = "parcels")
    private Integer parcels;

    @Column(name = "fragile")
    private Boolean fragile;

    public Shipment() {}

    public Long getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public ShipmentStatus getStatus() {
        return status;
    }

    public LocalDate getShippedOn() {
        return shippedOn;
    }

    public Instant getLoggedAt() {
        return loggedAt;
    }

    public BigDecimal getWeight() {
        return weight;
    }

    public Integer getParcels() {
        return parcels;
    }

    public Boolean getFragile() {
        return fragile;
    }
}
