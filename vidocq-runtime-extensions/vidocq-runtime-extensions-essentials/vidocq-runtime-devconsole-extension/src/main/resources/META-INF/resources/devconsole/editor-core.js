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
// - A language is { id, tokenize(text, data), diagnose(text, data), complete(text, caret, data), format(text, data),
//   pairs }. Every offset is a UTF-16 offset into the text, as a textarea counts them.
// - jsonLanguage reads JSON, its data being the JSON Schema of the value: the first syntax error, then what the
//   schema says at every depth, following properties, items, additionalProperties and a local $ref, never anyOf,
//   oneOf, allOf, not or patternProperties. A schema, however odd, never makes it throw: it checks less.
// - queryLanguage() reads a query (JDQL), its data being the language a panel publishes: a dialect and a vocabulary
//   of targets and their attributes. It colours, completes and checks names, never the grammar, which the server
//   judges; parameters() is the JSON Schema of the query's named parameters. Odd data reads as none.
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

/**
 * Whether the string token {@code t} ends with its closing quote, the quote it opens with: in JSON ("), an escaped
 * quote is no closing one; in a query (the dialect's quote, ' in JDQL), a doubled one is none.
 */
function isClosed(text, t) {
  const quote = text[t.from];
  if (t.to - t.from < 2 || text[t.to - 1] !== quote) return false;
  if (quote !== "\"") {
    let quotes = 0;
    for (let i = t.to - 1; i > t.from && text[i] === quote; i--) quotes++;
    return quotes % 2 === 1;
  }
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
// ------------------------------------------------------------------------------------------------ completion

/**
 * Where {@code caret} is (spec §3.3), from the tokens before it: { position: "key" | "value" | null, schema, keys,
 * current }. schema is the resolved schema of the object whose key is typed, or of the value typed; keys, the keys
 * that object already has, before and after the caret, the one being typed excepted; current, the token the caret
 * is in, being typed, or null. Unlike the parser, it goes on past an error: a text being typed rarely parses.
 */
function contextAt(text, tokens, caret, data) {
  const stack = [];
  let rootDone = false;
  let found = null;
  const valueSchema = (top) => top === undefined ? resolve(data, data)
    : top.schema === null ? null
    : resolve(top.kind === "object" ? memberSchema(top.schema, top.key) : itemSchema(top.schema, top.count), data);
  const here = () => {
    const top = stack[stack.length - 1];
    if (!top) return { frame: null, position: rootDone ? null : "value", schema: resolve(data, data) };
    if (top.kind === "object" && (top.expect === "first" || top.expect === "key")) {
      return { frame: top, position: "key", schema: top.schema };
    }
    if ((top.kind === "object" && top.expect === "value") || (top.kind === "array" && top.expect !== "comma")) {
      return { frame: top, position: "value", schema: valueSchema(top) };
    }
    return { frame: top, position: null, schema: null };
  };
  const step = (t) => {
    const top = stack[stack.length - 1];
    const c = punctAt(text, t);
    if (c === "{" || c === "[") {
      const schema = valueSchema(top);
      if (top) {
        top.count++;
        top.expect = "comma";
      }
      stack.push({ kind: c === "{" ? "object" : "array", schema, keys: new Set(), expect: "first", key: null,
        count: 0 });
    } else if (c === "}" || c === "]") {
      const kind = c === "}" ? "object" : "array";
      for (let i = stack.length - 1; i >= 0; i--) {
        if (stack[i].kind === kind) {
          stack.length = i;
          if (i === 0) rootDone = true;
          break;
        }
      }
    } else if (!top) {
      rootDone = true;
    } else if (c === ",") {
      top.expect = top.kind === "object" ? "key" : "value";
    } else if (c === ":") {
      if (top.kind === "object") top.expect = "value";
    } else if (top.kind === "object" && top.expect !== "colon" && top.expect !== "value" && isStringToken(t)) {
      const key = stringValue(text, t);
      top.key = key !== undefined ? key : text.slice(t.from + 1, t.to);
      top.keys.add(top.key);
      top.expect = "colon";
    } else if (top.kind === "array" || top.expect === "value" || top.expect === "colon") {
      top.count++;
      top.expect = "comma";
    }
  };
  let current = null;
  for (const t of tokens) {
    if (found === null && (t.from >= caret || touches(text, t, caret))) {
      found = here();
      if (t.from < caret) current = t;
    }
    if (found !== null && found.frame !== null && !stack.includes(found.frame)) break;
    if (found !== null && found.frame === null) break;
    if (t === current && found.position === "key") continue;
    step(t);
  }
  if (found === null) found = here();
  return { position: found.position, schema: found.schema,
    keys: found.frame !== null ? found.frame.keys : new Set(), current, inContainer: found.frame !== null };
}

/** Whether the caret is in token {@code t}, typing it: inside a closed string, at the end of an open one or a word. */
const touches = (text, t, caret) => t.kind !== "punct" && t.from < caret
  && (caret < t.to || (caret === t.to && (!isStringToken(t) || !isClosed(text, t))));

/** What a completion says of a schema's type: string, integer, enum, object, string | null …, or any. */
function kindOf(p) {
  if (p === null) return "any";
  if (Array.isArray(p.enum) && p.enum.length) return "enum";
  const types = typesOf(p);
  return types ? types.join(" | ") : isObject(p.properties) ? "object" : "any";
}

/** The types a value of schema {@code p} may take, an object assumed when it lists properties and no type. */
const typesFor = (p) => typesOf(p) || (isObject(p.properties) ? ["object"] : []);

/**
 * The start of a value of schema {@code p}: { text, caret }, caret an offset into text, or undefined for after it.
 * "" with the caret inside, 0, false, null, the first enum value, [] or {} with the caret inside; at the first
 * level, an object whose schema has required keys starts with them.
 */
function valueStart(p, root, depth) {
  if (p === null) return { text: "null" };
  if (Array.isArray(p.enum) && p.enum.length) return { text: JSON.stringify(p.enum[0]) };
  const types = typesFor(p);
  switch (types.find((type) => type !== "null") || types[0]) {
    case "string":
      return { text: "\"\"", caret: 1 };
    case "number":
    case "integer":
      return { text: "0" };
    case "boolean":
      return { text: "false" };
    case "array":
      return { text: "[]", caret: 1 };
    case "object":
      return depth === 0 ? objectStart(p, root) : { text: "{}", caret: 1 };
    default:
      return { text: "null" };
  }
}

/** An object of schema {@code s} with its required keys, the caret in or after the first one's value; else {}. */
function objectStart(s, root) {
  const required = Array.isArray(s.required) ? s.required.filter((name) => typeof name === "string") : [];
  if (!required.length) return { text: "{}", caret: 1 };
  const properties = isObject(s.properties) ? s.properties : {};
  let text = "{";
  let caret;
  for (const name of required) {
    if (text.length > 1) text += ", ";
    text += JSON.stringify(name) + ": ";
    const start = valueStart(resolve(Object.hasOwn(properties, name) ? properties[name] : null, root), root, 1);
    if (caret === undefined) caret = text.length + (start.caret !== undefined ? start.caret : start.text.length);
    text += start.text;
  }
  return { text: text + "}", caret };
}

/**
 * The keys of object schema {@code s} not in {@code present}: required first, then the others, then the read-only
 * ones, each group in schema order. {@code keyOnly}: the key is already followed by ":", only it is replaced.
 */
function keyItems(s, present, keyOnly, root) {
  const properties = isObject(s.properties) ? s.properties : {};
  const required = new Set(Array.isArray(s.required) ? s.required.filter((name) => typeof name === "string") : []);
  const names = [...Object.keys(properties), ...[...required].filter((name) => !Object.hasOwn(properties, name))];
  const ranked = [];
  for (const name of names) {
    if (present.has(name)) continue;
    const p = resolve(Object.hasOwn(properties, name) ? properties[name] : null, root);
    const readOnly = p !== null && p.readOnly === true;
    const mandatory = required.has(name) && !readOnly;
    const description = p !== null && typeof p.description === "string" && p.description ? " — " + p.description : "";
    const key = JSON.stringify(name);
    const item = { insert: key, label: name, kind: "key",
      detail: [kindOf(p), ...(mandatory ? ["required"] : []), ...(readOnly ? ["generated"] : [])].join(", ")
        + description };
    if (!keyOnly) {
      const start = valueStart(p, root, 0);
      item.insert = key + ": " + start.text;
      if (start.caret !== undefined) item.caret = key.length + 2 + start.caret;
    }
    ranked.push({ rank: readOnly ? 2 : mandatory ? 0 : 1, item });
  }
  return ranked.sort((a, b) => a.rank - b.rank).map((r) => r.item);
}

/** The values a value of schema {@code p} may start with: its enum, true and false, null, then {} or []. */
function valueItems(p, root) {
  const items = [];
  const add = (insert, detail, caret) => {
    if (items.some((item) => item.insert === insert)) return;
    items.push(caret === undefined ? { insert, label: insert, detail, kind: "value" }
      : { insert, label: insert, detail, kind: "value", caret });
  };
  if (Array.isArray(p.enum)) for (const v of p.enum) add(JSON.stringify(v), "enum");
  const types = typesFor(p);
  if (types.includes("boolean")) {
    add("true", "boolean");
    add("false", "boolean");
  }
  if (types.includes("null")) add("null", "null");
  if (types.includes("object")) {
    const start = objectStart(p, root);
    add(start.text, "object", start.caret);
  }
  if (types.includes("array")) add("[]", "array", 1);
  return items;
}

/**
 * The completion at {@code caret} (spec §3.3): { from, to, items }, from-to what is already typed, a key with its
 * quotes (a string with no closing quote up to the caret only, since it runs to the end of its line; so too a string
 * whose closing quote is another member's opening one, a punctuation character between the caret and it), items
 * filtered by it ignoring case; null anywhere else, with no schema for the place, or with no item left. Inside an
 * object or an array, an item followed by another member ends with "," (the caret staying before it), so that
 * accepting it keeps the text JSON.
 */
function complete(text, caret, data) {
  const at = contextAt(text, lex(text), caret, data);
  if (at.position === null || at.schema === null) return null;
  const t = at.current;
  const from = t ? t.from : caret;
  const swallowed = t !== null && t.kind === "string" && isClosed(text, t)
    && /[,:{}[\]]/.test(text.slice(caret, t.to - 1));
  const to = t && !swallowed && (!isStringToken(t) || isClosed(text, t)) ? t.to : caret;
  let items;
  if (at.position === "key") {
    const prefix = t ? text.slice(t.from + (isStringToken(t) ? 1 : 0), caret).toLowerCase() : "";
    items = keyItems(at.schema, at.keys, t !== null && t.kind === "key", data)
      .filter((item) => item.label.toLowerCase().startsWith(prefix));
  } else {
    const prefix = t ? text.slice(t.from, caret).toLowerCase() : "";
    items = valueItems(at.schema, data).filter((item) => item.insert.toLowerCase().startsWith(prefix));
  }
  if (!items.length) return null;
  if (at.inContainer && memberFollows(text, to)) {
    for (const item of items) {
      if (item.caret === undefined) item.caret = item.insert.length;
      item.insert += ",";
    }
  }
  return { from, to, items };
}

/** Whether a member follows offset {@code i}: the next character that is not blank is none of , : } ] (nor the end). */
function memberFollows(text, i) {
  while (i < text.length && isBlank(text[i])) i++;
  return i < text.length && !",:}]".includes(text[i]);
}
// ------------------------------------------------------------------------------------------------ formatting

/** The line of {@code offset}, counted from 1. */
function lineOf(text, offset) {
  let line = 1;
  for (let i = text.indexOf("\n"); i >= 0 && i < offset; i = text.indexOf("\n", i + 1)) line++;
  return line;
}

/**
 * {@code text} re-indented from its tokens: two spaces, one member per line, an empty {} or [] kept on one line, the
 * text of every string and number copied as written. Throws, saying why, when the text does not parse.
 */
function format(text) {
  const tokens = lex(text);
  const { error } = parse(text, tokens);
  if (error) throw new Error("line " + lineOf(text, error.from) + ": " + error.message);
  let out = "";
  let depth = 0;
  for (let k = 0; k < tokens.length; k++) {
    const t = tokens[k];
    const c = punctAt(text, t);
    if (c === "{" || c === "[") {
      if (punctAt(text, tokens[k + 1]) === (c === "{" ? "}" : "]")) {
        out += c === "{" ? "{}" : "[]";
        k++;
      } else {
        depth++;
        out += c + "\n" + INDENT.repeat(depth);
      }
    } else if (c === "}" || c === "]") {
      depth--;
      out += "\n" + INDENT.repeat(depth) + c;
    } else if (c === ",") {
      out += ",\n" + INDENT.repeat(depth);
    } else if (c === ":") {
      out += ": ";
    } else {
      out += text.slice(t.from, t.to);
    }
  }
  return out;
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
  complete,
  format,
  pairs: Object.freeze(["{}", "[]", "()", "\"\""]),
});

