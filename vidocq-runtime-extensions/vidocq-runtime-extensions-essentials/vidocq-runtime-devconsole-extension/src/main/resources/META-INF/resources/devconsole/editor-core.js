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
// ------------------------------------------------------------------------------------------------ schema

/** The JSON Schema types a value can have: a type the editor does not know is not checked. */
const TYPES = new Set(["object", "array", "string", "number", "integer", "boolean", "null"]);
/** The keywords whose subschemas are never checked: a value under them is accepted as it is. */
const UNCHECKED = ["anyOf", "oneOf", "allOf", "not", "patternProperties"];
/** How many $ref one resolution follows before it gives up: a cycle of references ends there. */
const MAX_REFS = 32;
/** An ISO date, and a time of day with or without seconds and offset, as the formats below read them. */
const DATE = "\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])";
const TIME = "([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d(\\.\\d+)?)?([Zz]|[+-]([01]\\d|2[0-3]):[0-5]\\d)?";
/** What a string of each format looks like: a warning, never a refusal, since the server stays the judge. */
const FORMAT_PATTERNS = new Map([
  ["date", new RegExp("^" + DATE + "$")],
  ["time", new RegExp("^" + TIME + "$")],
  ["date-time", new RegExp("^" + DATE + "[Tt]" + TIME + "$")],
  ["uuid", /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/],
]);

/**
 * {@code schema} with its local $ref followed, or null when it is no object, its $ref is not a local one (it is then
 * not checked), or a chain of references runs past MAX_REFS, as a cycle does. {@code root} is what "#" names.
 */
function resolve(schema, root) {
  let s = schema;
  for (let refs = 0; isObject(s) && Object.hasOwn(s, "$ref"); refs++) {
    if (refs === MAX_REFS || typeof s.$ref !== "string") return null;
    s = pointer(root, s.$ref);
  }
  return isObject(s) ? s : null;
}

/** The schema a local $ref names under #/$defs/ or #/definitions/, or undefined for any other reference. */
function pointer(root, ref) {
  if (!ref.startsWith("#/$defs/") && !ref.startsWith("#/definitions/")) return undefined;
  let s = root;
  for (const part of ref.slice(2).split("/")) {
    const name = part.replaceAll("~1", "/").replaceAll("~0", "~");
    if (!isObject(s) || !Object.hasOwn(s, name)) return undefined;
    s = s[name];
  }
  return s;
}

/** The schema of member {@code key} of an object of schema {@code s}, unresolved; null when it has none. */
function memberSchema(s, key) {
  if (isObject(s.properties) && Object.hasOwn(s.properties, key)) return s.properties[key];
  return isObject(s.additionalProperties) ? s.additionalProperties : null;
}

/** The schema of item {@code i} of an array of schema {@code s}, unresolved; null when it has none. */
function itemSchema(s, i) {
  if (Array.isArray(s.items)) return i < s.items.length ? s.items[i] : null;
  return isObject(s.items) ? s.items : null;
}

/** The types {@code s} allows that the editor knows, or null when it says none it knows. */
function typesOf(s) {
  const named = typeof s.type === "string" ? [s.type] : Array.isArray(s.type) ? s.type : [];
  const known = named.filter((type) => typeof type === "string" && TYPES.has(type));
  return known.length ? known : null;
}

/** Whether the parsed {@code node} is of JSON Schema type {@code type}; integer refuses 1.5, not 1.0. */
function hasType(node, type) {
  switch (type) {
    case "object":
    case "array":
    case "string":
    case "number":
      return node.kind === type;
    case "integer":
      return node.kind === "number" && Number.isInteger(node.value);
    case "boolean":
      return node.kind === "literal" && typeof node.value === "boolean";
    default:
      return node.kind === "literal" && node.value === null;
  }
}

/** Whether the parsed {@code node} equals the JSON value {@code v}. */
function same(node, v) {
  switch (node.kind) {
    case "object":
      return isObject(v) && Object.keys(v).length === new Set(node.members.map((m) => m.key)).size
        && node.members.every((m) => Object.hasOwn(v, m.key) && same(m.value, v[m.key]));
    case "array":
      return Array.isArray(v) && v.length === node.items.length && node.items.every((item, i) => same(item, v[i]));
    default:
      return node.value === v;
  }
}

/** The values of an enum as a message lists them: the first five, as JSON. */
const listed = (values) => values.slice(0, 5).map((v) => JSON.stringify(v)).join(", ")
  + (values.length > 5 ? ", …" : "");

/**
 * The schema checks of the parsed {@code value} against {@code schema}, at every depth, in the order of the text. A
 * value of the wrong type is checked no further. An object or an array is marked on its opening bracket, so that a
 * tooltip does not cover all it holds. Its own stack, as the parser.
 */
function check(value, schema) {
  const out = [];
  const diagnostic = (from, to, severity, message) => out.push({ from, to, severity, message });
  const work = [[value, resolve(schema, schema)]];
  while (work.length) {
    const [node, s] = work.pop();
    if (s === null) continue;
    const container = node.kind === "object" || node.kind === "array";
    const from = node.from;
    const to = container ? node.from + 1 : node.to;
    const types = typesOf(s);
    if (types && !types.some((type) => hasType(node, type))) {
      diagnostic(from, to, "error", "expected " + types.join(" or "));
      continue;
    }
    if (Array.isArray(s.enum) && s.enum.length && !s.enum.some((v) => same(node, v))) {
      diagnostic(from, to, "error", "not one of " + listed(s.enum));
      continue;
    }
    if (node.kind === "string") {
      if (typeof s.maxLength === "number" && [...node.value].length > s.maxLength) {
        diagnostic(from, to, "error", "longer than " + s.maxLength + " characters");
      }
      const format = typeof s.format === "string" ? FORMAT_PATTERNS.get(s.format) : undefined;
      if (format && !format.test(node.value)) {
        diagnostic(from, to, "warning", "not a " + s.format + ", such as " + FORMAT_EXAMPLES.get(s.format));
      }
    } else if (node.kind === "object") {
      const keys = new Set(node.members.map((m) => m.key));
      for (const name of Array.isArray(s.required) ? s.required : []) {
        if (typeof name === "string" && !keys.has(name)) {
          diagnostic(from, to, "error", "missing required key " + JSON.stringify(name));
        }
      }
      const properties = isObject(s.properties) ? s.properties : null;
      const closed = properties !== null && Object.keys(properties).length > 0 && s.additionalProperties !== true
        && !isObject(s.additionalProperties) && !UNCHECKED.some((keyword) => Object.hasOwn(s, keyword));
      for (const m of node.members) {
        if (closed && !Object.hasOwn(properties, m.key)) {
          diagnostic(m.keyFrom, m.keyTo, "warning", "unknown key " + JSON.stringify(m.key));
        }
        work.push([m.value, resolve(memberSchema(s, m.key), schema)]);
      }
    } else if (node.kind === "array") {
      node.items.forEach((item, i) => work.push([item, resolve(itemSchema(s, i), schema)]));
    }
  }
  return out.sort((a, b) => a.from - b.from || a.to - b.to);
}
// ------------------------------------------------------------------------------------------------ the language

/** JSON, its data the JSON Schema of the value (spec §3). */
export const jsonLanguage = Object.freeze({
  id: "json",
  tokenize: (text) => lex(text),
  diagnose(text, data) {
    const { value, error } = parse(text, lex(text));
    return error ? [error] : check(value, data);
  },
  pairs: Object.freeze(["{}", "[]", "()", "\"\""]),
});
