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
package io.vidocq.runtime.examples.mansart;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An audit row stored in the <b>named</b> {@code "audit"} datasource — a different H2 database than
 * the {@link Product} table. Persisted through {@link AuditEntryRepository}, whose
 * {@code @Repository(dataStore = "audit")} routes it to the generated {@code @Named("audit")}
 * DataSource.
 */
@Entity
@Table(name = "audit_log")
public class AuditEntry {

    @Id
    @GeneratedValue
    private Long id;

    @Column(nullable = false)
    private String action;

    public AuditEntry() {}

    public AuditEntry(String action) {
        this.action = action;
    }

    public Long   getId()              { return id;        }
    public void   setId(Long id)       { this.id = id;     }
    public String getAction()          { return action;    }
    public void   setAction(String a)  { this.action = a;  }
}