// ------------------------------------------------------------------------------------------------ keystrokes

/** The offset where the line holding {@code offset} starts. */
const lineStart = (text, offset) => offset === 0 ? 0 : text.lastIndexOf("\n", offset - 1) + 1;

/** The lines {@code start}-{@code end} touch, from the start of the first to the end of the last. */
function lineSpan(text, start, end) {
  const last = end > start && text[end - 1] === "\n" ? end - 1 : end;
  const to = text.indexOf("\n", last);
  return { from: lineStart(text, start), to: to < 0 ? text.length : to };
}

/** The string token {@code offset} is inside of, its closing quote excluded, or null. */
function stringAround(language, text, offset) {
  for (const t of language.tokenize(text)) {
    if (t.from >= offset) return null;
    if (isStringToken(t) && (offset < t.to || (offset === t.to && !isClosed(text, t)))) return t;
  }
  return null;
}

/**
 * Whether a key pressed with these modifiers is a command for the browser rather than something typed: Command held,
 * or Control without Alt. A character typed with Option (a French Mac keyboard types { and [ so) or with AltGr (which
 * Windows reports as Control and Alt) is typed, and gets the smart keystrokes; a key that types no character, such as
 * Option+Backspace, stays the browser's.
 */
export function isShortcut(key, ctrlKey, altKey, metaKey) {
  if (metaKey || (ctrlKey && !altKey)) return true;
  return (ctrlKey || altKey) && String(key).length !== 1;
}

