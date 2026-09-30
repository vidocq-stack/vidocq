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

// The dev console's code editor, its page half: a textarea over a <pre> that shows the same text coloured, with line
// numbers, diagnostics, a completion list, the matching bracket and a Format button. What the text means is
// editor-core.js's; this module draws it and turns keys into edits.
//
// - The <pre> is built from spans whose text is set with textContent, never parsed as markup. It draws the line
//   numbers too, in the padding it shares with the textarea, so that a wrapped line keeps its number without measuring.
// - Tokens, colours and diagnostics are computed again in one requestAnimationFrame after an input, never twice in a
//   frame, and only the lines that changed are drawn again. Past LIMIT characters the editor stops colouring and
//   checking and shows the textarea's own text; completion and formatting still work when asked for.
// - Every edit it makes goes through document.execCommand("insertText"), which keeps Ctrl+Z; where the browser
//   refuses it, setRangeText and an input event.

import { jsonLanguage, keystroke, isShortcut, FORMAT_EXAMPLES } from "./editor-core.js";

export { jsonLanguage, FORMAT_EXAMPLES };

/** Past this many characters, no colours and no diagnostics: the textarea alone. */
const LIMIT = 100_000;
/** The keys, besides the characters of a language's pairs, that editor-core.js may turn into an edit. */
const KEYS = new Set(["Enter", "Backspace", "Tab", "Shift+Tab"]);
/** The brackets whose match is outlined, by opening character. */
const BRACKETS = new Map([["{", "}"], ["[", "]"], ["(", ")"]]);
/** Keys pressed alone before another: they neither end the Escape of Escape-then-Tab nor make an edit. */
const MODIFIERS = new Set(["Shift", "Control", "Alt", "Meta", "CapsLock"]);

let editors = 0;

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined && text !== null) e.textContent = String(text);
  return e;
}

const plural = (n, one, many) => n + " " + (n === 1 ? one : many);
const clamp = (offset, length) => Math.max(0, Math.min(length, Math.trunc(Number(offset)) || 0));

/** {@code compute}'s value, or {@code fallback} when it throws: a language never breaks the page. */
function safely(compute, fallback) {
  try {
    return compute();
  } catch (failed) {
    return fallback;
  }
}

/**
 * An editor of {@code language} (spec §4), {@code data} its data (for JSON, the argument's schema), starting with
 * {@code value}, {@code rows} lines high; {@code label} names it for a screen reader.
 *
 * @returns {{root: HTMLElement, value(): string, setValue(text: string): void, disable(on: boolean): void,
 *   focus(): void, textarea: HTMLTextAreaElement}}
 */
