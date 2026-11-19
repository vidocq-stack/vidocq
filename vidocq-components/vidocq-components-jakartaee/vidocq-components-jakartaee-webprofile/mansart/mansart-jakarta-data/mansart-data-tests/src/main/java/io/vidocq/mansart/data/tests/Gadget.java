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

import java.time.LocalDate;

/** Fixture entity with primitive, nullable and temporal attributes, for {@link GadgetRepository}. */
@Entity
public class Gadget {

    @Id @GeneratedValue
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column
    private String category;

    @Column
    private double price;

    @Column
    private int stock;

    @Column
    private boolean active;

    @Column
    private LocalDate releasedOn;

    public Gadget() {
    }

    public Gadget(String name, String category, double price, int stock, boolean active, LocalDate releasedOn) {
        this.name = name;
        this.category = category;
        this.price = price;
        this.stock = stock;
        this.active = active;
        this.releasedOn = releasedOn;
    }

    public Long getId()                      { return id; }
    public void setId(Long id)               { this.id = id; }
    public String getName()                  { return name; }
    public void setName(String name)         { this.name = name; }
    public String getCategory()              { return category; }
    public void setCategory(String category) { this.category = category; }
    public double getPrice()                 { return price; }
    public void setPrice(double price)       { this.price = price; }
    public int getStock()                    { return stock; }
    public void setStock(int stock)          { this.stock = stock; }
    public boolean isActive()                { return active; }
    public void setActive(boolean active)    { this.active = active; }
    public LocalDate getReleasedOn()         { return releasedOn; }
    public void setReleasedOn(LocalDate d)   { this.releasedOn = d; }
}