/**
 * The edit {@code key} makes in {@code text}, whose selection is {@code selectionStart}-{@code selectionEnd} (spec
 * §3.5): { from, to, insert, caret }, caret an offset into insert as on a completion item, and anchor, when present,
 * the other end of the selection it leaves, an offset into insert too; an empty insert over an empty range only moves
 * the caret. null: the browser types the key itself. The keys: an opening or closing character of language.pairs,
 * "Enter", "Backspace", "Tab", "Shift+Tab".
 */
export function keystroke(language, text, selectionStart, selectionEnd, key) {
  const start = Math.min(selectionStart, selectionEnd);
  const end = Math.max(selectionStart, selectionEnd);
  if (key === "Enter") return enter(text, start, end);
  if (key === "Tab") {
    return start === end ? { from: start, to: start, insert: INDENT, caret: INDENT.length } : indent(text, start, end);
  }
  if (key === "Shift+Tab") return outdent(text, start, end);
  const pairs = Array.isArray(language.pairs) ? language.pairs : [];
  if (key === "Backspace") return start === end ? backspace(language, pairs, text, start) : null;
  const opening = pairs.find((pair) => pair[0] === key);
  const closing = pairs.find((pair) => pair[1] === key);
  if (!opening && !closing) return null;
  if (start !== end) {
    return opening ? { from: start, to: end, insert: key + text.slice(start, end) + opening[1], anchor: 1,
      caret: 1 + end - start } : null;
  }
  const string = stringAround(language, text, start);
  if (closing && text[start] === key
      && (key === "\"" ? string !== null && string.to === start + 1 && isClosed(text, string) : string === null)) {
    return { from: start + 1, to: start + 1, insert: "", caret: 0 };
  }
  if (!opening || string !== null) return null;
  if (key === "\"" && /[\p{L}\p{N}]$/u.test(text.slice(Math.max(0, start - 2), start))) return null;
  return { from: start, to: start, insert: opening, caret: 1 };
}

