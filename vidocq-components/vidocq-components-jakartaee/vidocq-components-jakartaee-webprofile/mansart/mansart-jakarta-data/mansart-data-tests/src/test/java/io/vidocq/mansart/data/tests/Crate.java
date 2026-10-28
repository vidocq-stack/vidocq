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

/**
 * What {@link JdqlEnumLiteralTest} runs statements on: a text attribute, an attribute of a top-level enum and one of
 * an enum nested in the entity. Not an {@code @Entity}, so that Mansart builds its model at run time; the table is
 * created by the test.
 */
@Table(name = "jdql_crates")
public class Crate {

    /** How a crate is handled, nested so that its binary name ({@code Crate$Kind}) differs from its canonical one. */
    public enum Kind { STANDARD, FRAGILE }

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "label")
    private String label;

    @Column(name = "status")
    private ShipmentStatus status;

    @Column(name = "kind")
    private Kind kind;

    public Crate() {
        // Mansart instantiates the entity before it sets its attributes.
    }

    public Long getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public ShipmentStatus getStatus() {
        return status;
    }

    public Kind getKind() {
        return kind;
    }
}
