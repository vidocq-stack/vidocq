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
    private static final List<String> FILES = List.of("index.html", "console.css", "console.js", "favicon.svg",
            "editor-core.js", "editor.js");
    /** The page's scripts: console.js, which the index loads, and the modules it imports. */
    private static final List<String> SCRIPTS = FILES.stream().filter(name -> name.endsWith(".js")).toList();
    /** An import statement of a module, and the module it names. */
    private static final Pattern IMPORT = Pattern.compile("(?m)^import .* from \"([^\"]+)\";$");
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
        for (String name : SCRIPTS) {
            String script = file(name);
            for (String sink : List.of("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(",
                    "new Function", "setAttribute(\"style\"", "srcdoc", "import(")) {
                assertFalse(script.contains(sink), name + " uses " + sink + ": every text goes through textContent");
            }
        }
        String script = file("console.js");
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
        for (String name : SCRIPTS) {
            List<String> uses = file(name).lines()
                    .filter(line -> !line.strip().startsWith("//") && !line.strip().startsWith("*"))
                    .filter(line -> line.contains("localStorage"))
                    .toList();

            assertEquals(name.equals("console.js") ? 2 : 0, uses.size(),
                    "one read, one write, in console.js only: " + name + " " + uses);
            for (String use : uses) {
                assertTrue(use.contains("try {") && use.contains("catch"), "a private window may refuse it: " + use);
            }
        }
    }

    @Test
    void theEditorCoreTouchesNothingOfThePageSoThatGraalJsRunsIt() {
        String code = file("editor-core.js").replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");

        for (String global : List.of("document", "window", "navigator", "requestAnimationFrame", "localStorage",
                "globalThis")) {
            assertFalse(Pattern.compile("\\b" + global + "\\b").matcher(code).find(), "editor-core.js uses " + global);
        }
        assertEquals(List.of(), IMPORT.matcher(code).results().map(m -> m.group(1)).toList(), "it imports nothing");
        assertTrue(code.contains("export const jsonLanguage = Object.freeze({"), "the JSON language");
    }

    @Test
    void theEditorImportsTheCoreAndDrawsItsTextWithTextContent() {
        String editor = file("editor.js");

        assertEquals(List.of("./editor-core.js"), IMPORT.matcher(editor).results().map(m -> m.group(1)).toList());
        assertTrue(editor.contains("export function createEditor({ language, data, value, rows, label })"), "spec §4");
        assertTrue(editor.contains("span.textContent = text.slice(part.from, part.to);"), "the <pre>'s text, as text");
        assertTrue(editor.contains("if (frame === 0) frame = requestAnimationFrame("), "one draw per frame at most");
        assertTrue(editor.contains("const LIMIT = 100_000;"), "past 100 000 characters, a plain textarea");
        assertTrue(editor.contains("diagnostics = safely(() => language.diagnose(text, data), []);"),
                "a schema the language chokes on never breaks the page");
        assertTrue(editor.contains("document.execCommand(\"insertText\", false, insert)"), "Ctrl+Z undoes an edit");
        assertTrue(editor.contains("textarea.setRangeText(insert, from, to, \"end\");"),
                "where the browser refuses execCommand");
        assertTrue(editor.contains("event.key === \" \" && event.ctrlKey"), "Ctrl+Space opens the completion list");
        assertTrue(editor.contains("event.code === \"KeyF\" && event.shiftKey && event.altKey"), "Shift+Alt+F formats");
        assertTrue(editor.contains("if (event.isComposing || MODIFIERS.has(event.key)) return;"),
                "Shift pressed before Tab does not cancel the Escape of Escape then Shift+Tab");
        assertTrue(editor.contains("if (tab && leaving) return;"), "Escape then Tab leaves the editor");
        assertTrue(editor.contains("popup.style.left = Math.max(0, Math.min(point.left, box.clientWidth - width))"),
                "the completion list never past the editor's right edge");
        assertTrue(editor.contains("const up = frameTop + below + height > window.innerHeight && frameTop + above >= 0;"),
                "over the caret when the window has no room below");
        assertTrue(editor.contains("document.elementsFromPoint(x, y)"), "the tooltip of what the pointer is on");
        assertTrue(editor.contains("el(\"button\", \"ed-format\", \"Format\")"), "the Format button");
    }

    @Test
    void theEditorColoursItsTokensWithTheViewersColoursInEveryTheme() {
        String style = file("console.css");

        for (String kind : List.of("key", "string", "number", "literal", "punct")) {
            assertTrue(rule(style, ".ed-" + kind + " {").contains("var(--json-" + kind + ")"), "the viewer's " + kind);
        }
        assertTrue(rule(style, ".ed-invalid {").contains("var(--crit)"), "an invalid run");
        assertTrue(rule(style, ".ed-error {").contains("var(--crit)"), "an error, a red wavy underline");
        assertTrue(rule(style, ".ed-warning {").contains("var(--warn)"), "a warning, an orange one");
        for (String opening : List.of(":root {", ":root:not([data-theme=\"light\"]) {",
                ":root[data-theme=\"dark\"] {")) {
            String theme = rule(style, opening);
            assertTrue(theme.contains("--crit:") && theme.contains("--warn:"), "both colours in " + opening);
        }
        assertTrue(rule(style, ".ed-text {").contains("color: transparent"), "the textarea shows the <pre>'s text");
        assertTrue(rule(style, ".ed-pre, .ed-text, .ed-mirror {").contains("white-space: pre-wrap"),
                "the <pre>, the textarea and the caret's mirror wrap alike");
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

    @Test
    void anActionPublishesEveryOutcomeToItsOutletWithTheWordingOfToday() {
        String script = file("console.js");

        assertTrue(script.contains("function actionRow(panelId, action, outlet)"), "where results show is the outlet's");
        assertTrue(script.contains("function inlineOutlet(actionId)"), "the panel's own bar keeps its look");
        assertTrue(script.contains("const started = performance.now();"), "the round trip the page measures");
        for (String wording : List.of("\"No token: reload the page.\"", "\"the console did not answer\"",
                "\"another action of this panel is running\"", "\"still running after 60 s: the outcome will show here\"",
                "\"failed: \" + answer.error", "\"refused (\" + response.status + \")\"")) {
            assertTrue(script.contains(wording), "the wording the page uses today: " + wording);
        }
    }

    @Test
    void aGroupTabPicksOneActionAndShowsItsResultApartThenItsHistory() {
        String script = file("console.js");

        assertTrue(script.contains("function groupTab(panelId, name, rows, state, open)"), "a tab per group");
        assertTrue(script.contains("el(\"select\", \"action-select\")"), "a native combo of the group's actions");
        assertTrue(script.contains("rows.length > FILTER_FROM"), "a filter past ten actions of the group");
        assertTrue(script.contains("formSlot.replaceChildren(byId.get(id).root)"),
                "the selected action's own form, kept while it is typed in");
        assertTrue(script.contains("byId.has(state.chosen.get(name))"),
                "a selected action a dev reload removed falls back to the group's first");
        assertTrue(script.contains("function resultBlock(result, actionId)"), "the result, apart from the form");
        assertTrue(script.contains("\"No call yet\""), "before the first call");
        assertTrue(script.contains("function sampleTable(value, panelId, keep)"), "a table filtered to a group");
        assertTrue(script.contains("(id) => groupOf.get(id) === name"), "the rows of this group's actions only");
        assertTrue(script.contains("if (keep && !kept) return el(\"p\", \"absent\", \"No call yet\");"),
                "a history with no row of the group says so");
    }

    @Test
    void aPanelWhoseActionsHaveGroupsGetsSubTabsMonitoringFirst() {
        String script = file("console.js");

        assertTrue(script.contains("const MONITORING = \"\""), "Monitoring's id, which no group has");
        assertTrue(script.contains("[[MONITORING, \"Monitoring\"], ...[...byGroup.keys()].map((g) => [g, g])]"),
                "Monitoring first, then one tab per group in order of first appearance");
        assertTrue(script.contains("strip.setAttribute(\"role\", \"tablist\")"), "the markup of the top tabs");
        assertTrue(script.contains("const box = byGroup.size ? el(\"div\", \"subpanel\") : panelArea;"),
                "a panel without a grouped action renders as before");
        assertTrue(script.contains("body.hidden = tabId !== id"), "a sub-tab is shown, never drawn again");
        assertTrue(script.contains("v.columns.includes(REPLAY_COLUMN)"), "the replay tables move to the group tabs");
        assertTrue(script.contains("store(SUBTAB_KEY + panel.id, id)"), "the open sub-tab is remembered");
        assertTrue(script.contains("if (!byGroup.has(state.tab)) state.tab = MONITORING;"),
                "a group a dev reload removed falls back to Monitoring");
        assertTrue(script.contains("state.results.delete(id)"), "the result of a removed action is forgotten");
        assertFalse(script.contains("action-group"), "grouped actions are no longer folded sections");
    }

    @Test
    void aServerOutcomeKeepsTheBodyOfThisPagesOwnCallOnlyAndADevReloadKeepsTheResults() {
        String script = file("console.js");

        assertTrue(script.contains("publish(answer.error === true ? \"error\" : \"ok\", answer.result, { millis, answer, mine: true });"),
                "the answer of this page's own call is marked as such");
        assertTrue(script.contains("const own = current && current.mine && current.state !== \"running\" ? current : null;"),
                "a newer server outcome carries the body over only from this page's own call, once");
        assertTrue(script.contains("if (actions.length) for (const id of [...state.results.keys()])"),
                "a snapshot taken while a dev reload has no action yet forgets no result");
    }

    @Test
    void aPolledTableKeepsItsScrollingBoxSoItsHorizontalScrollSurvives() {
        String script = file("console.js");

        assertTrue(script.contains("function redrawTable(holder, next)"), "one way to redraw a polled table");
        assertTrue(script.contains("box.replaceChildren(...next.childNodes);"),
                "the new table goes into the scrolling box already on the page, which a drag keeps holding");
        assertTrue(script.contains("box.scrollLeft = left;"), "the horizontal scroll is put back");
        assertTrue(script.contains("redrawTable(holder, value.kind === \"table\""), "a panel's tables");
        assertTrue(script.contains("redrawTable(historyHolders.get(key), value.kind === \"table\""),
                "a group tab's history");
        assertFalse(script.contains("holder.replaceChildren(value.kind"), "no table redrawn with a new scrolling box");
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
    void aStringPropertyOfFormatTextareaIsAMultiLineFieldOfTheForm() {
        String script = file("console.js");
        String style = file("console.css");

        assertTrue(script.contains("} else if (kind === \"string\" && definition.format === \"textarea\") {"),
                "a string property of \"format\": \"textarea\", and only such a property, gets a textarea");
        assertTrue(script.contains("input = el(\"textarea\", \"json-text\");"), "a textarea of its own class");
        assertTrue(script.contains("input.rows = 4;"), "four lines to start with");
        assertTrue(script.contains("wrap.classList.add(\"wide\");"), "on a line of its own");
        String textarea = rule(style, ".action textarea.json-text {");
        assertTrue(textarea.contains("var(--mono)"), "monospace, as the JSON editor");
        assertTrue(textarea.contains("resize: vertical"), "taller when dragged");
        assertTrue(rule(style, ".action .arg.wide {").contains("flex-basis: 100%"), "the whole width of the form");
    }

    @Test
    void aCsvResultIsTextWithADownloadThatSendsNoRequest() {
        String script = file("console.js");

        assertTrue(script.contains(
                "const isCsvType = (type) => typeof type === \"string\" && type.startsWith(\"text/csv\");"),
                "PanelAction.ActionResult.CSV");
        assertTrue(script.contains("return { view: el(\"pre\", \"result-body\", answer.body), "
                + "tools: downloadTools(answer.body, actionId) };"), "shown as text, with Download");
        assertTrue(script.contains("new Blob([text], { type: \"text/csv;charset=utf-8\" })"),
                "saved as the browser holds it, in UTF-8");
        assertTrue(script.contains("String(actionId).replace(/[^A-Za-z0-9._-]/g, \"-\")"),
                "the action id, kept to the characters of a file name");
        assertTrue(script.contains("+ \"-\" + stamp + \".csv\""), "<action id>-<yyyyMMdd-HHmmss>.csv");
        assertTrue(script.contains("URL.revokeObjectURL(url)"), "the Blob is released");
        String download = script.substring(script.indexOf("function downloadTools("),
                script.indexOf("function answerBody("));
        assertFalse(download.contains("fetch("), "a download sends no request");
        assertEquals(2, Pattern.compile(Pattern.quote("answerBody(answer, result.nodes.body, actionId)"))
                .matcher(script).results().count(), "a group tab's result block and the panel's own bar");
    }

    @Test
    void aCsvTextareaGetsAChooseFileThatReadsALocalFileOfAtMost60KiB() {
        String script = file("console.js");
        String style = file("console.css");

        assertTrue(script.contains("if (definition.contentMediaType === \"text/csv\") chooser = fileChooser(input);"),
                "a textarea whose schema says text/csv, and only such a one");
        assertTrue(script.contains("file.type = \"file\";") && script.contains("file.accept = \".csv,text/csv\";"),
                "a native file input for CSV files");
        assertTrue(script.contains("const MAX_FILE_BYTES = 60 * 1024;"), "under the console's 64 KiB request");
        assertTrue(script.contains("\"the file is larger than 60 KiB\""), "said under the field");
        assertTrue(script.contains("read(\"UTF-8\");"), "read in the browser, as UTF-8 first");
        assertTrue(script.contains("target.value = raw;"), "the file replaces the textarea's value");
        String chooser = script.substring(script.indexOf("function fileChooser("),
                script.indexOf("function jsonField("));
        assertFalse(chooser.contains("fetch("), "nothing is sent until the form is");
        assertTrue(script.contains("...choosers]) c.disabled = on;"), "disabled while the form is sent");
        assertTrue(rule(style, ".action .file-note {").contains("var(--crit)"), "the refusal in the error colour");
    }

    @Test
    void aChosenFileThatIsNotUtf8IsReadAgainAsWindows1252AndSaidSo() {
        String chooser = chooser(file("console.js"));

        assertTrue(chooser.contains("if (encoding === \"UTF-8\" && text.includes(\"\\uFFFD\")) {"),
                "a replacement character: the bytes were not UTF-8");
        assertTrue(chooser.contains("read(\"windows-1252\");"), "as a spreadsheet of a decimal-comma locale saves CSV");
        assertTrue(chooser.contains("\"read as Windows-1252 (not UTF-8)\""), "said under the input, not refused");
    }

    @Test
    void theSameFileMayBeChosenAgainAndALateReadIsIgnored() {
        String chooser = chooser(file("console.js"));

        assertTrue(chooser.contains("file.value = \"\";"), "cleared after each read: choosing it again fires change");
        assertTrue(chooser.contains("const token = ++reads;"), "each read has its token");
        assertTrue(chooser.contains("if (token !== reads) return;"), "a read overtaken by a newer one is dropped");
        assertTrue(chooser.contains("if (file.disabled) {"), "a read ending while the form is sent is dropped");
        assertTrue(chooser.contains("\"the form was sent before the file was read: choose it again\""),
                "and said so");
    }

    @Test
    void anUneditedFileIsSentAsReadItsLineEndsKept() {
        String script = file("console.js");
        String chooser = chooser(script);

        assertTrue(chooser.contains("text: () => raw !== null && target.value === raw.replace(/\\r\\n?/g, \"\\n\") ? raw"
                + " : target.value,"), "the file's own text while the textarea still shows it, else the textarea");
        assertTrue(script.contains("inputs.set(property, { input, kind, text: chooser ? chooser.text : null });"),
                "the form keeps it");
        assertTrue(script.contains("object[property] = fileText ? fileText() : input.value;"), "and sends it");
    }

    private static String chooser(String script) {
        return script.substring(script.indexOf("function fileChooser("), script.indexOf("function jsonField("));
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