/** Enter: the line's indentation kept; between {} or [], an indented line and the closing character on the next. */
function enter(text, start, end) {
  const indentation = /^[ \t]*/.exec(text.slice(lineStart(text, start), start))[0];
  const before = text[start - 1];
  const after = text[end];
  if ((before === "{" && after === "}") || (before === "[" && after === "]")) {
    const opened = "\n" + indentation + INDENT;
    return { from: start, to: end, insert: opened + "\n" + indentation, caret: opened.length };
  }
  return { from: start, to: end, insert: "\n" + indentation, caret: 1 + indentation.length };
}

/** Backspace right between an empty pair of the language, an empty string's quotes included, deletes both. */
function backspace(language, pairs, text, offset) {
  if (offset === 0 || !pairs.includes(text.slice(offset - 1, offset + 1))) return null;
  if (text[offset] === "\"") {
    const empty = language.tokenize(text).find((t) => t.from === offset - 1);
    if (!empty || !isStringToken(empty) || empty.to !== offset + 1) return null;
  }
  return { from: offset - 1, to: offset + 1, insert: "", caret: 0 };
}

/** Tab over a selection: every line it touches indented, and selected. */
function indent(text, start, end) {
  const { from, to } = lineSpan(text, start, end);
  const insert = text.slice(from, to).split("\n").map((line) => INDENT + line).join("\n");
  return { from, to, insert, anchor: 0, caret: insert.length };
}

