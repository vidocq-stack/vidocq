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

import java.time.Instant;

/**
 * Test entity for MANSART-002: an {@link Instant} field mapped to a {@code TIMESTAMPTZ} column.
 * The PostgreSQL driver rejects {@code setObject(Instant, TIMESTAMP_WITH_TIMEZONE)}, so the dialect
 * must bind an {@code OffsetDateTime}; this entity exercises that write/read round-trip.
 */
@Entity
public class Event {

    @Id @GeneratedValue
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public Long getId()                       { return id; }
    public void setId(Long id)                { this.id = id; }
    public Instant getOccurredAt()            { return occurredAt; }
    public void setOccurredAt(Instant when)   { this.occurredAt = when; }
}
