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
package io.vidocq.runtime.examples.rest.model;

import jakarta.json.bind.annotation.JsonbCreator;
import jakarta.json.bind.annotation.JsonbProperty;

public record Todo(long id, String title, boolean done) {

    /**
     * Annotated factory {@link JsonbCreator} for JSON-B deserialization.
     * <p>Without this, Yasson 3.0.4 in strict module-path mode (jlink/jpackage)
     * fails to invoke the canonical constructor of the record and falls into
     * silent defaults ({@code title=null}). Explicit marking forces
     * Yasson to go through this factory, which is resolved by the names of
     * parameters ({@code @JsonbProperty}).</p>
     */
    @JsonbCreator
    public static Todo create(@JsonbProperty("id") long id,
                              @JsonbProperty("title") String title,
                              @JsonbProperty("done") boolean done) {
        return new Todo(id, title, done);
    }

    public Todo withUpdate(String newTitle, boolean newDone) {
        return new Todo(this.id, newTitle, newDone);
    }
}