/** Shift+Tab: every line the selection or the caret touches outdented by one level; null when none can be. */
function outdent(text, start, end) {
  const { from, to } = lineSpan(text, start, end);
  const lines = text.slice(from, to).split("\n");
  const cuts = lines.map((line) => line.startsWith(INDENT) ? INDENT.length : /^[ \t]/.test(line) ? 1 : 0);
  if (cuts.every((cut) => cut === 0)) return null;
  const insert = lines.map((line, i) => line.slice(cuts[i])).join("\n");
  if (start === end) return { from, to, insert, caret: Math.max(0, start - from - cuts[0]) };
  return { from, to, insert, anchor: 0, caret: insert.length };
}

// ------------------------------------------------------------------------------------------------ query: tokens

/** The dialect of data that says none, or of each word it leaves out: JDQL's, as Mansart reads it (spec §2.2). */
const DIALECT = Object.freeze({
  keywords: ["SELECT", "FROM", "WHERE", "ORDER", "BY", "AND", "OR", "NOT", "IS", "NULL", "BETWEEN", "LIKE", "IN",
    "ASC", "DESC", "UPDATE", "SET", "DELETE", "COUNT", "THIS", "SUM", "AVG", "MIN", "MAX", "TRUE", "FALSE"],
  functions: ["UPPER", "LOWER", "LENGTH", "ABS", "CONCAT", "COUNT", "SUM", "AVG", "MIN", "MAX"],
  clauses: ["SELECT", "FROM", "WHERE", "ORDER BY", "SET", "UPDATE", "DELETE FROM"],
  targetAfter: ["FROM", "UPDATE"],
  self: "this",
  quote: "'",
});
/** The operators of a query, the longest first, so that <= is not read as < then =. */
const OPERATORS = ["<=", ">=", "<>", "!=", "=", "<", ">", "+", "-", "*", "/"];
/** What starts a name and what continues it, as JDQL reads them: a letter or _, then digits too. */
const NAME_START = /[\p{L}_]/u;
const NAME_PART = /[\p{L}\p{N}_]/u;
const isDigit = (c) => c !== undefined && c >= "0" && c <= "9";
/** The vocabularies read so far, by the data they were read from: a large one is read once, not on every key. */
const VOCABULARIES = new WeakMap();

/** The words of a dialect's list, in capitals, or {@code fallback}'s when it is no list. */
const wordsOf = (list, fallback) => (Array.isArray(list) ? list : fallback)
  .filter((w) => typeof w === "string" && w.trim() !== "").map((w) => w.trim().toUpperCase());

/**
 * The vocabulary of a query language's data (spec §2.2), read once per data object: keywords and functions (Sets of
 * capitals, and keywordList and functionList in the dialect's order), clauses (each a list of words, the longest
 * first), targetAfter (a Set), self, quote, and targets, a Map of each target's name to { name, detail, attributes },
 * attributes a Map of each name to { name, type, format, enum, detail, target }. Anything odd is left out; data that
 * is no object reads as the default dialect and no target.
 */
function vocabulary(data) {
  if (!isObject(data)) return NO_VOCABULARY;
  let known = VOCABULARIES.get(data);
  if (known === undefined) {
    known = readVocabulary(data);
    VOCABULARIES.set(data, known);
  }
  return known;
}