export function createEditor({ language, data, value, rows, label }) {
  const id = "ed-" + ++editors;
  const root = el("div", "ed");
  const box = el("div", "ed-box");
  const pre = el("pre", "ed-pre");
  pre.setAttribute("aria-hidden", "true");
  const textarea = el("textarea", "ed-text");
  textarea.rows = rows || 8;
  textarea.spellcheck = false;
  textarea.setAttribute("autocomplete", "off");
  textarea.setAttribute("autocapitalize", "off");
  textarea.setAttribute("aria-autocomplete", "list");
  textarea.setAttribute("aria-controls", id + "-list");
  textarea.setAttribute("aria-expanded", "false");
  if (label) textarea.setAttribute("aria-label", label);
  textarea.value = typeof value === "string" ? value : "";
  const list = el("div", "ed-list");
  list.id = id + "-list";
  list.setAttribute("role", "listbox");
  list.hidden = true;
  const tip = el("div", "ed-tip");
  tip.id = id + "-tip";
  tip.setAttribute("role", "tooltip");
  tip.hidden = true;
  const mirror = el("div", "ed-mirror");
  mirror.setAttribute("aria-hidden", "true");
  box.append(pre, textarea, list, tip, mirror);
  const foot = el("div", "ed-foot");
  const note = el("span", "ed-note");
  note.setAttribute("aria-live", "polite");
  const format = el("button", "ed-format", "Format");
  format.type = "button";
  format.title = "Format (Shift+Alt+F)";
  foot.append(note, format);
  root.append(box, foot);

  let frame = 0;                // the pending animation frame of a draw, 0 when none
  let hoverFrame = 0;           // the pending animation frame of a pointer move, 0 when none
  let tokens = [];
  let diagnostics = [];
  let matches = new Map();      // bracket offset -> offset of its match
  let shown = [];               // the lines drawn: { key, from, parts: [{ from, to }], node }
  let outlined = [];            // the spans of the matching brackets
  let formatNote = "";          // why Format refused, until the next edit
  let escaped = false;          // Escape was the last key: the next Tab leaves the editor
  let hovering = false;         // the tooltip shows what the pointer is on
  let completion = null;        // while the list is open: { from, to, items, active }

  // -------------------------------------------------------------------------------------------- drawing

  function schedule() {
    if (frame === 0) frame = requestAnimationFrame(() => { frame = 0; draw(); });
  }

  function draw() {
    const text = textarea.value;
    const plain = text.length > LIMIT;
    root.classList.toggle("ed-plain", plain);
    if (plain) {
      tokens = [];
      diagnostics = [];
      matches = new Map();
      if (shown.length) pre.replaceChildren();
      shown = [];
    } else {
      tokens = safely(() => language.tokenize(text), []);
      diagnostics = safely(() => language.diagnose(text, data), []);
      matches = bracketMatches(text);
      paint(text);
    }
    root.style.setProperty("--ed-digits", String(Math.max(2, String(Math.max(1, shown.length)).length)));
    say();
    follow();
    outline();
    tipAtCaret();
  }

  /** The note under the editor: how many errors and warnings, the size limit, why Format refused. */
  function say() {
    const errors = diagnostics.filter((d) => d.severity === "error").length;
    const warnings = diagnostics.length - errors;
    const counts = [errors ? plural(errors, "error", "errors") : "", warnings ? plural(warnings, "warning", "warnings")
      : ""].filter(Boolean).join(", ");
    const limit = textarea.value.length > LIMIT ? "past 100 000 characters: no colours, no checks" : "";
    note.textContent = [limit, counts, formatNote].filter(Boolean).join(" · ");
    note.classList.toggle("ed-bad", errors > 0 || formatNote !== "");
  }

  /** The offsets of each bracket and its match, from the punctuation tokens. */
  function bracketMatches(text) {
    const found = new Map();
    const open = [];
    for (const t of tokens) {
      if (t.kind !== "punct") continue;
      const c = text[t.from];
      if (BRACKETS.has(c)) {
        open.push(t.from);
      } else if ([...BRACKETS.values()].includes(c)) {
        const from = open.pop();
        if (from !== undefined && BRACKETS.get(text[from]) === c) {
          found.set(from, t.from);
          found.set(t.from, from);
        }
      }
    }
    return found;
  }

  /**
   * Draws {@code text} into the <pre>: one element per line, one span per run of characters of the same token and
   * the same diagnostic level, a mark where an empty diagnostic stands, such as a value missing at the end. The lines
   * that did not change are kept, those between the first and the last change drawn again.
   */
  function paint(text) {
    const n = text.length;
    const tokenAt = new Int32Array(n).fill(-1);
    tokens.forEach((t, k) => tokenAt.fill(k, clamp(t.from, n), clamp(t.to, n)));
    const levelAt = new Uint8Array(n);
    const marks = new Map();                            // offset -> level of an empty diagnostic there
    for (const d of diagnostics) {
      const level = d.severity === "error" ? 2 : 1;
      const from = clamp(d.from, n);
      const to = clamp(d.to, n);
      if (from >= to) {
        marks.set(from, Math.max(marks.get(from) || 0, level));
      } else {
        for (let i = from; i < to; i++) if (levelAt[i] < level) levelAt[i] = level;
      }
    }
    const lines = [{ from: 0, parts: [] }];
    let start = 0;
    const cut = (to) => {
      if (start < to) {
        lines[lines.length - 1].parts.push({ from: start, to, token: tokenAt[start], level: levelAt[start] });
      }
      start = to;
    };
    const mark = (at) => {
      if (marks.has(at)) lines[lines.length - 1].parts.push({ from: at, to: at, token: -1, level: marks.get(at) });
    };
    for (let i = 0; i < n; i++) {
      if (i > start && (tokenAt[i] !== tokenAt[start] || levelAt[i] !== levelAt[start])) cut(i);
      if (marks.has(i)) {
        cut(i);
        mark(i);
      }
      if (text[i] === "\n") {
        cut(i);
        start = i + 1;
        lines.push({ from: i + 1, parts: [] });
      }
    }
    cut(n);
    mark(n);
    const classOf = (part) => [part.token >= 0 ? "ed-" + tokens[part.token].kind : "",
      part.from === part.to ? "ed-mark" : "", part.level === 2 ? "ed-error" : part.level === 1 ? "ed-warning" : ""]
      .filter(Boolean).join(" ");
    for (const line of lines) {
      line.level = Math.max(0, ...line.parts.map((part) => part.level));
      line.key = line.level + "\u0001" + line.parts.map((part) => classOf(part) + "\u0002"
        + text.slice(part.from, part.to)).join("\u0003");
    }
    let head = 0;
    while (head < shown.length && head < lines.length && shown[head].key === lines[head].key) head++;
    let tail = 0;
    while (tail < shown.length - head && tail < lines.length - head
        && shown[shown.length - 1 - tail].key === lines[lines.length - 1 - tail].key) tail++;
    const after = tail > 0 ? shown[shown.length - tail].node : null;
    for (let k = head; k < shown.length - tail; k++) shown[k].node.remove();
    const fresh = document.createDocumentFragment();
    for (let k = head; k < lines.length - tail; k++) {
      const node = el("div", "ed-line" + (lines[k].level === 2 ? " ed-dot-error" : lines[k].level === 1
        ? " ed-dot-warning" : ""));
      for (const part of lines[k].parts) {
        const span = el("span", classOf(part) || null);
        span.textContent = text.slice(part.from, part.to);
        node.append(span);
      }
      lines[k].node = node;
      fresh.append(node);
    }
    pre.insertBefore(fresh, after);
    for (let k = 0; k < lines.length; k++) {
      if (k < head) lines[k].node = shown[k].node;
      else if (k >= lines.length - tail) lines[k].node = shown[shown.length - (lines.length - k)].node;
    }
    shown = lines;
  }

  /** The span that draws offset {@code offset}, or null. */
  function spanAt(offset) {
    let low = 0;
    let high = shown.length - 1;
    while (low < high) {
      const middle = (low + high + 1) >> 1;
      if (shown[middle].from <= offset) low = middle;
      else high = middle - 1;
    }
    const line = shown[low];
    if (!line) return null;
    const index = line.parts.findIndex((part) => part.from <= offset && offset < part.to);
    return index < 0 ? null : line.node.childNodes[index];
  }

  /** The part a span of the <pre> draws, or null. */
  function partOf(span) {
    const line = shown.find((l) => l.node === span.parentNode);
    return line ? line.parts[[...line.node.childNodes].indexOf(span)] || null : null;
  }

  /** The <pre> follows the textarea's scroll. */
  function follow() {
    pre.scrollTop = textarea.scrollTop;
    pre.scrollLeft = textarea.scrollLeft;
  }

  /** Outlines the bracket next to the caret and its match. */
  function outline() {
    for (const span of outlined) span.classList.remove("ed-match");
    outlined = [];
    if (textarea.selectionStart !== textarea.selectionEnd) return;
    const caret = textarea.selectionStart;
    const at = matches.has(caret - 1) ? caret - 1 : matches.has(caret) ? caret : -1;
    if (at < 0) return;
    for (const offset of [at, matches.get(at)]) {
      const span = spanAt(offset);
      if (span) {
        span.classList.add("ed-match");
        outlined.push(span);
      }
    }
  }

  // -------------------------------------------------------------------------------------------- placing

  /** Where offset {@code offset} is drawn, relative to the box, measured on a hidden copy of the textarea. */
  function pointAt(offset) {
    const text = textarea.value;
    mirror.style.width = textarea.offsetWidth + "px";
    mirror.textContent = text.slice(0, offset);
    const next = text.slice(offset, offset + 1);
    const marker = el("span", null, next && next !== "\n" ? next : ".");
    mirror.append(marker);
    const height = parseFloat(getComputedStyle(textarea).lineHeight) || marker.offsetHeight;
    return { left: textarea.clientLeft + marker.offsetLeft - textarea.scrollLeft,
      top: textarea.clientTop + marker.offsetTop - textarea.scrollTop, height };
  }

  /**
   * Places {@code popup} under {@code point}, or over it when the window has no room below, and never past the
   * right edge of the box.
   */
  function place(popup, point) {
    popup.style.left = "0px";
    popup.style.top = "0px";
    const width = popup.offsetWidth;
    const height = popup.offsetHeight;
    const frameTop = box.getBoundingClientRect().top;
    const below = point.top + point.height + 2;
    const above = point.top - height - 2;
    const up = frameTop + below + height > window.innerHeight && frameTop + above >= 0;
    popup.style.left = Math.max(0, Math.min(point.left, box.clientWidth - width)) + "px";
    popup.style.top = (up ? above : below) + "px";
  }

  // -------------------------------------------------------------------------------------------- tooltip

  function showTip(found, point) {
    tip.replaceChildren(...found.map((d) => el("div", "ed-tip-" + (d.severity === "error" ? "error" : "warning"),
      String(d.message))));
    tip.hidden = false;
    textarea.setAttribute("aria-describedby", tip.id);
    place(tip, point);
  }

  function hideTip() {
    tip.hidden = true;
    textarea.removeAttribute("aria-describedby");
  }

  /** The messages of the diagnostics the caret is on, unless the pointer shows others or the list is open. */
  function tipAtCaret() {
    if (hovering) return;
    if (completion || document.activeElement !== textarea || textarea.selectionStart !== textarea.selectionEnd) {
      hideTip();
      return;
    }
    const caret = textarea.selectionStart;
    const here = diagnostics.filter((d) => d.from <= caret && caret <= d.to);
    if (!here.length) {
      hideTip();
      return;
    }
    showTip(here, pointAt(Math.min(...here.map((d) => d.from))));
  }

  /** The messages of the diagnostics under the pointer, found among the spans the textarea covers. */
  function tipAtPointer(x, y) {
    const span = document.elementsFromPoint(x, y).find((e) => e.parentNode && e.parentNode.parentNode === pre
      && (e.classList.contains("ed-error") || e.classList.contains("ed-warning")));
    const part = span ? partOf(span) : null;
    const here = part ? diagnostics.filter((d) => part.from === part.to ? d.from === part.from && d.to === part.to
      : d.from <= part.from && part.from < d.to) : [];
    hovering = here.length > 0 && !completion;
    if (!hovering) {
      tipAtCaret();
      return;
    }
    const spanBox = span.getBoundingClientRect();
    const area = box.getBoundingClientRect();
    showTip(here, { left: spanBox.left - area.left, top: spanBox.top - area.top, height: spanBox.height });
  }

  // -------------------------------------------------------------------------------------------- editing

  /**
   * Replaces {@code from}-{@code to} with {@code insert} as typing would, so that Ctrl+Z undoes it in one step, then
   * puts the caret at {@code caret}, an offset into insert, and selects back to {@code anchor} when given.
   */
  function edit(from, to, insert, caret, anchor) {
    textarea.focus();
    if (from !== to || insert !== "") {
      textarea.setSelectionRange(from, to);
      const typed = insert === "" ? document.execCommand("delete")
        : document.execCommand("insertText", false, insert);
      if (!typed) {
        textarea.setRangeText(insert, from, to, "end");
        textarea.dispatchEvent(new Event("input", { bubbles: true }));
      }
    }
    const end = from + caret;
    const start = anchor === undefined ? end : from + anchor;
    textarea.setSelectionRange(Math.min(start, end), Math.max(start, end), start > end ? "backward" : "forward");
    outline();
  }

  function formatText() {
    let formatted;
    try {
      formatted = language.format(textarea.value);
    } catch (refused) {
      formatNote = "not formatted: " + (refused && refused.message ? refused.message : String(refused));
      say();
      return;
    }
    formatNote = "";
    closeList();
    if (formatted !== textarea.value) edit(0, textarea.value.length, formatted, 0);
    say();
  }

  // -------------------------------------------------------------------------------------------- completion

  /**
   * Opens the completion list at the caret, or refilters it; {@code keysOnly}: only when the caret is in a key, as
   * after a quote typed there. Closes it when there is nothing to offer.
   */
  function openList(keysOnly) {
    const found = textarea.selectionStart !== textarea.selectionEnd ? null
      : safely(() => language.complete(textarea.value, textarea.selectionStart, data), null);
    if (!found || !Array.isArray(found.items) || !found.items.length || (keysOnly && found.items[0].kind !== "key")) {
      closeList();
      return;
    }
    completion = { from: found.from, to: found.to, items: found.items, active: 0 };
    list.replaceChildren(...found.items.map((item, i) => {
      const option = el("div", "ed-item");
      option.id = id + "-item-" + i;
      option.setAttribute("role", "option");
      option.append(el("span", "ed-item-label", item.label), el("span", "ed-item-detail", item.detail || ""));
      option.addEventListener("mousedown", (event) => event.preventDefault());
      option.addEventListener("click", () => accept(i));
      return option;
    }));
    list.hidden = false;
    textarea.setAttribute("aria-expanded", "true");
    hideTip();
    hovering = false;
    activate(0);
    place(list, pointAt(completion.from));
  }

  function activate(index) {
    completion.active = index;
    [...list.children].forEach((option, i) => option.setAttribute("aria-selected", String(i === index)));
    textarea.setAttribute("aria-activedescendant", id + "-item-" + index);
    const option = list.children[index];
    if (option.offsetTop < list.scrollTop) list.scrollTop = option.offsetTop;
    else if (option.offsetTop + option.offsetHeight > list.scrollTop + list.clientHeight) {
      list.scrollTop = option.offsetTop + option.offsetHeight - list.clientHeight;
    }
  }

  function closeList() {
    if (!completion) return;
    completion = null;
    list.hidden = true;
    list.replaceChildren();
    textarea.setAttribute("aria-expanded", "false");
    textarea.removeAttribute("aria-activedescendant");
  }

  function accept(index) {
    const { from, to, items } = completion;
    const item = items[index];
    closeList();
    edit(from, to, item.insert, typeof item.caret === "number" ? item.caret : item.insert.length);
  }

  // -------------------------------------------------------------------------------------------- events

  textarea.addEventListener("keydown", (event) => {
    if (event.isComposing || MODIFIERS.has(event.key)) return;
    if (completion) {
      const count = completion.items.length;
      if (event.key === "ArrowDown" || event.key === "ArrowUp") {
        event.preventDefault();
        activate((completion.active + (event.key === "ArrowDown" ? 1 : count - 1)) % count);
        return;
      }
      if (event.key === "Enter" || event.key === "Tab") {
        event.preventDefault();
        accept(completion.active);
        return;
      }
      if (event.key === "Escape") {
        event.preventDefault();
        closeList();
        return;
      }
    }
    if (event.key === " " && event.ctrlKey && !event.altKey && !event.metaKey) {
      event.preventDefault();
      openList(false);
      return;
    }
    if (event.code === "KeyF" && event.shiftKey && event.altKey && !event.ctrlKey && !event.metaKey) {
      event.preventDefault();
      formatText();
      return;
    }
    const leaving = escaped;
    escaped = event.key === "Escape";
    if (isShortcut(event.key, event.ctrlKey, event.altKey, event.metaKey)) return;
    const key = event.key === "Tab" && event.shiftKey ? "Shift+Tab" : event.key;
    const tab = key === "Tab" || key === "Shift+Tab";
    if (tab && leaving) return;
    const paired = key.length === 1 && Array.isArray(language.pairs) && language.pairs.some((p) => p.includes(key));
    if (!KEYS.has(key) && !paired) return;
    const change = safely(() => keystroke(language, textarea.value, textarea.selectionStart, textarea.selectionEnd,
      key), null);
    if (change === null) {
      if (tab) event.preventDefault();
      return;
    }
    event.preventDefault();
    edit(change.from, change.to, change.insert, change.caret, change.anchor);
    if (key === "\"") openList(true);
  });

  textarea.addEventListener("input", () => {
    formatNote = "";
    schedule();
    if (completion) openList(false);
  });
  textarea.addEventListener("scroll", () => {
    follow();
    if (completion) place(list, pointAt(completion.from));
    hovering = false;
    hideTip();
  });
  for (const type of ["keyup", "mouseup", "focus"]) {
    textarea.addEventListener(type, () => {
      // The caret moved off what the list would replace (an arrow key, a click): Enter types again.
      const { selectionStart: start, selectionEnd: end } = textarea;
      if (completion && (start < completion.from || end > completion.to)) closeList();
      outline();
      tipAtCaret();
    });
  }
  textarea.addEventListener("blur", () => {
    closeList();
    hovering = false;
    hideTip();
  });
  textarea.addEventListener("mousemove", (event) => {
    const x = event.clientX;
    const y = event.clientY;
    if (hoverFrame === 0) hoverFrame = requestAnimationFrame(() => { hoverFrame = 0; tipAtPointer(x, y); });
  });
  textarea.addEventListener("mouseleave", () => {
    hovering = false;
    tipAtCaret();
  });
  format.addEventListener("click", formatText);

  draw();
  return {
    root,
    textarea,
    value: () => textarea.value,
    setValue(text) {
      textarea.value = typeof text === "string" ? text : "";
      textarea.scrollTop = 0;
      formatNote = "";
      closeList();
      schedule();
    },
    disable(on) {
      textarea.disabled = on;
      format.disabled = on;
      root.classList.toggle("ed-disabled", on);
      if (on) closeList();
    },
    focus: () => textarea.focus(),
  };
}
