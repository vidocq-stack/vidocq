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
package io.vidocq.runtime.spi.devconsole;

import java.util.Objects;

/**
 * A language a {@link DevConsolePanel} offers the dev console's code editor, such as the JDQL its query actions take:
 * a dialect and a vocabulary, as the text of one JSON object. Checked when it is built, so that a mistake fails where
 * it is written rather than on the page.
 *
 * <p><b>How the page uses it.</b> A string property of a {@link PanelAction.Argument#json json} argument's schema
 * with {@code "format": "textarea"}, {@code "contentMediaType": "text/x-query"} and {@code "x-language": "<id>"} is
 * a query editor: the page fetches the language once, {@code GET /api/language/<panel>/<id>}, in a
 * {@link io.vidocq.runtime.spi.report.LaunchMode#DEV dev} launch only, and colours, completes and checks the query
 * with it. The server stays the judge of the grammar. The snapshot names the ids a panel offers, never the content.
 *
 * <p><b>Its JSON.</b> For the {@code query} mode of the page's editor:
 * <pre>{@code
 * { "mode": "query",
 *   "dialect": { "keywords": ["SELECT", "FROM", "WHERE", ...], "functions": ["UPPER", "LOWER", ...],
 *                "clauses": ["SELECT", "FROM", "WHERE", "ORDER BY", "SET", "UPDATE", "DELETE FROM"],
 *                "targetAfter": ["FROM", "UPDATE"], "self": "this", "quote": "'" },
 *   "targets": { "Task": { "detail": "table task",
 *                          "attributes": { "title": { "type": "string", "detail": "String · column title" },
 *                                          "project": { "type": "integer", "target": "Project",
 *                                                       "detail": "→ Project · column project_id" } } } } }
 * }</pre>
 * {@code keywords} are matched ignoring case and written in capitals; {@code functions} are the names that may be
 * followed by {@code (}; {@code clauses} start a clause; {@code targetAfter} are the words after which a target is
 * named; {@code self} is the target's own name in an expression; {@code quote} delimits a string, doubled inside it.
 * An attribute has a JSON Schema {@code type} ({@code string}, {@code integer}, {@code number}, {@code boolean}),
 * optionally {@code format} and {@code enum}, a {@code detail}, and {@code target} when it refers to another target of
 * the same language. A word the dialect leaves out takes JDQL's.
 *
 * @param id   identifies the language among those of its panel, stable across boots; it follows the rule of
 *             {@link PanelSample#requireKey}, such as {@code jdql}
 * @param json the language, the text of one JSON object of at most {@value #MAX_JSON} characters, nested
 *             {@value PanelAction.Argument#MAX_JSON_DEPTH} levels deep at most
 */
public record PanelLanguage(String id, String json) {

    /** The longest language, in characters: 1 MiB. */
    public static final int MAX_JSON = 1024 * 1024;

    public PanelLanguage {
        PanelSample.requireKey(id);
        Objects.requireNonNull(json, "json");
        if (json.length() > MAX_JSON || !JsonCheck.isObject(json, PanelAction.Argument.MAX_JSON_DEPTH)) {
            throw new IllegalArgumentException("language '" + id + "' is not a JSON object of at most " + MAX_JSON
                    + " characters and " + PanelAction.Argument.MAX_JSON_DEPTH + " levels");
        }
    }
}