function readVocabulary(data) {
  const d = isObject(data.dialect) ? data.dialect : {};
  const keywords = wordsOf(d.keywords, DIALECT.keywords);
  const functions = wordsOf(d.functions, DIALECT.functions);
  const quote = typeof d.quote === "string" && d.quote.length === 1 && !isBlank(d.quote) && !NAME_PART.test(d.quote)
    && !"(),.:?!".includes(d.quote) && !OPERATORS.includes(d.quote) ? d.quote : DIALECT.quote;
  const self = typeof d.self === "string" && /^[\p{L}_][\p{L}\p{N}_]*$/u.test(d.self) ? d.self : DIALECT.self;
  const targets = new Map();
  for (const [name, t] of Object.entries(isObject(data.targets) ? data.targets : {})) {
    if (!isObject(t)) continue;
    const attributes = new Map();
    for (const [attribute, a] of Object.entries(isObject(t.attributes) ? t.attributes : {})) {
      if (!isObject(a)) continue;
      attributes.set(attribute, { name: attribute, type: typeof a.type === "string" ? a.type : null,
        format: typeof a.format === "string" ? a.format : null, enum: Array.isArray(a.enum) ? a.enum : null,
        detail: typeof a.detail === "string" ? a.detail : "", target: typeof a.target === "string" ? a.target : null });
    }
    targets.set(name, { name, detail: typeof t.detail === "string" ? t.detail : "", attributes });
  }
  return { keywords: new Set(keywords), keywordList: keywords, functions: new Set(functions), functionList: functions,
    clauses: wordsOf(d.clauses, DIALECT.clauses).map((c) => c.split(/\s+/)).sort((a, b) => b.length - a.length),
    targetAfter: new Set(wordsOf(d.targetAfter, DIALECT.targetAfter)), self, quote, targets };
}

/** The vocabulary of no data: the default dialect, and no target. */
const NO_VOCABULARY = readVocabulary({});

/**
 * The raw tokens of a query: { from, to, kind }, kind name, string, number, parameter (:name, ?1), operator, punct
 * (( ) , .) or invalid (one character). A string runs from {@code quote} to the next one that is not doubled, or to
 * the end of its line when there is none; a number written into a name, 12ab, is invalid as a whole.
 */
function lexQuery(text, quote) {
  const tokens = [];
  const n = text.length;
  let i = 0;
  while (i < n) {
    const c = text[i];
    if (isBlank(c)) {
      i++;
      continue;
    }
    let j = i + 1;
    let kind;
    if (c === quote) {
      kind = "string";
      while (j < n && text[j] !== "\n" && text[j] !== "\r") {
        if (text[j] === quote && text[j + 1] === quote) {
          j += 2;
        } else if (text[j++] === quote) {
          break;
        }
      }
    } else if (isDigit(c)) {
      kind = "number";
      while (isDigit(text[j])) j++;
      if (text[j] === "." && isDigit(text[j + 1])) for (j += 1; isDigit(text[j]); j++);
      if (j < n && "lLfFdD".includes(text[j])) j++;
      if (j < n && NAME_PART.test(text[j])) {
        while (j < n && NAME_PART.test(text[j])) j++;
        kind = "invalid";
      }
    } else if (NAME_START.test(c)) {
      kind = "name";
      while (j < n && NAME_PART.test(text[j])) j++;
    } else if ((c === ":" && j < n && NAME_PART.test(text[j])) || (c === "?" && isDigit(text[j]))) {
      kind = "parameter";
      while (j < n && (c === ":" ? NAME_PART.test(text[j]) : isDigit(text[j]))) j++;
    } else if ("(),.".includes(c)) {
      kind = "punct";
    } else {
      const operator = OPERATORS.find((o) => text.startsWith(o, i));
      kind = operator ? "operator" : "invalid";
      if (operator) j = i + operator.length;
      else if (c >= "\uD800" && c <= "\uDBFF" && j < n) j++;          // a character outside the BMP stays whole
    }
    tokens.push({ from: i, to: j, kind });
    i = j;
  }
  return tokens;
}

/** The last text read and what it gave: one draw asks for the tokens, the diagnostics and the parameters of it. */
let lastRead = { text: null, data: null, read: null };

/**
 * {@code text} read with the vocabulary of {@code data} (spec §3.1-§3.2): { v, tokens, target }. A token is { from,
 * to, kind }, kind keyword, function, target, attribute, identifier, string, number, parameter, operator, punct or
 * invalid; a word also has word, its capitals; a name of a path pathFrom, where the path starts, and attribute when
 * it resolves to one, or self when it is the dialect's self; a name that is wrong, problem, what is wrong. target is
 * the target named right after the first targetAfter word, wherever the caret is, or null when that name is unknown,
 * qualified (a.b.C, never checked) or missing. A path goes through the targets one step at a time: a cycle of
 * references costs one lookup per step of the text.
 */
