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

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.json.bind.adapter.JsonbAdapter;

/**
 * A JSON-B adapter that is a CDI bean (JSON-B 3.0 §4.7.1): Champollion obtains it from the container, so its
 * {@link TitleStyle} is injected.
 */
@Dependent
public class TitleAdapter implements JsonbAdapter<String, String> {

    private final TitleStyle style;

    @Inject
    public TitleAdapter(TitleStyle style) {
        this.style = style;
    }

    @Override
    public String adaptToJson(String title) {
        return style.write(title);
    }

    @Override
    public String adaptFromJson(String json) {
        return style.read(json);
    }
}
