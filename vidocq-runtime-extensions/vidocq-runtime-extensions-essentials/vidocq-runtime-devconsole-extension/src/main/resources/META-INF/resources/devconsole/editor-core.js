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

// The dev console's code editor, its pure half: the languages and the smart keystrokes. It never touches the page, so
// that it runs in GraalJS for the tests (EditorCoreTest) exactly as it runs in the browser; editor.js draws what it
// computes.
//
// - A language is { id, tokenize(text), diagnose(text, data), complete(text, caret, data), format(text), pairs }.
//   Every offset is a UTF-16 offset into the text, as a textarea counts them.
// - jsonLanguage reads JSON, its data being the JSON Schema of the value: the first syntax error, then what the
//   schema says at every depth, following properties, items, additionalProperties and a local $ref, never anyOf,
//   oneOf, allOf, not or patternProperties. A schema, however odd, never makes it throw: it checks less.
// - keystroke() is the edit a key makes, or null to let the browser type it.

/** One level of indentation, as the formatter and the Tab key write it. */
export const INDENT = "  ";

/** An example of each string format the editor knows, which the generated form also shows as a placeholder. */
export const FORMAT_EXAMPLES = new Map([["date", "2026-09-30"], ["time", "14:30:00"],
  ["date-time", "2026-09-30T14:30:00"], ["uuid", "123e4567-e89b-12d3-a456-426614174000"]]);

const PUNCT = "{}[]:,";
const NUMBER = /^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$/;
const LITERALS = new Map([["true", true], ["false", false], ["null", null]]);

const isBlank = (c) => c === " " || c === "\t" || c === "\n" || c === "\r";
const isObject = (v) => v !== null && typeof v === "object" && !Array.isArray(v);
const isStringToken = (t) => t.kind === "string" || t.kind === "key";

// ------------------------------------------------------------------------------------------------ tokens

/**
 * The tokens of {@code text}, in order, covering every character that is not blank: key (a string followed by ":"),
 * string, number, literal (true, false, null), punct ({ } [ ] : ,), and invalid for any other run of characters up to
 * the next blank, punctuation or quote. A string with no closing quote runs to the end of its line.
 */
function lex(text) {
  const tokens = [];
  const n = text.length;
  let i = 0;
  while (i < n) {
    const c = text[i];
    if (isBlank(c)) {
      i++;
      continue;
    }
    if (PUNCT.includes(c)) {
      tokens.push({ from: i, to: i + 1, kind: "punct" });
      i++;
      continue;
    }
    let j = i + 1;
    if (c === "\"") {
      while (j < n) {
        const d = text[j];
        if (d === "\n" || d === "\r") break;
        j++;
        if (d === "\"") break;
        if (d === "\\" && j < n && text[j] !== "\n" && text[j] !== "\r") j++;
      }
      tokens.push({ from: i, to: j, kind: "string" });
    } else {
      while (j < n && !isBlank(text[j]) && !PUNCT.includes(text[j]) && text[j] !== "\"") j++;
      const word = text.slice(i, j);
      tokens.push({ from: i, to: j, kind: NUMBER.test(word) ? "number" : LITERALS.has(word) ? "literal" : "invalid" });
    }
    i = j;
  }
  for (let k = 0; k + 1 < tokens.length; k++) {
    if (tokens[k].kind === "string" && punctAt(text, tokens[k + 1]) === ":") tokens[k].kind = "key";
  }
  return tokens;
}

/** The punctuation character of token {@code t}, or null when it is none. */
const punctAt = (text, t) => t !== undefined && t.kind === "punct" ? text[t.from] : null;

/** Whether the string token {@code t} ends with its closing quote, an escaped quote not being one. */
function isClosed(text, t) {
  if (t.to - t.from < 2 || text[t.to - 1] !== "\"") return false;
  let backslashes = 0;
  for (let i = t.to - 2; i > t.from && text[i] === "\\"; i--) backslashes++;
  return backslashes % 2 === 0;
}

// ------------------------------------------------------------------------------------------------ syntax

/** The value of the string token {@code t}, or undefined when it is unterminated or holds an invalid escape. */
function stringValue(text, t) {
  if (!isClosed(text, t)) return undefined;
  try {
    return JSON.parse(text.slice(t.from, t.to));
  } catch (invalid) {
    return undefined;
  }
}