function read(text, data) {
  if (lastRead.text === text && lastRead.data === data) return lastRead.read;
  const v = vocabulary(data);
  const tokens = lexQuery(text, v.quote);
  const self = v.self.toUpperCase();
  const isDot = (t) => t !== undefined && t.kind === "punct" && text[t.from] === ".";
  const joined = (a, b) => a !== undefined && b !== undefined && a.to === b.from;
  // A word next to a dot is a name of a path; else a function before "(", a keyword, or a name.
  tokens.forEach((t, k) => {
    if (t.kind !== "name") return;
    t.word = text.slice(t.from, t.to).toUpperCase();
    const next = tokens[k + 1];
    if ((isDot(tokens[k - 1]) && joined(tokens[k - 1], t)) || (isDot(next) && joined(t, next))) return;
    if (next !== undefined && next.kind === "punct" && text[next.from] === "(" && v.functions.has(t.word)) {
      t.kind = "function";
    } else if (v.keywords.has(t.word)) {
      t.kind = "keyword";
      if (t.word === self) t.self = true;
    }
  });
  // The name right after a targetAfter word is a target, known or not (a known one may be spelt as a keyword, such
  // as Order); the first one is the query's.
  let target = null;
  let first = true;
  tokens.forEach((t, k) => {
    const name = tokens[k + 1];
    if (t.kind !== "keyword" || !v.targetAfter.has(t.word) || name === undefined) return;
    const written = text.slice(name.from, name.to);
    if (name.kind !== "name" && !(name.kind === "keyword" && v.targets.has(written))) return;
    name.kind = "name";
    name.self = false;
    name.position = "target";
    const qualified = isDot(tokens[k + 2]) && joined(name, tokens[k + 2]);
    const entry = qualified ? null : v.targets.get(written) || null;
    if (entry !== null) name.kind = "target";
    else if (!qualified && v.targets.size) name.problem = "unknown target " + written;
    if (first) target = entry;
    first = false;
  });
  // Every other name heads a path, name.name…, resolved from the target one step at a time; with no known target,
  // or after a qualified or unknown one, nothing is checked.
  tokens.forEach((head, k) => {
    if (head.kind !== "name" || (isDot(tokens[k - 1]) && joined(tokens[k - 1], head))) return;
    let entry = head.position === "target" ? null : target;
    for (let at = k; ; at += 2) {
      const t = tokens[at];
      const name = text.slice(t.from, t.to);
      t.pathFrom = head.from;
      if (at === k && head.position !== "target" && t.word === self) {
        t.kind = "keyword";
        t.self = true;
      } else if (entry !== null && entry.attributes.has(name)) {
        t.kind = "attribute";
        t.attribute = entry.attributes.get(name);
      } else {
        t.kind = "identifier";
        if (entry !== null && entry.attributes.size) t.problem = "unknown attribute " + name + " of " + entry.name;
      }
      const dot = tokens[at + 1];
      if (!isDot(dot) || !joined(t, dot)) break;
      const after = tokens[at + 2];
      const more = after !== undefined && after.kind === "name" && joined(dot, after);
      if (t.attribute !== undefined && t.attribute.target === null) {
        (more ? after : dot).problem = name + " is not a reference";
      }
      entry = t.self ? entry : t.attribute !== undefined && t.attribute.target !== null
        ? v.targets.get(t.attribute.target) || null : null;
      if (!more) break;
    }
  });
  lastRead = { text, data, read: { v, tokens, target } };
  return lastRead.read;
}

// ------------------------------------------------------------------------------------------------ query: completion

/** The clauses whose expressions name the target's attributes (spec §3.3). */
const EXPRESSION_CLAUSES = new Set(["SELECT", "WHERE", "ORDER BY", "SET"]);
/** The kinds of a token that is a word being typed. */
const WORDS = new Set(["keyword", "function", "target", "attribute", "identifier"]);

/** The clause phrase that starts at token {@code k}, as its list of words, the longest one; null when none does. */
const clauseAt = (v, tokens, k) => v.clauses.find((words) => words.every((w, i) => tokens[k + i] !== undefined
  && tokens[k + i].kind === "keyword" && tokens[k + i].word === w)) || null;

/** The clause the tokens before index {@code end} leave open: the last clause phrase, such as "ORDER BY", or null. */
function clauseBefore(v, tokens, end) {
  let clause = null;
  for (let k = 0; k < end; k++) {
    const words = clauseAt(v, tokens, k);
    if (words !== null && k + words.length <= end) {
      clause = words.join(" ");
      k += words.length - 1;
    }
  }
  return clause;
}

/** The completion items of the attributes of {@code entry}, in its order. */
const attributeItems = (entry) => [...entry.attributes.values()].map((a) => ({ insert: a.name, label: a.name,
  detail: a.detail, kind: "attribute" }));

