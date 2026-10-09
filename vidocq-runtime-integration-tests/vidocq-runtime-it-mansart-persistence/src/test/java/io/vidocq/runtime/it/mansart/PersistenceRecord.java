/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.it.mansart;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class PersistenceRecord {
    @Id
    private String id;

    protected PersistenceRecord() {
    }

    PersistenceRecord(String id) {
        this.id = id;
    }
}