/**
 * {@code text} parsed from its {@code tokens}: { value, error }, value a tree of nodes that keep their offsets
 * ({ kind: "object", from, to, members: [{ key, keyFrom, keyTo, value }] }, { kind: "array", from, to, items },
 * { kind: "string" | "number" | "literal", from, to, value }), error the first syntax error, or null. The parser
 * keeps its own stack, so that a deeply nested text never exhausts the engine's. An error at the end of the text is
 * empty: from and to are both its length.
 */
function parse(text, tokens) {
  const end = text.length;
  const stack = [];
  let root;
  let k = 0;
  const fail = (t, message) => ({ value: null, error: { from: t ? t.from : end, to: t ? t.to : end,
    severity: "error", message } });
  const attach = (node) => {
    const top = stack[stack.length - 1];
    if (!top) {
      root = node;
    } else if (top.kind === "object") {
      top.members.push({ key: top.key, keyFrom: top.keyFrom, keyTo: top.keyTo, value: node });
      top.expect = "comma";
    } else {
      top.items.push(node);
      top.expect = "comma";
    }
  };
  const close = (container, t) => {
    container.to = t.to;
    stack.pop();
    attach(container);
  };
  for (;;) {
    const t = tokens[k];
    const top = stack[stack.length - 1];
    const c = punctAt(text, t);
    if (!top) {
      if (root !== undefined) return t ? fail(t, "nothing after the value") : { value: root, error: null };
    } else if (top.kind === "object" && top.expect !== "value") {
      if (top.expect === "comma") {
        if (c === ",") { top.expect = "key"; top.comma = t; k++; continue; }
        if (c === "}") { k++; close(top, t); continue; }
        return fail(t, "expected ',' or '}'");
      }
      if (top.expect === "colon") {
        if (c === ":") { top.expect = "value"; k++; continue; }
        return fail(t, "expected ':'");
      }
      if (c === "}") {
        if (top.expect === "key") return fail(top.comma, "trailing comma");
        k++;
        close(top, t);
        continue;
      }
      if (!t || !isStringToken(t)) return fail(t, top.expect === "first" ? "expected a key or '}'" : "expected a key");
      const key = stringValue(text, t);
      if (key === undefined) return fail(t, isClosed(text, t) ? "invalid string" : "unterminated string");
      top.key = key;
      top.keyFrom = t.from;
      top.keyTo = t.to;
      top.expect = "colon";
      k++;
      continue;
    } else if (top.kind === "array" && top.expect === "comma") {
      if (c === ",") { top.expect = "value"; top.comma = t; k++; continue; }
      if (c === "]") { k++; close(top, t); continue; }
      return fail(t, "expected ',' or ']'");
    } else if (top.kind === "array" && c === "]") {
      if (top.expect === "value") return fail(top.comma, "trailing comma");
      k++;
      close(top, t);
      continue;
    }
    // a value is expected here
    if (!t) return fail(t, "expected a value");
    if (c === "{") {
      stack.push({ kind: "object", from: t.from, to: end, members: [], expect: "first" });
    } else if (c === "[") {
      stack.push({ kind: "array", from: t.from, to: end, items: [], expect: "first" });
    } else if (isStringToken(t)) {
      const value = stringValue(text, t);
      if (value === undefined) return fail(t, isClosed(text, t) ? "invalid string" : "unterminated string");
      attach({ kind: "string", from: t.from, to: t.to, value });
    } else if (t.kind === "number") {
      attach({ kind: "number", from: t.from, to: t.to, value: Number(text.slice(t.from, t.to)) });
    } else if (t.kind === "literal") {
      attach({ kind: "literal", from: t.from, to: t.to, value: LITERALS.get(text.slice(t.from, t.to)) });
    } else {
      return fail(t, t.kind === "invalid" ? "unexpected token" : "expected a value");
    }
    k++;
  }
}
// ------------------------------------------------------------------------------------------------ the language

/** JSON, its data the JSON Schema of the value (spec §3). */
export const jsonLanguage = Object.freeze({
  id: "json",
  tokenize: (text) => lex(text),
  diagnose(text, data) {
    const { error } = parse(text, lex(text));
    return error ? [error] : [];
  },
  pairs: Object.freeze(["{}", "[]", "()", "\"\""]),
});
