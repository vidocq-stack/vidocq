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
 * A pet for sale. The {@code categoryId} is a plain foreign-key column — the {@link Category}
 * relation is resolved in {@code PetService}, not by JPA. Tags are linked through the
 * {@link PetTag} join entity, also assembled in the service layer.
 */
@Entity
@Table(name = "pets")
public class Pet {

    @Id
    @GeneratedValue
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "category_id")
    private Long categoryId;

    /** One of {@code available}, {@code pending}, {@code sold}. */
    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private double price;

    public Pet() {}

    public Pet(String name, Long categoryId, String status, double price) {
        this.name       = name;
        this.categoryId = categoryId;
        this.status     = status;
        this.price      = price;
    }

    public Long   getId()                    { return id;             }
    public void   setId(Long id)             { this.id = id;          }
    public String getName()                  { return name;           }
    public void   setName(String n)          { this.name = n;         }
    public Long   getCategoryId()            { return categoryId;     }
    public void   setCategoryId(Long c)      { this.categoryId = c;   }
    public String getStatus()                { return status;         }
    public void   setStatus(String s)        { this.status = s;       }
    public double getPrice()                 { return price;          }
    public void   setPrice(double p)         { this.price = p;        }
}
