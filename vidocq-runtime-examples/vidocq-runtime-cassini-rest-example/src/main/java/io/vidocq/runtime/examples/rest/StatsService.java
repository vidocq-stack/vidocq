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
package io.vidocq.runtime.examples.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Constructor-injection showcase (Vidocq/vauban#24): this normal-scoped bean declares
 * <strong>only</strong> an {@code @Inject} constructor and uses its parameter right away —
 * no no-arg constructor, no {@code ProxyLink} anywhere in this source.
 *
 * <p>It works because the {@code (ProxyLink)} client-proxy entry constructor is woven
 * into the compiled class at build time (auto-started javac plugin shipped with the
 * Vauban annotation processor, belt-and-braces in the vauban-maven-plugin): the generated
 * proxy chains to that synthetic constructor, so creating it runs none of the code below,
 * while the contextual instance still receives a real {@link TodoService}.
 */
@ApplicationScoped
public class StatsService {

    private final int todosAtStartup;

    @Inject
    public StatsService(TodoService todos) {
        this.todosAtStartup = todos.list().size();
    }

    public String report() {
        return "todos-at-startup=" + todosAtStartup;
    }
}
