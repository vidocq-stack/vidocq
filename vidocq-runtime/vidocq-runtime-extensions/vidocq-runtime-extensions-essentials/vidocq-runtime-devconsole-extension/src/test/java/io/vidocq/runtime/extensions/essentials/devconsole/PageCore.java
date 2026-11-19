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
package io.vidocq.runtime.extensions.essentials.devconsole;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The page's pure half, {@code editor-core.js}, run by GraalJS as the ES module the page loads, for the tests of what
 * it computes. One engine per test class: open it in {@code @BeforeAll}, close it in {@code @AfterAll}.
 */
final class PageCore implements AutoCloseable {

    private final Context context;
    private final Value exports;
    private final Value json;

    PageCore() {
        context = Context.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .option("js.esm-eval-returns-exports", "true")
                .build();
        exports = context.eval(Source.newBuilder("js", resource("editor-core.js"), "editor-core.mjs")
                .mimeType("application/javascript+module").buildLiteral());
        json = context.eval("js", "JSON");
    }

    /** The value {@code text} parses to, as JSON. */
    Value parse(String text) {
        return json.invokeMember("parse", text);
    }

    /** What the module's {@code function} returns for these arguments, a Java string becoming a JS string. */
    Value run(String function, Object... arguments) {
        return exports.getMember(function).execute(arguments);
    }

    /** {@code value} as JSON, or {@code "null"}. */
    String json(Value value) {
        return value.isNull() ? "null" : json.invokeMember("stringify", value).asString();
    }

    /** What the function {@code source}, a JS expression, returns for these arguments. */
    Value eval(String source, Object... arguments) {
        return context.eval("js", source).execute(arguments);
    }

    @Override
    public void close() {
        context.close();
    }

    private static String resource(String name) {
        String path = DevConsoleExtension.PAGE_RESOURCES + "/" + name;
        try (InputStream in = PageCore.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("the page has no " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
