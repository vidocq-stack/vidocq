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
package io.vidocq.runtime.it.noether;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * The entity of the unit {@code drafts}, RESOURCE_LOCAL on the pool {@code notes}, which the keys of
 * {@code vidocq.properties} declare. Its title is indexed and its code unique, so the dev console's Indexes tab has a
 * declared index of each kind to find in the database.
 */
@Entity
@Table(indexes = @Index(name = "idx_draft_title", columnList = "title"))
public class Draft {

    @Id
    private Long id;

    private String title;

    @Column(unique = true)
    private String code;

    protected Draft() {
    }

    public Draft(Long id, String title, String code) {
        this.id = id;
        this.title = title;
        this.code = code;
    }

    public Long id() {
        return id;
    }

    public String title() {
        return title;
    }
}
