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

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

/**
 * One enum column per storage (MANSART-003): {@code @Enumerated(ORDINAL)} stores the index,
 * {@code @Enumerated(STRING)} the name, and an enum with no {@code @Enumerated} the name too — Mansart's
 * documented default, where JPA would store the index.
 */
@Entity
public class EnumTicket {

    @Id @GeneratedValue
    private Long id;

    @Enumerated(EnumType.ORDINAL)
    private Priority rank;

    @Enumerated(EnumType.STRING)
    private Priority label;

    private Priority plain;

    public Long     getId()                 { return id; }
    public void     setId(Long id)          { this.id = id; }
    public Priority getRank()               { return rank; }
    public void     setRank(Priority p)     { this.rank = p; }
    public Priority getLabel()              { return label; }
    public void     setLabel(Priority p)    { this.label = p; }
    public Priority getPlain()              { return plain; }
    public void     setPlain(Priority p)    { this.plain = p; }
}
