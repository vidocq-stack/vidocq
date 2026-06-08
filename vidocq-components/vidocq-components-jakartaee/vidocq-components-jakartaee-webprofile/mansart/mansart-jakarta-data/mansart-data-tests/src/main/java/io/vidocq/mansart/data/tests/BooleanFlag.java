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

/**
 * Test entity for MANSART-001: covers both a primitive {@code boolean} and a boxed {@link Boolean}
 * field, which must be typed as {@code BooleanAttribute} in the generated metamodel (previously they
 * fell through to {@code NumericAttribute} and failed to compile).
 */
@Entity
public class BooleanFlag {

    @Id @GeneratedValue
    private Long id;

    @Column(nullable = false)
    private boolean active;

    @Column
    private Boolean archived;

    public Long    getId()                  { return id; }
    public void    setId(Long id)           { this.id = id; }
    public boolean isActive()               { return active; }
    public void    setActive(boolean a)     { this.active = a; }
    public Boolean getArchived()            { return archived; }
    public void    setArchived(Boolean a)   { this.archived = a; }
}
