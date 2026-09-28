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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules the page's files keep, read from the resources the console serves: nothing loaded from elsewhere, nothing
 * the console's Content-Security-Policy would block, and no panel text ever parsed as markup.
 */
class PageTest {

    /** Every file of the page. */
    private static final List<String> FILES = List.of("index.html", "console.css", "console.js", "favicon.svg");
    /** The absolute URLs the page may hold, never fetched: the SVG namespace, and the licenses in its headers. */
    private static final List<String> NAMES = List.of("http://www.w3.org/2000/svg",
            "https://www.eclipse.org/legal/epl-2.0/", "https://www.gnu.org/licenses/old-licenses/gpl-2.0.html",
            "https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12");

    private static String file(String name) {
        String path = DevConsoleExtension.PAGE_RESOURCES + "/" + name;
        try (InputStream in = PageTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "the page has no " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void theIndexDeclaresItsCharsetAndLoadsItsOwnFilesOnly() {
        String index = file("index.html");

        assertTrue(index.contains("<meta charset=\"utf-8\">"), "the server adds no charset to text/html");
        assertTrue(index.contains("<link rel=\"stylesheet\" href=\"console.css\">"), index);
        assertTrue(index.contains("<script type=\"module\" src=\"console.js\"></script>"), index);
        assertTrue(index.contains("<link rel=\"icon\" href=\"favicon.svg\" type=\"image/svg+xml\">"), index);
        Matcher references = Pattern.compile("(?:src|href)=\"([^\"]*)\"").matcher(index);
        while (references.find()) {
            assertTrue(FILES.contains(references.group(1)), "a file the page does not have: " + references.group(1));
        }
    }

    @Test
    void theIndexHoldsNothingTheContentSecurityPolicyBlocks() {
        String index = file("index.html");

        assertFalse(Pattern.compile("<script(?![^>]*\\ssrc=)", Pattern.CASE_INSENSITIVE).matcher(index).find(),
                "an inline script: default-src 'self' blocks it");
        assertFalse(Pattern.compile("<style", Pattern.CASE_INSENSITIVE).matcher(index).find(), "an inline style");
        assertFalse(Pattern.compile("\\sstyle=", Pattern.CASE_INSENSITIVE).matcher(index).find(),
                "a style attribute");
        assertFalse(Pattern.compile("\\son[a-z]+=", Pattern.CASE_INSENSITIVE).matcher(index).find(),
                "an event handler attribute");
    }

    @Test
    void noFileLoadsAnythingFromElsewhere() {
        for (String name : FILES) {
            String text = file(name);
            for (String allowed : NAMES) {
                text = text.replace(allowed, "");
            }
            assertFalse(Pattern.compile("https?:|//[a-z0-9.-]+\\.[a-z]{2,}/", Pattern.CASE_INSENSITIVE)
                    .matcher(text).find(), name + " names another site: no CDN, no web font");
            assertFalse(text.contains("@import"), name);
            assertFalse(text.contains("url("), name + " loads a file from its style");
        }
    }

    @Test
    void theScriptNeverParsesTextAsMarkup() {
        String script = file("console.js");

        for (String sink : List.of("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(",
                "new Function", "setAttribute(\"style\"", "srcdoc")) {
            assertFalse(script.contains(sink), "console.js uses " + sink + ": every text goes through textContent");
        }
        assertTrue(script.contains("textContent"), script.length() + " characters and no textContent");
    }

    @Test
    void anActionIsASameOriginJsonPostWithTheTokenConfirmedInline() {
        String script = file("console.js");

        assertTrue(script.contains("method: \"POST\""), "an action is a POST");
        assertTrue(script.contains("\"Content-Type\": \"application/json\""), "a body a cross-site form cannot send");
        assertTrue(script.contains("\"X-Vidocq-Console-Token\": token"), "the token of the boot");
        assertTrue(script.contains("console.actionToken"), "read from the snapshot");
        assertTrue(script.contains("fetch(\"api/action/"), "to the console itself, never another origin");
        assertFalse(Pattern.compile("(?<![\\w.])(confirm|alert|prompt)\\(|window\\.(confirm|alert|prompt)")
                .matcher(script).find(), "a blocking dialog: the confirmation is asked inline");
    }

    @Test
    void theScriptTouchesLocalStorageInsideATryOnly() {
        List<String> uses = file("console.js").lines()
                .filter(line -> !line.strip().startsWith("//") && !line.strip().startsWith("*"))
                .filter(line -> line.contains("localStorage"))
                .toList();

        assertEquals(2, uses.size(), "one read, one write: " + uses);
        for (String use : uses) {
            assertTrue(use.contains("try {") && use.contains("catch"), "a private window may refuse it: " + use);
        }
    }

    @Test
    void theStyleFollowsTheSystemThemeAndUsesSystemFonts() {
        String style = file("console.css");

        assertTrue(style.contains("@media (prefers-color-scheme: dark)"), "a dark theme");
        assertTrue(style.contains("ui-monospace"), "the data in the system's monospace font");
        assertFalse(style.contains("IBM Plex"), "a web font the page would have to load");
    }

    @Test
    void theStyleDefinesEveryJsonTokenInTheLightThemeAndInBothDarkBlocks() {
        String style = file("console.css");
        String light = rule(style, ":root {");
        String system = rule(style, ":root:not([data-theme=\"light\"]) {");
        String forced = rule(style, ":root[data-theme=\"dark\"] {");

        assertTrue(style.indexOf("@media (prefers-color-scheme: dark)")
                < style.indexOf(":root:not([data-theme=\"light\"]) {"), "the system's dark theme is a media query");
        for (String token : List.of("--json-key", "--json-string", "--json-number", "--json-literal",
                "--json-punct")) {
            assertTrue(light.contains(token + ":"), token + " in the light theme");
            assertTrue(system.contains(token + ":"), token + " in the system's dark theme");
            assertTrue(forced.contains(token + ":"), token + " in data-theme=\"dark\"");
        }
    }

    @Test
    void everyJsonThePageShowsGoesThroughTheViewer() {
        String script = file("console.js");

        assertTrue(script.contains("function jsonViewer(text, nodes)"), "the viewer, written once");
        assertFalse(script.contains("prettyJson"), "the viewer replaced prettyJson everywhere");
        assertTrue(script.contains("const JSON_BIG = 500"), "past 500 values, the first level only is open");
        assertTrue(script.contains("event.altKey"), "Alt+click flips a node and everything under it");
        for (String tool : List.of("\"Expand all\"", "\"Collapse all\"", "\"Copy\"", "\"Clipboard refused\"")) {
            assertTrue(script.contains(tool), "the viewer's tools: " + tool);
        }
        assertTrue(script.contains("navigator.clipboard.writeText(viewer.text())"), "Copy goes through the clipboard");
        assertTrue(script.contains("if (!viewer) return { view: el(\"pre\", \"result-body\", text), tools: null };"),
                "a body that does not parse is shown as text");
        assertTrue(script.contains("context.source") && script.contains("JSON.rawJSON"),
                "an integer past 2^53 is shown and copied as the server sent it");
    }

    /** The declarations of the first rule of {@code style} that starts with {@code opening}, to its closing brace. */
    private static String rule(String style, String opening) {
        int start = style.indexOf(opening);
        assertTrue(start >= 0, "no rule " + opening);
        return style.substring(start, style.indexOf('}', start));
    }

    @Test
    void jsonArgumentsGetAFormForAFlatSchemaAndARawEditorOtherwise() {
        String script = file("console.js");

        assertTrue(script.contains("function isFlatSchema(schema)"), "the flat-schema rule, written once");
        assertTrue(script.contains("\"$ref\""), "a $ref is never flat");
        assertTrue(script.contains("function jsonField(argument)"), "a json argument's field");
        assertTrue(script.contains("const REPLAY_COLUMN = \"replay\""), "PanelSample.REPLAY_COLUMN");
        assertTrue(script.contains("const MASKED = \"***\""), "a masked value is not replayed");
        assertTrue(script.contains("const FILTER_FROM = 10"), "a filter past ten actions");
        assertTrue(script.contains("\"Exchange\""), "the details folded under Exchange");
    }

    @Test
    void aReplayColumnCellThatReplaysNoActionOfThePanelStaysText() {
        String script = file("console.js");

        assertTrue(script.contains("function replayButton(panelId, cell)"), "a replay is a button, or null");
        assertTrue(script.contains("tr.append(el(\"td\", /^\\d+$/.test(cell) ? \"n\" : null, cell));"),
                "a cell that is no replay is drawn as any other cell");
        assertTrue(script.contains("i === replayAt && buttons ? \"\" : column"),
                "the header stays when no cell of the column is a replay");
    }

    @Test
    void aJsonPropertyNamedProtoIsAnObjectKeyLikeAnyOther() {
        String script = file("console.js");

        long nullPrototypeObjects = script.lines().filter(line -> line.contains("= Object.create(null);")).count();
        assertEquals(3, nullPrototypeObjects,
                "skeleton(), formObject() and the request body: a bare {} would silently drop a \"__proto__\" key");
        assertTrue(script.contains("Object.hasOwn(properties, name)"), "a schema property read by its own name");
        assertTrue(script.contains("Object.hasOwn(object, property) ? object[property] : undefined"),
                "a form value read by its own name, never an inherited member such as constructor or toString");
    }
}