/**
 * The completion at {@code caret} (spec §3.3): { from, to, items }, from-to the word being typed (a path's last
 * segment only), items filtered by it ignoring case: after a targetAfter word the targets; after "name." the
 * attributes of the target it refers to; in a SELECT, WHERE, ORDER BY or SET clause of a known target its
 * attributes, self, the functions (inserted with "(" and the caret inside) and the other keywords; anywhere else the
 * keywords. null in a string, a number or a parameter, or with nothing to offer.
 */
function completeQuery(text, caret, data) {
  const { v, tokens, target } = read(text, data);
  const current = tokens.find((t) => t.from < caret && caret <= t.to) || null;
  const word = current !== null && WORDS.has(current.kind) ? current : null;
  if (current !== null && word === null && current.kind !== "punct" && current.kind !== "operator") return null;
  const from = word !== null ? word.from : caret;
  const to = word !== null ? word.to : caret;
  let p = -1;
  while (p + 1 < tokens.length && tokens[p + 1].to <= from) p++;
  const previous = tokens[p];
  let items;
  if (previous !== undefined && previous.kind === "punct" && text[previous.from] === "." && previous.to === from) {
    const owner = tokens[p - 1];
    const entry = owner === undefined || owner.to !== previous.from ? null : owner.self ? target
      : owner.attribute !== undefined && owner.attribute.target !== null
        ? v.targets.get(owner.attribute.target) || null : null;
    items = entry !== null ? attributeItems(entry) : [];
  } else if (previous !== undefined && previous.kind === "keyword" && v.targetAfter.has(previous.word)) {
    items = [...v.targets.values()].map((t) => ({ insert: t.name, label: t.name, detail: t.detail, kind: "target" }));
  } else {
    const keywords = v.keywordList.map((k) => ({ insert: k, label: k, detail: "keyword", kind: "keyword" }));
    if (target !== null && EXPRESSION_CLAUSES.has(clauseBefore(v, tokens, p + 1))) {
      const call = text[to] === "(";
      items = [...attributeItems(target),
        { insert: v.self, label: v.self, detail: "the " + target.name + " itself", kind: "keyword" },
        ...v.functionList.map((f) => call ? { insert: f, label: f, detail: "function", kind: "function" }
          : { insert: f + "()", label: f, detail: "function", kind: "function", caret: f.length + 1 }),
        ...keywords.filter((k) => !v.functions.has(k.label) && k.label !== v.self.toUpperCase())];
    } else {
      items = keywords;
    }
  }
  const prefix = text.slice(from, caret).toLowerCase();
  items = items.filter((item) => item.label.toLowerCase().startsWith(prefix));
  return items.length ? { from, to, items } : null;
}

// ------------------------------------------------------------------------------------------------ query: diagnostics

/**
 * The errors of a query (spec §3.4), in the order of the text: an unknown target, an unknown attribute of the target
 * or of the target a reference leads to, a path through an attribute that is no reference, an unterminated string, a
 * parenthesis never closed or closing none. Without a vocabulary, or a known target, no attribute is checked; the
 * grammar never is: the server judges it when the query runs.
 */
function diagnoseQuery(text, data) {
  const out = [];
  const error = (t, message) => out.push({ from: t.from, to: t.to, severity: "error", message });
  const open = [];
  for (const t of read(text, data).tokens) {
    if (t.problem !== undefined) error(t, t.problem);
    if (t.kind === "string" && !isClosed(text, t)) error(t, "unterminated string");
    if (t.kind === "punct" && text[t.from] === "(") open.push(t);
    if (t.kind === "punct" && text[t.from] === ")" && open.pop() === undefined) error(t, "no '(' to close");
  }
  for (const t of open) error(t, "'(' never closed");
  return out.sort((a, b) => a.from - b.from || a.to - b.to);
}

// ------------------------------------------------------------------------------------------------ the query language

/** A query, its data the language a panel publishes (spec §3); every function reads odd data as none. */
const QUERY = Object.freeze({
  id: "query",
  tokenize: (text, data) => read(text, data).tokens.map(({ from, to, kind }) => ({ from, to, kind })),
  diagnose: diagnoseQuery,
  complete: completeQuery,
  pairs: Object.freeze(["()", "''"]),
});

/** The query language (spec §3), with the contract of jsonLanguage plus parameters(text, data). */
export function queryLanguage() {
  return QUERY;
}
