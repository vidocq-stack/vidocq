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
package io.vidocq.runtime.examples.petstore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Join entity materialising the many-to-many link between {@link Pet} and {@link Tag}.
 * Mansart Data has no association mapping, so the link is a first-class flat row managed
 * explicitly by {@code PetService} (delete-then-reinsert on update).
 */
@Entity
@Table(name = "pet_tags")
public class PetTag {

    @Id
    @GeneratedValue
    private Long id;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    @Column(name = "tag_id", nullable = false)
    private Long tagId;

    public PetTag() {}

    public PetTag(Long petId, Long tagId) {
        this.petId = petId;
        this.tagId = tagId;
    }

    public Long getId()              { return id;        }
    public void setId(Long id)       { this.id = id;     }
    public Long getPetId()           { return petId;     }
    public void setPetId(Long p)     { this.petId = p;   }
    public Long getTagId()           { return tagId;     }
    public void setTagId(Long t)     { this.tagId = t;   }
}
