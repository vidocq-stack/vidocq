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

// The Vidocq dev console page. It polls api/snapshot, one complete document per poll, and draws it: the startup
// report first, then one tab per panel, the console's own JVM panel last.
//
// - Every string of the snapshot is panel or report text, which the page does not trust: it reaches the page through
//   textContent, a text node or an attribute value, never as markup. Charts are SVG elements built one by one.
// - One request in flight at most, every console.pollMillis; none while the tab is hidden or the page is paused. A
//   poll that fails greys the last data under "reloading…" and retries every second for a minute, then every five.
// - The history of each value, 300 points, is the SERVER's: it keeps ticking while this tab is hidden, and a poll
//   asks for what this page is missing with ?since=<its newest point>. That is why a tab that comes back after three
//   minutes away draws a complete curve instead of a hole. The page forgets it all when console.boot changes — a new
//   boot, a dev reload included — and then asks for the whole ring again. Time is the server's, never this browser's.
// - localStorage keeps the selected panel, the pause and each panel's open sub-tab, nothing else, and may refuse all.
// - In a dev launch, a panel's actions are buttons. Each sends one same-origin POST, application/json, with the
//   token of the boot the snapshot carries (console.actionToken); a confirmation is asked inline, never with a
//   blocking dialog. The page shows the line the action returned, or the class of what it threw.
// - An action's json argument is a form generated from its JSON Schema when formShape accepts the schema (scalars,
//   and objects of scalars one level down), the code editor of editor.js otherwise, with a "JSON" switch to that
//   editor that keeps the values. The editor colours, checks and completes the JSON against the same schema. A
//   structured answer shows its body, through the JSON viewer (jsonViewer) when it is JSON, and its details folded
//   under "Exchange", through it too. A cell of a sample table column named "replay" that reads as "<action id>
//   <JSON object>" of an action of that panel is a button that fills its form: nothing is sent until the user
//   submits. Any other cell of such a column stays text.
// - A panel whose actions have groups gets sub-tabs: Monitoring, with everything else the panel shows, then one per
//   group, in order of first appearance, which picks one action in a combo and shows its form, its last result apart
//   and the rows of the panel's replay tables that name one of the group's actions. The page keeps, per panel, the
//   open sub-tab, the action picked in each group and the last result of each action (panelState).
// - A text/csv answer is shown as text with a Download button, which saves it in the browser, no request sent. A
//   textarea of a form whose schema says "contentMediaType": "text/csv" gets Choose file, which reads a local file of
//   60 KiB at most into it; nothing is sent until the form is.

import { createEditor, jsonLanguage, FORMAT_EXAMPLES } from "./editor.js";

const HISTORY_POINTS = 300;          // five minutes at one poll per second
const WINDOW_MILLIS = 300_000;       // what a chart shows: the last five minutes
const FAST_RETRY_MILLIS = 1000;
const SLOW_RETRY_MILLIS = 5000;
const FAST_RETRY_FOR_MILLIS = 60_000;
const REQUEST_TIMEOUT_MILLIS = 10_000;
const SVG = "http://www.w3.org/2000/svg";
const PANEL_KEY = "vidocq.devconsole.panel";
const PAUSED_KEY = "vidocq.devconsole.paused";
/** Prefix of the key, by panel id, of a panel's open sub-tab. */
const SUBTAB_KEY = "vidocq.devconsole.subtab.";
/** The id of a panel's Monitoring sub-tab: a group title is never blank, so no group has it. */
const MONITORING = "";
const STARTUP = "startup";
/** The sections the core writes, by id, with the title the page gives them. */
const CORE_SECTIONS = new Map([["launch", "Launch"], ["vidocq", "Vidocq"], ["phases", "Phases"], ["layer", "Layer"],
  ["configuration", "Configuration"], ["extensions", "Extensions"]]);
/** The core sections whose headline only repeats their rows. */
const HEADLINE_REPEATS_ROWS = new Set(["phases"]);
/** The columns of the core sections that are tables. */
const TABLE_HEADS = new Map([["layer", ["module", "location", "kind"]],
  ["extensions", ["priority", "name", "start", "module"]]]);

// ------------------------------------------------------------------------------------------------ what the page keeps

function stored(key) { try { return window.localStorage.getItem(key); } catch (refused) { return null; } }
function store(key, value) { try { window.localStorage.setItem(key, value); } catch (refused) { /* this tab only */ } }

const page = {
  snapshot: null,                     // the last snapshot received
  boot: null,                         // its console.boot
  selected: stored(PANEL_KEY) || STARTUP,
  paused: stored(PAUSED_KEY) === "true",
  failingSince: 0,                    // when the polls started failing, by this page's clock; 0 while they succeed
  inFlight: false,
  timer: 0,
  since: -1,                          // the newest history point this page holds, by the server's clock; -1 for none
  view: null,                         // what the panel area shows: { key, update(snapshot) }
  tabsKey: "",
  openGroups: new Set(),              // the groups whose boot facts are unfolded, by panel and group
  closedGroups: new Set(),
};

/**
 * The history the server sends, by panel, group and key -> { kind, unit, points: [{ t, v, max }] }.
 * The page appends to it what each poll brings and never invents a point of its own.
 */
const history = new Map();
/**
 * What the page keeps of each panel with action groups, by panel id, across polls and redraws (spec §4): the open
 * sub-tab (also in localStorage), the action picked per group, and the last result of each action, which a dev
 * reload that removes the action forgets.
 */
const panelStates = new Map();

function panelState(panelId) {
  let state = panelStates.get(panelId);
  if (!state) {
    state = { tab: stored(SUBTAB_KEY + panelId) || MONITORING, chosen: new Map(), results: new Map() };
    panelStates.set(panelId, state);
  }
  return state;
}

const $ = (id) => document.getElementById(id);
const tabs = $("tabs");
const panelArea = $("panel");

// ------------------------------------------------------------------------------------------------ DOM, text only

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined && text !== null) e.textContent = String(text);
  return e;
}

function svgEl(tag, attributes, cls) {
  const e = document.createElementNS(SVG, tag);
  for (const [name, value] of Object.entries(attributes || {})) e.setAttribute(name, String(value));
  if (cls) e.setAttribute("class", cls);
  return e;
}

/** A value key as a label: "heap.used" reads "heap used". */
const label = (key) => String(key).replace(/[.-]/g, " ");

// ------------------------------------------------------------------------------------------------ numbers

const MIB = 1024 * 1024;
const GIB = 1024 * MIB;
const numbers = new Intl.NumberFormat("en-US", { maximumFractionDigits: 2 });

const count = (v) => numbers.format(v);

function decimals(v) {
  const abs = Math.abs(v);
  return abs < 10 ? v.toFixed(2) : abs < 100 ? v.toFixed(1) : String(Math.round(v));
}

function bytes(b) {
  const abs = Math.abs(b);
  if (abs < 1024) return numbers.format(Math.round(b)) + " B";
  if (abs < MIB) return numbers.format(Math.round(b / 1024)) + " KB";
  if (abs < 10 * GIB) return numbers.format(Math.round(b / MIB)) + " MB";
  return (b / GIB).toFixed(1) + " GB";
}

function percent(ratio) {
  const p = ratio * 100;
  return (p > 0 && p < 1 ? p.toFixed(2) : p.toFixed(1)) + " %";
}

function duration(nanos) {
  const abs = Math.abs(nanos);
  if (abs < 1e3) return Math.round(nanos) + " ns";
  if (abs < 1e6) return Math.round(nanos / 1e3) + " µs";
  if (abs < 1e9) return decimals(nanos / 1e6) + " ms";
  if (abs < 60e9) return decimals(nanos / 1e9) + " s";
  const s = Math.floor(nanos / 1e9);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
  return (h ? h + " h " : "") + (h || m ? m + " min " : "") + r + " s";
}

/**
 * A number in its unit: a value's unit (count, bytes, nanos, ratio), or the unit of a rate: count/s, bytes/s,
 * ratio/s, and share, the rate of a counter of nanoseconds, the share of wall time it measures.
 */
function formatted(v, unit) {
  if (typeof v !== "number" || !Number.isFinite(v)) return "–";
  switch (unit) {
    case "bytes": return bytes(v);
    case "nanos": return duration(v);
    case "ratio": case "share": return percent(v);
    case "count/s": return count(v) + "/s";
    case "bytes/s": return bytes(v) + "/s";
    case "nanos/s": return duration(v) + "/s";
    case "ratio/s": return percent(v) + "/s";
    default: return count(v);
  }
}

/** The unit of the rate of a counter in {@code unit}. */
const rateUnit = (unit) => unit === "nanos" ? "share" : unit + "/s";

// ------------------------------------------------------------------------------------------------ history

const historyKey = (panel, group, key) => panel + "\u0000" + group + "\u0000" + key;

/**
 * Takes in what this poll brought: the points the server had after page.since, appended to what is already drawn,
 * and the new watermark to ask from next time. A panel with no history member leaves its series alone.
 */
function record(snapshot) {
  let newest = page.since;
  for (const panel of snapshot.panels || []) {
    if (!Array.isArray(panel.history)) continue;
    for (const sent of panel.history) {
      const times = sent.t;
      if (!Array.isArray(times) || !Array.isArray(sent.v)) continue;
      const k = historyKey(panel.id, sent.group || "", sent.key);
      let series = history.get(k);
      if (!series) {
        series = { kind: sent.kind, unit: sent.unit, points: [] };
        history.set(k, series);
      }
      series.kind = sent.kind;
      series.unit = sent.unit;
      const ceilings = Array.isArray(sent.max) ? sent.max : null;
      for (let i = 0; i < times.length; i++) {
        const t = times[i];
        if (typeof t !== "number" || t <= page.since) continue;     // never the same point twice
        const v = sent.v[i];
        const max = ceilings && typeof ceilings[i] === "number" ? ceilings[i] : null;
        series.points.push({ t, v: typeof v === "number" ? v : null, max });   // null: a gap, never a zero
        if (t > newest) newest = t;
      }
      if (series.points.length > HISTORY_POINTS) series.points.splice(0, series.points.length - HISTORY_POINTS);
    }
  }
  page.since = newest;
}

/** The value of {@code key} at the tick before this poll, for "+N since last poll", or null. */
function previous(panel, group, key) {
  const series = history.get(historyKey(panel, group, key));
  if (!series || series.points.length < 1) return null;
  return series.points[series.points.length - 1].v;
}

/**
 * The growth per second of a counter between two polls, by the server's clock; a counter that went down started
 * again, and leaves a gap. A counter of nanoseconds grows by the share of wall time it measures.
 */
function rates(points, unit) {
  const out = [];
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1], b = points[i];
    const dt = b.t - a.t;
    let v = null;
    if (a.v !== null && b.v !== null && dt > 0 && b.v >= a.v) {
      v = (b.v - a.v) * 1000 / dt;
      if (unit === "nanos") v /= 1e9;
    }
    out.push({ t: b.t, v });
  }
  return out;
}

// ------------------------------------------------------------------------------------------------ charts

const FILL_COLORS = ["accent", "accent2", "brass", "good"];
/** A max more than ten times above what a chart shows is not its top: a 30 GB heap max would flatten 50 MB used. */
const CEILING_REACH = 10;

/** The data a chart plots in one scope: per series, its unit and its points, or its ceiling. */
function chartData(chart, panel, group) {
  return chart.series.map((series) => {
    const h = history.get(historyKey(panel, group, series.key));
    const empty = { series, unit: null, points: [], ceiling: null };
    if (!h) return empty;
    if (series.style === "rate") {
      return h.kind === "counter" ? { ...empty, unit: rateUnit(h.unit), points: rates(h.points, h.unit) } : empty;
    }
    if (h.kind !== "gauge") return empty;
    if (series.style === "ceiling") {
      for (let i = h.points.length - 1; i >= 0; i--) {
        if (h.points[i].max !== null) return { ...empty, unit: h.unit, ceiling: h.points[i].max };
      }
      return empty;
    }
    return { ...empty, unit: h.unit, points: h.points };
  });
}

/** A round top for an axis: 1, 2, 2.5, 4, 5 or 8 times a power of ten, of the unit it is read in. */
function niceTop(v, unit) {
  let scale = 1;
  if (unit === "bytes" || unit === "bytes/s") scale = v >= 10 * GIB ? GIB : v >= MIB ? MIB : v >= 1024 ? 1024 : 1;
  const x = v / scale;
  if (!(x > 0)) return unit === "ratio" || unit === "share" ? 0.01 : 1;
  const exp = Math.pow(10, Math.floor(Math.log10(x)));
  for (const m of [1, 2, 2.5, 4, 5, 8, 10]) if (m * exp >= x - 1e-12) return m * exp * scale;
  return 10 * exp * scale;
}

/** The segments of a series to draw: its points in the window, cut where a value is absent or polls were missed. */
function segments(points, start, gap) {
  const out = [];
  let current = [];
  let last = null;
  for (const p of points) {
    if (p.t < start) continue;
    if (p.v === null || (last !== null && p.t - last > gap)) {
      if (current.length) out.push(current);
      current = [];
    }
    if (p.v !== null) current.push(p);
    last = p.t;
  }
  if (current.length) out.push(current);
  return out;
}

function pathOf(points, x, y) {
  return points.map((p, i) => (i ? "L" : "M") + x(p.t).toFixed(1) + "," + y(p.v).toFixed(1)).join("");
}

/** A chart's frame: its title, a note, the SVG, its legend. */
function chartBox(chart) {
  const box = el("div", "chart");
  const head = el("div", "ct");
  const note = el("span");
  head.append(el("b", null, chart.title), note);
  const svg = svgEl("svg", { role: "img", "aria-label": chart.title, preserveAspectRatio: "none" });
  const legend = el("div", "legend");
  box.append(head, svg, legend);
  return { chart, box, svg, note, legend };
}

/** Draws {@code box.chart} for one scope of one panel, from the history, by the server's time {@code now}. */
function drawChart(box, panel, group, now, pollMillis) {
  const { svg, note, legend } = box;
  const data = chartData(box.chart, panel, group);
  const w = Math.max(260, Math.round(svg.getBoundingClientRect().width || 520));
  const h = 150, T = 8, B = 20;
  const start = now - WINDOW_MILLIS;
  const gap = Math.max(3 * pollMillis, 3000);

  // what is stacked on what: the running top of the AREA and STACKED series, by time
  let stack = new Map();
  for (const d of data) {
    if (d.series.style === "area") {
      stack = new Map(d.points.filter((p) => p.v !== null).map((p) => [p.t, p.v]));
      d.base = null;
    } else if (d.series.style === "stacked") {
      const base = stack;
      d.base = base;
      d.points = d.points.map((p) => ({ t: p.t, v: p.v === null || !base.has(p.t) ? null : base.get(p.t) + p.v }));
      stack = new Map(d.points.filter((p) => p.v !== null).map((p) => [p.t, p.v]));
    }
  }

  // one scale per unit: the first on the left axis, the second on the right one
  const units = [...new Set(data.filter((d) => d.unit).map((d) => d.unit))];
  const scales = new Map();
  for (const unit of units) {
    let highest = 0, limit = 0;
    for (const d of data) {
      if (d.unit !== unit) continue;
      if (d.series.style === "ceiling") { limit = Math.max(limit, d.ceiling || 0); continue; }
      for (const p of d.points) {
        if (p.t < start || p.v === null) continue;
        highest = Math.max(highest, p.v);
        if (d.series.style !== "rate" && p.max !== null && p.max !== undefined) limit = Math.max(limit, p.max);
      }
    }
    // up to the max the values can reach, unless it is so far above them that they would draw a flat line
    const top = limit > 0 && limit >= highest && highest * CEILING_REACH >= limit ? limit
      : niceTop(highest * 1.08, unit);
    scales.set(unit, top);
  }
  const L = 62, R = units.length > 1 ? 62 : 12;
  const iw = w - L - R, ih = h - T - B;
  const x = (t) => L + iw * (t - start) / WINDOW_MILLIS;
  const yOf = (unit) => (v) => T + ih - (Math.max(0, Math.min(v, scales.get(unit))) / scales.get(unit)) * ih;

  const nodes = [];
  units.slice(0, 2).forEach((unit, side) => {
    const top = scales.get(unit);
    for (const tick of [0, top / 2, top]) {
      const yy = yOf(unit)(tick);
      if (side === 0) nodes.push(svgEl("line", { x1: L, x2: w - R, y1: yy.toFixed(1), y2: yy.toFixed(1) }, "grid"));
      const text = svgEl("text", side === 0
        ? { x: L - 7, y: (yy + 3.5).toFixed(1), "text-anchor": "end" }
        : { x: w - R + 7, y: (yy + 3.5).toFixed(1), "text-anchor": "start" }, "axis");
      text.textContent = formatted(tick, unit);
      nodes.push(text);
    }
  });
  for (const [xx, text, anchor] of [[L, "−5 min", "start"], [L + iw / 2, "−2:30", "middle"], [w - R, "now", "end"]]) {
    const t = svgEl("text", { x: xx, y: h - 4, "text-anchor": anchor }, "axis");
    t.textContent = text;
    nodes.push(t);
  }

  // the series, with a color each, and the legend
  const hasFill = data.some((d) => d.series.style === "area" || d.series.style === "stacked");
  const lineColors = hasFill ? ["brass", "crit", "accent", "good"] : ["accent", "brass", "crit", "good"];
  let fills = 0, lines = 0, drawn = false, ceilingNote = null;
  const legendItems = [];
  for (const d of data) {
    const style = d.series.style;
    const key = d.series.key;
    if (style === "ceiling") {
      if (d.ceiling !== null && d.unit && d.ceiling <= scales.get(d.unit)) {
        const yy = yOf(d.unit)(d.ceiling).toFixed(1);
        nodes.push(svgEl("line", { x1: L, x2: w - R, y1: yy, y2: yy }, "ceiling"));
      }
      if (d.ceiling !== null && d.unit) ceilingNote = "max " + formatted(d.ceiling, d.unit);
      legendItems.push(["ceiling", "max", d.ceiling !== null ? formatted(d.ceiling, d.unit) : "–"]);
      continue;
    }
    const filled = style === "area" || style === "stacked";
    const color = filled ? FILL_COLORS[fills++ % FILL_COLORS.length] : lineColors[lines++ % lineColors.length];
    let latest = null;
    if (d.unit) {
      const y = yOf(d.unit);
      for (const part of segments(d.points, start, gap)) {
        drawn = true;
        const line = pathOf(part, x, y);
        if (style === "area") {
          const first = part[0], last = part[part.length - 1];
          nodes.push(svgEl("path", { d: line + "L" + x(last.t).toFixed(1) + "," + y(0).toFixed(1) + "L"
            + x(first.t).toFixed(1) + "," + y(0).toFixed(1) + "Z" }, "fill-" + color));
          nodes.push(svgEl("path", { d: line }, "line-" + color));
        } else if (style === "stacked") {
          let back = "";
          for (let i = part.length - 1; i >= 0; i--) {
            const base = d.base.get(part[i].t) || 0;
            back += "L" + x(part[i].t).toFixed(1) + "," + y(base).toFixed(1);
          }
          nodes.push(svgEl("path", { d: line + back + "Z" }, "fill-" + color));
        } else {
          nodes.push(svgEl("path", { d: line }, "line-" + color));
        }
      }
      const points = d.points;
      const end = points.length ? points[points.length - 1] : null;
      if (end && end.v !== null && end.t >= start && style !== "stacked") {
        nodes.push(svgEl("circle", { cx: x(end.t).toFixed(1), cy: y(end.v).toFixed(1), r: 3 }, "end-" + color));
      }
      if (end && end.v !== null) {
        // the legend shows the value itself, not what it is stacked on
        latest = style === "stacked" ? end.v - (d.base.get(end.t) || 0) : end.v;
      }
    }
    legendItems.push([color, label(key), latest === null ? "–" : formatted(latest, d.unit)]);
  }
  if (!drawn) {
    const waiting = svgEl("text", { x: L + iw / 2, y: T + ih / 2, "text-anchor": "middle" }, "waiting");
    waiting.textContent = data.some((d) => d.series.style === "rate") ? "a rate needs two polls…" : "no value yet";
    nodes.push(waiting);
  }

  svg.setAttribute("viewBox", "0 0 " + w + " " + h);
  svg.replaceChildren(...nodes);
  note.textContent = ceilingNote !== null ? ceilingNote
    : data.every((d) => d.series.style === "rate") ? "per second"
      : units.length === 1 && units[0] === "ratio" && scales.get("ratio") === 1 ? "0–100 %" : "";
  legend.replaceChildren(...legendItems.map(([color, text, value]) => {
    const item = el("span");
    item.append(el("i", "sw-" + color), text + " ", el("b", null, value));
    return item;
  }));
}

// ------------------------------------------------------------------------------------------------ building blocks

/** A tile for one value: its label, the value, how much it grew since the last poll, a fill bar for a max. */
function tile(key) {
  const root = el("div", "tile");
  const k = el("div", "k");
  k.append(el("span", null, label(key)));
  const v = el("div", "v", "–");
  const delta = el("div", "delta");
  delta.hidden = true;
  const bar = el("div", "bar");
  const fill = el("i");
  bar.append(fill);
  bar.hidden = true;
  root.append(k, v, delta, bar);
  return { root, v, delta, bar, fill };
}

function setTile(t, value, before) {
  t.v.replaceChildren();
  t.v.className = "v";
  t.bar.hidden = true;
  t.delta.hidden = true;
  switch (value.kind) {
    case "gauge": {
      t.v.append(formatted(value.value, value.unit));
      if (typeof value.max === "number" && value.max > 0) {
        if (!(value.unit === "ratio" && value.max === 1)) {
          t.v.append(el("small", null, " of " + formatted(value.max, value.unit)));
        }
        const ratio = Math.max(0, Math.min(1, value.value / value.max));
        t.bar.hidden = false;
        t.fill.style.width = (ratio * 100).toFixed(1) + "%";
        t.fill.className = ratio >= 0.9 ? "hot" : "";
      }
      break;
    }
    case "counter":
      t.v.append(formatted(value.value, value.unit));
      if (typeof before === "number") {
        const grown = value.value - before;
        t.delta.textContent = grown >= 0 ? "+" + formatted(grown, value.unit) + " since last poll"
          : "started again since last poll";
        t.delta.hidden = false;
      }
      break;
    case "duration":
      t.v.append(duration(value.nanos));
      break;
    case "text":
      t.v.className = "v text";
      t.v.append(value.value === null || value.value === undefined ? "" : String(value.value));
      break;
    default:
      t.v.append(el("span", "absent", value.reason || "not available"));
  }
}

/**
 * A table of a sample, its columns and rows; a cell of a REPLAY_COLUMN column that replays an action is drawn as a
 * Replay button, and that column's header left blank when at least one cell is. {@code keep}, when given, keeps only
 * the rows whose replay cell names an action it accepts, by id, as a group tab does; with no row kept, the table is
 * a line that says so.
 */
function sampleTable(value, panelId, keep) {
  const table = el("table", "ext");
  const columns = value.columns || [];
  const replayAt = columns.indexOf(REPLAY_COLUMN);
  const body = el("tbody");
  let buttons = 0;
  let kept = 0;
  for (const row of value.rows || []) {
    if (keep && !(replayAt >= 0 && keep(replayTarget(row[replayAt])))) continue;
    kept++;
    const tr = el("tr");
    row.forEach((cell, i) => {
      const button = i === replayAt ? replayButton(panelId, cell) : null;
      if (button) {
        buttons++;
        const td = el("td");
        td.append(button);
        tr.append(td);
      } else {
        tr.append(el("td", /^\d+$/.test(cell) ? "n" : null, cell));
      }
    });
    body.append(tr);
  }
  if (keep && !kept) return el("p", "absent", "No call yet");
  const head = el("tr");
  columns.forEach((column, i) => head.append(el("th", null, i === replayAt && buttons ? "" : column)));
  const thead = el("thead");
  thead.append(head);
  table.append(thead, body);
  const scroll = el("div", "scroll");
  scroll.append(table);
  return scroll;
}

/**
 * Puts {@code next}, a table or the line that stands for it, in {@code holder} on every poll. A table goes into the
 * scrolling box already there rather than a new one: its horizontal scroll, and a drag of its scrollbar under way,
 * survive the poll instead of jumping back to the left.
 */
function redrawTable(holder, next) {
  const box = holder.childElementCount === 1 ? holder.firstElementChild : null;
  if (box && box.classList.contains("scroll") && next.classList.contains("scroll")) {
    const left = box.scrollLeft;
    box.replaceChildren(...next.childNodes);
    box.scrollLeft = left;
    return;
  }
  holder.replaceChildren(next);
}

/**
 * A link the server vouched for: an absolute http or https URL, opened in a new tab that cannot reach this page.
 * Anything else stays text: the page never builds a link from a string a panel wrote.
 */
function linkTo(href, text) {
  if (typeof href !== "string" || !/^https?:\/\//.test(href)) return document.createTextNode(text);
  const a = el("a", "link", text);
  a.href = href;
  a.target = "_blank";
  a.rel = "noopener noreferrer";
  return a;
}

/**
 * The links a panel declared, such as the Swagger UI, as buttons under its title: a named line with an href. A route
 * that can be opened stays in its table, where its method and handler say what it is.
 */
function openButtons(lines) {
  const links = (lines || []).map(splitLine).filter((line) => line.href && typeof line.key === "string");
  if (!links.length) return null;
  const bar = el("div", "opens");
  for (const link of links) {
    const button = linkTo(link.href, link.key);
    if (button.nodeType === Node.ELEMENT_NODE) {
      button.className = "open";
      button.title = link.href;
    }
    bar.append(button);
  }
  return bar;
}

// ------------------------------------------------------------------------------------------------ actions

/** The time of an outcome, by the server's clock, as the reader's local time of day. */
const clockTime = (t) => typeof t === "number" ? new Date(t).toLocaleTimeString() : "";

/** Past this many actions, a panel's actions get a text filter. */
const FILTER_FROM = 10;
/** The header of a sample table column whose cells replay an action: PanelSample.REPLAY_COLUMN. */
const REPLAY_COLUMN = "replay";
/** What a panel writes in place of a secret: a replay leaves it for the user to type again. */
const MASKED = "***";
/** The rows of the actions on the page, by panel and action: what a Replay button fills. */
const actionRows = new Map();
const actionKey = (panelId, actionId) => panelId + "\u0000" + actionId;
const isObject = (v) => v !== null && typeof v === "object" && !Array.isArray(v);

/**
 * The panel's own action bar, in a dev launch only (the snapshot then carries console.actionToken): the ungrouped
 * actions, one form each, a text filter above them past FILTER_FROM. A confirmation is asked inline, never with the
 * browser's blocking dialog. The request is a same-origin fetch with the token of the boot.
 */
function actionsBar(rows) {
  if (!rows.length) return null;
  const bar = el("div", "actions");
  if (rows.length > FILTER_FROM) {
    const filter = el("input", "action-filter");
    filter.type = "search";
    filter.placeholder = "Filter " + rows.length + " actions";
    filter.autocomplete = "off";
    filter.spellcheck = false;
    filter.addEventListener("input", () => {
      const words = filter.value.trim().toLowerCase();
      for (const row of rows) row.root.hidden = words !== "" && !row.text.includes(words);
    });
    bar.append(filter);
  }
  bar.append(...rows.map((row) => row.root));
  return bar;
}

/** A string argument: a list when the server named the values it accepts, a text field otherwise. */
function stringField(argument) {
  const wrap = el("label", "arg");
  wrap.append(el("span", null, argument.label || argument.name));
  let input;
  if (Array.isArray(argument.allowed)) {
    input = el("select");
    for (const value of argument.allowed) {
      const option = el("option", null, value);
      option.value = value;
      input.append(option);
    }
  } else {
    input = el("input");
    input.type = "text";
    input.maxLength = 200;
    input.autocomplete = "off";
    input.spellcheck = false;
  }
  input.name = argument.name;
  wrap.append(input);
  return {
    name: argument.name,
    root: wrap,
    value: () => input.value,
    fill(v) { if (typeof v === "string" && v !== MASKED) input.value = v; },
    disable(on) { input.disabled = on; },
  };
}

const SKELETON = new Map([["string", ""], ["number", 0], ["integer", 0], ["boolean", false], ["array", []],
  ["object", {}]]);
const SCALAR_TYPES = new Set(["string", "number", "integer", "boolean"]);
/** What a schema of a form's root may not use: the form would not say what it means. */
const NOT_FORM = ["$ref", "anyOf", "oneOf", "allOf", "not"];
/** What a scalar field's schema may not use. */
const NOT_SCALAR = ["$ref", "properties", "items", "anyOf", "oneOf", "allOf", "not", "patternProperties"];
/** What a nested object's schema may not use: it lists its properties, and nothing else says what it holds. */
const NOT_NESTED = ["$ref", "items", "anyOf", "oneOf", "allOf", "not", "patternProperties"];

/** The kind of a form field of property schema {@code p}: its scalar type, "enum" (of strings), or null. */
function scalarKind(p) {
  if (!isObject(p) || NOT_SCALAR.some((k) => k in p)) return null;
  if (Array.isArray(p.enum)) {
    return (p.type === undefined || p.type === "string") && p.enum.length > 0
      && p.enum.every((v) => typeof v === "string") ? "enum" : null;
  }
  return SCALAR_TYPES.has(p.type) ? p.type : null;
}

/**
 * The generated form of a json argument's schema (editor spec §5), the one place its rule is written: its fields, or
 * null for the JSON editor. The root is "type": "object", with no $ref, anyOf, oneOf, allOf or not; each property is
 * a string, a number, an integer or a boolean, or an enum of strings, or an object ("type": "object") whose own
 * properties all are such scalars, with no $ref and no deeper nesting. A field is { name, kind, definition, required,
 * readOnly, fields }: kind a scalar type, "enum" or "object"; fields, a nested object's own. A readOnly property is
 * never required.
 */
function formShape(schema) {
  if (!isObject(schema) || schema.type !== "object" || NOT_FORM.some((k) => k in schema)) return null;
  return formFields(schema, true);
}

/** The fields of object schema {@code schema}, or null when a property cannot be one; nested: one more level. */
function formFields(schema, nested) {
  if (schema.properties === undefined) return [];
  if (!isObject(schema.properties)) return null;
  const required = new Set(Array.isArray(schema.required) ? schema.required.filter((n) => typeof n === "string")
    : []);
  const fields = [];
  for (const [name, p] of Object.entries(schema.properties)) {
    const readOnly = isObject(p) && p.readOnly === true;
    const field = { name, kind: scalarKind(p), definition: p, required: !readOnly && required.has(name), readOnly,
      fields: null };
    if (field.kind === null) {
      if (!nested || !isObject(p) || p.type !== "object" || !isObject(p.properties)
        || NOT_NESTED.some((k) => k in p)) return null;
      field.kind = "object";
      field.fields = formFields(p, false);
      if (field.fields === null) return null;
    }
    fields.push(field);
  }
  return fields;
}

/**
 * The JSON editor's first value: the required properties, each with its default, or an empty value of its type, a
 * nested object with its own required properties. A property named "__proto__" is a schema-declared name like any
 * other: a bare {} would silently drop a write to that key (or repoint the object's own prototype) instead of
 * storing it, so the object is prototype-less.
 */
function skeleton(schema) {
  const object = Object.create(null);
  const properties = isObject(schema) && isObject(schema.properties) ? schema.properties : {};
  const required = isObject(schema) && Array.isArray(schema.required) ? schema.required : [];
  for (const name of required) {
    if (typeof name !== "string") continue;
    const p = Object.hasOwn(properties, name) && isObject(properties[name]) ? properties[name] : {};
    object[name] = p.default !== undefined ? p.default
      : Array.isArray(p.enum) && p.enum.length ? p.enum[0]
      : p.type === "object" ? skeleton(p)
      : SKELETON.has(p.type) ? structuredClone(SKELETON.get(p.type)) : null;
  }
  return object;
}

/** Whether {@code values} holds a member a panel masked, in it or in an object it holds. */
const hasMasked = (values) => Object.values(values).some((v) => v === MASKED || (isObject(v) && hasMasked(v)));

/** {@code values} without the members a panel masked, at every depth of its objects: the user types those again. */
const unmasked = (values) => Object.fromEntries(Object.entries(values).filter(([, v]) => v !== MASKED)
  .map(([k, v]) => [k, isObject(v) ? unmasked(v) : v]));

/** Past this size a chosen file is not read: the console takes a request of 64 KiB at most. */
const MAX_FILE_BYTES = 60 * 1024;

/**
 * A "Choose file" input for a textarea of CSV text (a string property of "contentMediaType": "text/csv"): the file
 * chosen is read in this browser and replaces the textarea's value; nothing is sent until the form is. It is read as
 * UTF-8, then again as Windows-1252 when that shows a replacement character (a spreadsheet of a decimal-comma locale
 * saves CSV so), the line under the input saying so. A file larger than 60 KiB is not read. The input is cleared after
 * each choice, so the same file can be chosen again; a read overtaken by a newer one, or ending while the form is
 * sent, is dropped. text() is what the form sends: the file's own text, its line ends kept, while the textarea still
 * shows it (a textarea turns \r\n into \n), else the textarea's value.
 */
function fileChooser(target) {
  const root = el("span", "file-choice");
  const file = el("input");
  file.type = "file";
  file.accept = ".csv,text/csv";
  const note = el("span", "file-note");
  let reads = 0;
  let raw = null;                        // the text of the file last read, as read
  file.addEventListener("change", () => {
    note.textContent = "";
    const chosen = file.files && file.files[0];
    file.value = "";
    if (!chosen) return;
    if (chosen.size > MAX_FILE_BYTES) {
      note.textContent = "the file is larger than 60 KiB";
      return;
    }
    const token = ++reads;
    const read = (encoding) => {
      const reader = new FileReader();
      reader.addEventListener("load", () => {
        if (token !== reads) return;
        if (file.disabled) {
          note.textContent = "the form was sent before the file was read: choose it again";
          return;
        }
        const text = typeof reader.result === "string" ? reader.result : "";
        if (encoding === "UTF-8" && text.includes("\uFFFD")) {
          read("windows-1252");
          return;
        }
        raw = text;
        target.value = raw;
        if (encoding !== "UTF-8") note.textContent = "read as Windows-1252 (not UTF-8)";
      });
      reader.addEventListener("error", () => {
        if (token === reads) note.textContent = "the file could not be read";
      });
      reader.readAsText(chosen, encoding);
    };
    read("UTF-8");
  });
  root.append(file, note);
  return {
    root,
    file,
    text: () => raw !== null && target.value === raw.replace(/\r\n?/g, "\n") ? raw : target.value,
  };
}

/**
 * A json argument: a form generated from its schema when formShape accepts it, using required, readOnly, default,
 * description, enum, a string's "format" (a placeholder of its shape; "textarea", a field of several lines, with
 * Choose file when its "contentMediaType" is "text/csv"), a nested object as a fieldset; the code editor of editor.js
 * otherwise, checking and completing against the schema, starting from the required properties. A "JSON" switch shows
 * the form's value in that editor; switching back keeps the values. value() returns the JSON text sent, or throws
 * what is wrong: the editor's own text once it parses as an object, so that an id past 2^53 reaches the server as
 * typed.
 */
function jsonField(argument) {
  const root = el("div", "json-arg");
  const schema = argument.schema;
  const name = argument.label || argument.name;
  const shape = formShape(schema);
  const head = el("div", "json-head");
  head.append(el("span", "json-label", name));
  const note = el("span", "json-note");
  const editor = createEditor({ language: jsonLanguage, data: schema, value: JSON.stringify(skeleton(schema), null, 2),
    rows: 8, label: name });
  editor.textarea.name = argument.name;
  const form = el("div", "json-form");
  const inputs = new Map();             // by field of the shape: { input, kind, text }
  const choosers = [];                  // the file inputs of the CSV fields, disabled with the form
  const raw = el("input");
  raw.type = "checkbox";

  /** The label and input of field {@code f}, {@code path} its name from the root, "entity.title". */
  function field(f, path) {
    const definition = f.definition;
    const kind = f.kind;
    const wrap = el("label", "arg");
    wrap.append(el("span", null, f.name + (f.required ? " *" : "") + (f.readOnly ? " (generated)" : "")));
    let input;
    let chooser = null;
    if (kind === "enum" || kind === "boolean") {
      input = el("select");
      for (const v of ["", ...(kind === "enum" ? definition.enum : ["true", "false"])]) {
        const option = el("option", null, v === "" ? "–" : v);
        option.value = v;
        input.append(option);
      }
    } else if (kind === "string" && definition.format === "textarea") {
      // A text of several lines, such as a query: a textarea, read, filled and sent as an input is.
      input = el("textarea", "json-text");
      input.rows = 4;
      input.spellcheck = false;
      wrap.classList.add("wide");
      if (definition.contentMediaType === "text/csv") chooser = fileChooser(input);
    } else {
      input = el("input");
      input.type = "text";
      input.autocomplete = "off";
      input.spellcheck = false;
      if (kind !== "string") input.inputMode = "decimal";
      input.placeholder = f.readOnly ? "generated" : FORMAT_EXAMPLES.get(definition.format) || "";
    }
    if (definition.default !== undefined && definition.default !== null) input.value = String(definition.default);
    if (typeof definition.description === "string") input.title = definition.description;
    input.name = argument.name + "." + path;
    wrap.append(input);
    if (chooser) {
      wrap.append(chooser.root);
      choosers.push(chooser.file);
    }
    inputs.set(f, { input, kind, text: chooser ? chooser.text : null });
    return wrap;
  }

  if (shape) {
    for (const f of shape) {
      if (f.kind !== "object") {
        form.append(field(f, f.name));
        continue;
      }
      // A nested object: a fieldset of its own fields, its name the legend.
      const group = el("fieldset", "json-group");
      group.append(el("legend", null, f.name + (f.required ? " *" : "") + (f.readOnly ? " (generated)" : "")));
      for (const inner of f.fields) group.append(field(inner, f.name + "." + inner.name));
      form.append(group);
    }
    const toggle = el("label", "json-switch");
    toggle.append(raw, el("span", null, "JSON"));
    head.append(toggle);
  }
  head.append(note);
  root.append(head);
  if (shape) root.append(form);
  root.append(editor.root);
  const rawMode = () => !shape || raw.checked;
  const show = () => { form.hidden = rawMode(); editor.root.hidden = !rawMode(); };
  show();

  /**
   * The values of {@code fields} as an object, {@code prefix} the path of that object for a refusal; strict, a number
   * that is none is refused. A nested object neither required nor filled in is left out; one that is, its missing
   * required fields refused by their path. A property literally named "__proto__" is a name like any other: a bare
   * {} would silently drop the write, which would then make it forever "required" instead of present.
   */
  function valuesOf(fields, prefix, strict) {
    const object = Object.create(null);
    for (const f of fields) {
      const property = f.name;
      if (f.kind === "object") {
        const inner = valuesOf(f.fields, prefix + property + ".", strict);
        if (!f.required && Object.keys(inner).length === 0) continue;
        if (strict) requireAll(f.fields, inner, prefix + property + ".");
        object[property] = inner;
        continue;
      }
      const { input, kind, text: fileText } = inputs.get(f);
      const text = input.value.trim();
      if (text === "") continue;
      if (kind === "integer" || kind === "number") {
        const n = Number(text);
        const valid = kind === "integer" ? /^-?\d+$/.test(text) : Number.isFinite(n);
        if (!valid && strict) {
          throw new Error(prefix + property + ": not " + (kind === "integer" ? "an integer" : "a number"));
        }
        object[property] = valid ? n : text;
      } else if (kind === "boolean") {
        object[property] = text === "true";
      } else {
        object[property] = fileText ? fileText() : input.value;
      }
    }
    return object;
  }
  /** Refuses the first required field of {@code fields} that {@code object} lacks: "entity.title is required". */
  function requireAll(fields, object, prefix) {
    for (const f of fields) {
      if (f.required && !Object.hasOwn(object, f.name)) throw new Error(prefix + f.name + " is required");
    }
  }
  function formObject(strict) {
    const object = valuesOf(shape, "", strict);
    if (strict) requireAll(shape, object, "");
    return object;
  }
  function editorObject() {
    let value;
    try { value = JSON.parse(editor.value()); } catch (unparsable) { throw new Error(name + ": not valid JSON"); }
    if (!isObject(value)) throw new Error(name + ": not a JSON object");
    return value;
  }
  function toForm(object, fields) {
    for (const f of fields) {
      const property = f.name;
      // Object.hasOwn: a property object lacks, such as "constructor" or "toString", must read as absent, never
      // as the inherited member of that name.
      const v = isObject(object) && Object.hasOwn(object, property) ? object[property] : undefined;
      if (f.kind === "object") {
        toForm(v, f.fields);
        continue;
      }
      inputs.get(f).input.value = v === undefined || v === null ? "" : typeof v === "object" ? JSON.stringify(v)
        : String(v);
    }
  }
  raw.addEventListener("change", () => {
    note.textContent = "";
    if (raw.checked) {
      editor.setValue(JSON.stringify(formObject(false), null, 2));
    } else {
      try { toForm(editorObject(), shape); } catch (invalid) { raw.checked = true; note.textContent = invalid.message; }
    }
    show();
  });
  return {
    name: argument.name,
    root,
    value() {
      if (!rawMode()) return JSON.stringify(formObject(true));
      editorObject();                   // refuses a text that is no JSON object, as before
      return editor.value();
    },
    fill(values) {
      if (!isObject(values)) return;
      const kept = unmasked(values);
      editor.setValue(JSON.stringify(kept, null, 2));
      if (shape) toForm(kept, shape);
      note.textContent = hasMasked(values) ? "masked values: type them again" : "";
    },
    disable(on) {
      editor.disable(on);
      for (const c of [raw, ...[...inputs.values()].map((i) => i.input), ...choosers]) c.disabled = on;
    },
  };
}

// ------------------------------------------------------------------------------------------------ JSON viewer

/** Past this many values, a JSON document opens its first level only. */
const JSON_BIG = 500;
/** The key, in a viewer's node state, of what Expand all or Collapse all last set for every node. */
const ALL_NODES = "\u0001all";

/**
 * An integer of a JSON document past what a double holds exactly, such as 9007199254740993: the viewer shows and
 * copies the text the server sent, never the nearest double.
 */
class JsonNumber {
  constructor(source) { this.source = source; }
}

/** Whether {@code v} is an object or an array of a parsed document, which the viewer folds. */
const isContainer = (v) => v !== null && typeof v === "object" && !(v instanceof JsonNumber);

/**
 * {@code text} parsed, an integer past 2^53 kept as its text where the browser gives the source of a value;
 * undefined when it is not JSON. A key "__proto__" is an own property, as JSON.parse always makes it.
 */
function parseJson(text) {
  try {
    return JSON.parse(text, (key, value, context) => typeof value === "number" && !Number.isSafeInteger(value)
      && context && typeof context.source === "string" && /^-?\d+$/.test(context.source)
      ? new JsonNumber(context.source) : value);
  } catch (notJson) {
    return undefined;
  }
}

/** {@code doc} as indented JSON text, a kept integer written as the server sent it where the browser can. */
function jsonText(doc) {
  return JSON.stringify(doc, (key, value) => !(value instanceof JsonNumber) ? value
    : typeof JSON.rawJSON === "function" ? JSON.rawJSON(value.source) : Number(value.source), 2);
}

/** How many values {@code doc} holds, counting stopped past {@code limit}. */
function countValues(doc, limit) {
  let n = 0;
  const stack = [doc];
  while (stack.length && n <= limit) {
    const v = stack.pop();
    n++;
    if (isContainer(v)) for (const child of Object.values(v)) stack.push(child);
  }
  return n;
}

/**
 * A JSON document as a tree (spec §3): keys, strings, numbers and literals coloured, every object and array folding
 * under a ▾/▸ toggle or a click on its summary, Alt+click flipping everything under it too. The first two levels
 * start open, only the first past JSON_BIG values. {@code nodes} holds the fold state by node path, so that a redraw
 * of the same result keeps it; a new result brings a new map. Built with DOM calls and textContent only. null when
 * {@code text} is not JSON.
 */
function jsonViewer(text, nodes) {
  const doc = parseJson(text);
  if (doc === undefined) return null;
  const openDepth = countValues(doc, JSON_BIG) > JSON_BIG ? 1 : 2;
  const root = el("div", "jv");
  const isOpen = (path, depth) => nodes.has(path) ? nodes.get(path)
    : nodes.has(ALL_NODES) ? nodes.get(ALL_NODES) : depth < openDepth;

  /** Sets every object and array under {@code value}, itself included, to {@code open}. */
  function setDeep(value, path, open) {
    const stack = [[value, path]];
    while (stack.length) {
      const [v, p] = stack.pop();
      if (!isContainer(v)) continue;
      nodes.set(p, open);
      for (const [k, child] of Object.entries(v)) stack.push([child, p + "\u0000" + k]);
    }
  }

  function leaf(value) {
    if (value instanceof JsonNumber) return el("span", "jv-number", value.source);
    if (Array.isArray(value)) return el("span", "jv-punct", "[]");
    if (isContainer(value)) return el("span", "jv-punct", "{}");
    if (value === null) return el("span", "jv-literal", "null");
    if (typeof value === "string") return el("span", "jv-string", JSON.stringify(value));
    if (typeof value === "number") return el("span", "jv-number", String(value));
    return el("span", "jv-literal", String(value));
  }

  /** One value: {@code key} its name in its object, null in an array or at the root; {@code tail} its comma. */
  function node(value, key, path, depth, tail) {
    const head = key === null ? [] : [el("span", "jv-key", JSON.stringify(key)), el("span", "jv-punct", ": ")];
    const entries = isContainer(value) ? Object.entries(value) : [];
    if (!entries.length) {
      const line = el("div", "jv-line");
      line.append(el("span", "jv-gap"), ...head, leaf(value));
      if (tail) line.append(el("span", "jv-punct", tail));
      return line;
    }
    const array = Array.isArray(value);
    const box = el("div", "jv-node");
    const line = el("div", "jv-line");
    const toggle = el("button", "jv-toggle");
    toggle.type = "button";
    const opening = el("span", "jv-punct", array ? "[" : "{");
    const summary = el("span", "jv-summary", array ? "[…] " + plural(entries.length, "item", "items")
      : "{…} " + plural(entries.length, "key", "keys"));
    const summaryTail = el("span", "jv-punct", tail);
    line.append(toggle, ...head, opening, summary, summaryTail);
    const children = el("div", "jv-children");
    const closing = el("div", "jv-line");
    closing.append(el("span", "jv-gap"), el("span", "jv-punct", (array ? "]" : "}") + tail));
    box.append(line, children, closing);
    let built = false;
    const show = (open) => {
      if (open && !built) {
        built = true;       // children are drawn the first time they are shown: a folded big document costs little
        entries.forEach(([k, child], i) => children.append(node(child, array ? null : k, path + "\u0000" + k,
          depth + 1, i < entries.length - 1 ? "," : "")));
      }
      toggle.textContent = open ? "▾" : "▸";
      toggle.setAttribute("aria-expanded", String(open));
      toggle.setAttribute("aria-label", open ? "Collapse" : "Expand");
      opening.hidden = !open;
      children.hidden = !open;
      closing.hidden = !open;
      summary.hidden = open;
      summaryTail.hidden = open;
    };
    const flip = (event) => {
      const open = !isOpen(path, depth);
      if (event.altKey) {
        setDeep(value, path, open);
        box.replaceWith(node(value, key, path, depth, tail));
        return;
      }
      nodes.set(path, open);
      show(open);
    };
    toggle.addEventListener("click", flip);
    summary.addEventListener("click", flip);
    show(isOpen(path, depth));
    return box;
  }

  const draw = () => root.replaceChildren(node(doc, null, "", 0, ""));
  draw();
  return {
    root,
    expandAll() { nodes.clear(); nodes.set(ALL_NODES, true); draw(); },
    collapseAll() { nodes.clear(); nodes.set(ALL_NODES, false); draw(); },
    text: () => jsonText(doc),
  };
}

/** Expand all, Collapse all and Copy, for {@code viewer}; a refused clipboard is said on the button, nothing more. */
function viewerTools(viewer) {
  const tools = el("span", "jv-tools");
  const button = (text, run) => {
    const b = el("button", null, text);
    b.type = "button";
    b.addEventListener("click", run);
    tools.append(b);
    return b;
  };
  button("Expand all", () => viewer.expandAll());
  button("Collapse all", () => viewer.collapseAll());
  const copy = button("Copy", async () => {
    let said;
    try {
      await navigator.clipboard.writeText(viewer.text());
      said = "Copied";
    } catch (refused) {
      said = "Clipboard refused";
    }
    copy.textContent = said;
    setTimeout(() => { copy.textContent = "Copy"; }, 2000);
  });
  return tools;
}

/** Whether a content type is JSON. */
const isJsonType = (type) => typeof type === "string" && type.startsWith("application/json");

/** Whether a content type is CSV: a body shown as text, with a Download button. */
const isCsvType = (type) => typeof type === "string" && type.startsWith("text/csv");

/** {@code <action id>-<yyyyMMdd-HHmmss>.csv}, the id's characters outside [A-Za-z0-9._-] replaced by "-". */
function csvFileName(actionId, now) {
  const two = (n) => String(n).padStart(2, "0");
  const stamp = now.getFullYear() + two(now.getMonth() + 1) + two(now.getDate()) + "-" + two(now.getHours())
    + two(now.getMinutes()) + two(now.getSeconds());
  return String(actionId).replace(/[^A-Za-z0-9._-]/g, "-") + "-" + stamp + ".csv";
}

/**
 * A Download button that saves {@code text} as a CSV file: a Blob of type text/csv;charset=utf-8, named by
 * csvFileName, handed to the browser through a link it clicks. No request is sent.
 */
function downloadTools(text, actionId) {
  const tools = el("span", "jv-tools");
  const button = el("button", null, "Download");
  button.type = "button";
  button.addEventListener("click", () => {
    const url = URL.createObjectURL(new Blob([text], { type: "text/csv;charset=utf-8" }));
    const link = el("a");
    link.href = url;
    link.download = csvFileName(actionId, new Date());
    link.hidden = true;
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  });
  tools.append(button);
  return tools;
}

/** The body of an answer: CSV as text with Download, JSON through the viewer, anything else as text. */
function answerBody(answer, nodes, actionId) {
  if (isCsvType(answer.contentType)) {
    return { view: el("pre", "result-body", answer.body), tools: downloadTools(answer.body, actionId) };
  }
  return textOrJson(answer.body, isJsonType(answer.contentType), nodes);
}

/**
 * {@code text} through the JSON viewer, with its tools, when {@code json} and it parses; as text otherwise, a body
 * that claims JSON but is none included. {@code nodes} is the viewer's fold state.
 */
function textOrJson(text, json, nodes) {
  const viewer = json ? jsonViewer(text, nodes) : null;
  if (!viewer) return { view: el("pre", "result-body", text), tools: null };
  return { view: viewer.root, tools: viewerTools(viewer) };
}

// ------------------------------------------------------------------------------------------------ results

/** The class a result's state takes on the page: ok green, error red, running neutral. */
const STATE_CLASS = new Map([["ok", "ok"], ["error", "failed"], ["running", "running"]]);

/** The group of an action, or null: a blank group is none. */
const groupName = (action) => typeof action.group === "string" && action.group ? action.group : null;

/** A new fold state for the two viewers of a result: its body and its details. */
const freshNodes = () => ({ body: new Map(), details: new Map() });

/**
 * A result as the page keeps it (spec §2.2): its state, "ok", "error" or "running"; its line; the round trip the
 * page measured, in milliseconds, or null; the structured answer, or null; the time of the snapshot outcome it
 * reflects; the fold state of its viewers, by node path; whether its "Exchange" is open; whether it is the answer
 * of this page's own call, whose body the server's echo of that call keeps, and no later outcome.
 */
function outcome(state, summary, fields) {
  return { state, summary, millis: null, answer: null, time: null, nodes: freshNodes(), exchangeOpen: false,
    mine: false, ...fields };
}

/** The details of an answer, the JSON-RPC exchange for the MCP inspector, folded under "Exchange". */
function exchangeFold(details, result) {
  const exchange = el("details", "exchange");
  exchange.open = result.exchangeOpen;
  exchange.addEventListener("toggle", () => { result.exchangeOpen = exchange.open; });
  const shown = textOrJson(details, true, result.nodes.details);
  exchange.append(el("summary", null, "Exchange"), ...(shown.tools ? [shown.tools] : []), shown.view);
  return exchange;
}

/** What a result shows under its line in the panel's own bar: its body, with the viewer's tools, then its details. */
function resultOutput(result, actionId) {
  const answer = result.answer;
  const out = [];
  if (typeof answer.body === "string") {
    const body = answerBody(answer, result.nodes.body, actionId);
    if (body.tools) out.push(body.tools);
    out.push(body.view);
  }
  if (typeof answer.details === "string") out.push(exchangeFold(answer.details, result));
  return out;
}

/**
 * Where an action of the panel's own bar shows its results, as it always did: the line next to its button, the body
 * and the exchange under it. It keeps the last result for the life of the form; a new line over the same answer
 * leaves the body as it is.
 */
function inlineOutlet(actionId) {
  const message = el("span", "msg");
  const output = el("div", "result");
  let last = null;
  return {
    nodes: [message, output],
    current: () => last,
    publish(next) {
      const sameAnswer = last !== null && next.answer === last.answer;
      last = next;
      message.textContent = next.summary;
      message.className = "msg " + STATE_CLASS.get(next.state);
      if (!sameAnswer) output.replaceChildren(...(next.answer ? resultOutput(next, actionId) : []));
    },
  };
}

/**
 * The form of one action: its description, its fields, its button and inline confirmation. Where its results show
 * is {@code outlet}'s, { nodes, current(), publish(result) }: inlineOutlet for the panel's own bar, groupOutlet for a
 * group tab. A refusal, a network failure and an invalid field are results too, in state "error".
 */
function actionRow(panelId, action, outlet) {
  const root = el("form", "action");
  root.noValidate = true;
  if (typeof action.description === "string" && action.description) {
    root.append(el("p", "action-desc", action.description));
  }
  const fields = [];
  for (const argument of action.arguments || []) {
    const field = isObject(argument.schema) ? jsonField(argument) : stringField(argument);
    fields.push(field);
    root.append(field.root);
  }
  const go = el("button", "act", action.label || action.id);
  go.type = "submit";
  const ask = el("span", "ask");
  ask.hidden = true;
  const yes = el("button", "act confirm", "Confirm");
  yes.type = "button";
  const no = el("button", null, "Cancel");
  no.type = "button";
  ask.append(el("span", "question", action.confirmation || ""), yes, no);
  root.append(go, ask, ...outlet.nodes);

  let sending = false;
  const kept = outlet.current();
  let shown = kept ? kept.time : null;   // the time of the snapshot outcome last shown; a newer one replaces it
  const busy = (on) => {
    for (const c of [go, yes, no]) c.disabled = on;
    for (const field of fields) field.disable(on);
  };
  const publish = (state, summary, more) => outlet.publish(outcome(state, summary, { time: shown, ...more }));
  const closeAsk = () => { ask.hidden = true; go.hidden = false; };

  async function send() {
    const token = page.snapshot && page.snapshot.console && page.snapshot.console.actionToken;
    if (typeof token !== "string") { publish("error", "No token: reload the page."); return; }
    // Object.create(null): an argument named "__proto__" (a server-declared name like any other) must still reach
    // the request body as an own property, not be swallowed by the prototype's own accessor of that name.
    const body = Object.create(null);
    try {
      for (const field of fields) body[field.name] = field.value();
    } catch (invalid) {
      publish("error", invalid.message);
      return;
    }
    sending = true;
    busy(true);
    publish("running", "running…");
    const started = performance.now();
    try {
      const response = await fetch("api/action/" + encodeURIComponent(panelId) + "/" + encodeURIComponent(action.id), {
        method: "POST",
        cache: "no-store",
        headers: { "Content-Type": "application/json", "X-Vidocq-Console-Token": token },
        body: JSON.stringify(body),
      });
      const type = response.headers.get("Content-Type") || "";
      const answer = type.startsWith("application/json") ? await response.json() : { text: await response.text() };
      const millis = Math.round(performance.now() - started);
      if (response.status === 200 && typeof answer.result === "string") {
        publish(answer.error === true ? "error" : "ok", answer.result, { millis, answer, mine: true });
      } else if (response.status === 500 && typeof answer.error === "string") {
        publish("error", "failed: " + answer.error, { millis });
      } else if (response.status === 202) {
        publish("running", "still running after 60 s: the outcome will show here", { millis });
      } else if (response.status === 409) {
        publish("error", "another action of this panel is running", { millis });
      } else {
        publish("error", "refused (" + response.status + ")" + (answer.text ? ": " + answer.text : ""), { millis });
      }
    } catch (unreachable) {
      publish("error", "the console did not answer");
    } finally {
      sending = false;
      busy(false);
    }
  }

  root.addEventListener("submit", (event) => {
    event.preventDefault();
    if (sending) return;
    if (action.confirmation) { go.hidden = true; ask.hidden = false; yes.focus(); return; }
    send();
  });
  yes.addEventListener("click", () => { closeAsk(); send(); });
  no.addEventListener("click", closeAsk);

  /**
   * Follows the snapshot: an action running elsewhere, or a newer outcome of it. The outcome of this page's own call
   * keeps its body, round trip, fold state and exchange; any other outcome replaces it without a body.
   */
  function update(now) {
    if (!now || sending) return;
    busy(!!now.running);
    const current = outlet.current();
    if (now.running) {
      if (!current || current.state !== "running") publish("running", "running…");
      shown = null;
      return;
    }
    const last = now.last;
    if (!last || typeof last.text !== "string" || last.time === shown) return;
    shown = last.time;
    const own = current && current.mine && current.state !== "running" ? current : null;
    const failed = !last.ok || last.error === true;
    outlet.publish(outcome(failed ? "error" : "ok",
      (last.ok ? "" : "failed: ") + last.text + " · " + clockTime(last.time),
      own ? { millis: own.millis, answer: own.answer, nodes: own.nodes, exchangeOpen: own.exchangeOpen, time: shown }
        : { time: shown }));
  }

  const row = {
    id: action.id,
    label: action.label || action.id,
    group: groupName(action),
    text: [action.label, action.id, action.description].filter((t) => typeof t === "string").join(" ").toLowerCase(),
    root,
    /** Shows the form; whoever lays the row out may replace it, as a group tab does to pick it in its combo. */
    reveal() { root.hidden = false; },
    /** Fills the form with a replayed call's arguments, by name; sends nothing. */
    fill(values) {
      for (const field of fields) if (Object.hasOwn(values, field.name)) field.fill(values[field.name]);
      row.reveal();
      root.scrollIntoView({ block: "nearest" });
      go.focus();
    },
    update,
  };
  return row;
}

/**
 * A cell of a REPLAY_COLUMN column: "<action id> <JSON object of its arguments>", as a button that fills that
 * action's form with them. Nothing is sent: the user submits. null for a cell that is no such replay, or whose id is
 * no action of this panel: the table shows it as text, as a column that merely has that name expects.
 */
function replayButton(panelId, cell) {
  const space = typeof cell === "string" ? cell.indexOf(" ") : -1;
  if (space <= 0) return null;
  let values;
  try { values = JSON.parse(cell.slice(space + 1)); } catch (unreadable) { return null; }
  const row = actionRows.get(actionKey(panelId, cell.slice(0, space)));
  if (!row || !isObject(values)) return null;
  const button = el("button", "replay", "Replay");
  button.type = "button";
  button.title = "Fill the form of this action with these arguments";
  button.addEventListener("click", () => row.fill(values));
  return button;
}

/** The action id a REPLAY_COLUMN cell names, the text before its first space, or null. */
function replayTarget(cell) {
  const space = typeof cell === "string" ? cell.indexOf(" ") : -1;
  return space > 0 ? cell.slice(0, space) : null;
}

// ------------------------------------------------------------------------------------------------ a group tab

/**
 * Where an action of a group tab shows its results: the panel's page state, which keeps the last result of each
 * action across polls and redraws; {@code changed} redraws the result block when that action is the one on screen.
 */
function groupOutlet(state, actionId, changed) {
  return {
    nodes: [],
    current: () => state.results.get(actionId) || null,
    publish(next) {
      state.results.set(actionId, next);
      changed(actionId);
    },
  };
}

/**
 * The result of the selected action, apart from its form (spec §2.1): a header with the state in colour, the line,
 * the round trip the page measured and the viewer's tools, then the body, then the exchange folded under "Exchange".
 */
function resultBlock(result, actionId) {
  const block = el("section", "result-block");
  if (!result) {
    block.dataset.state = "none";
    block.append(el("p", "result-none", "No call yet"));
    return block;
  }
  block.dataset.state = result.state;
  const head = el("div", "result-head");
  const stateText = result.state === "running" ? "running…" : result.state;
  head.append(el("span", "result-state " + STATE_CLASS.get(result.state), stateText));
  if (result.summary !== stateText) head.append(el("span", "result-summary", result.summary));
  if (typeof result.millis === "number") head.append(el("span", "result-time", result.millis + " ms"));
  block.append(head);
  const answer = result.answer;
  if (answer && typeof answer.body === "string") {
    const body = answerBody(answer, result.nodes.body, actionId);
    if (body.tools) head.append(body.tools);
    block.append(body.view);
  }
  if (answer && typeof answer.details === "string") block.append(exchangeFold(answer.details, result));
  return block;
}

/**
 * The tab of one group (spec §2.1): a combo of its actions by label, in declaration order, with a text filter above
 * it past FILTER_FROM of them; the selected action's description and form; its result block; the group's history.
 * {@code rows} are the group's action rows, built once per panel structure with a groupOutlet: a form keeps what was
 * typed in while the combo shows another action. {@code open} shows this tab, for a Replay.
 */
function groupTab(panelId, name, rows, state, open) {
  const root = el("div", "subpanel");
  root.setAttribute("role", "tabpanel");
  root.setAttribute("aria-label", name);
  const picker = el("div", "action-picker");
  const combo = el("select", "action-select");
  combo.setAttribute("aria-label", name);
  let filter = null;
  if (rows.length > FILTER_FROM) {
    filter = el("input", "action-filter");
    filter.type = "search";
    filter.placeholder = "Filter " + rows.length + " actions";
    filter.autocomplete = "off";
    filter.spellcheck = false;
    picker.append(filter);
  }
  picker.append(combo);
  const formSlot = el("div", "action-slot");
  const resultSlot = el("div", "result-slot");
  resultSlot.setAttribute("aria-live", "polite");
  const historyBox = el("div", "history");
  const historyHolders = new Map();       // a holder per history table, by key, kept while the keys stay the same
  let historyKeys = null;
  root.append(picker, formSlot, resultSlot, historyBox);

  const byId = new Map(rows.map((row) => [row.id, row]));
  let selected = byId.has(state.chosen.get(name)) ? state.chosen.get(name) : rows[0].id;

  /** Lists the rows whose text holds {@code words} in the combo, and returns them. */
  function options(words) {
    const shown = words === "" ? rows : rows.filter((row) => row.text.includes(words));
    combo.replaceChildren(...shown.map((row) => {
      const option = el("option", null, row.label);
      option.value = row.id;
      return option;
    }));
    if (!shown.length) {
      const none = el("option", null, "no action matches");
      none.value = "";
      none.disabled = true;
      combo.append(none);
    }
    return shown;
  }
  const showResult = () => resultSlot.replaceChildren(resultBlock(state.results.get(selected) || null, selected));
  function choose(id) {
    selected = id;
    state.chosen.set(name, id);
    combo.value = id;
    formSlot.replaceChildren(byId.get(id).root);
    showResult();
  }

  combo.addEventListener("change", () => { if (byId.has(combo.value)) choose(combo.value); });
  if (filter) {
    filter.addEventListener("input", () => {
      const shown = options(filter.value.trim().toLowerCase());
      if (shown.length && !shown.some((row) => row.id === selected)) choose(shown[0].id);
      else combo.value = selected;
    });
  }
  for (const row of rows) {
    row.reveal = () => {
      if (filter && filter.value !== "") { filter.value = ""; options(""); }
      open();
      choose(row.id);
    };
  }
  options("");
  choose(selected);

  return {
    root,
    /** Redraws the result block when {@code actionId} is the action on screen. */
    changed(actionId) { if (actionId === selected) showResult(); },
    /**
     * Draws the history (spec §2.3): each of {@code tables}, { key, value } of the panel's tables with a replay
     * column, keeping the rows whose action {@code groupOf} places in this group; a row of an unknown action is
     * dropped.
     */
    update(tables, groupOf) {
      const keys = tables.map((table) => table.key).join("\u0000");
      if (keys !== historyKeys) {
        historyKeys = keys;
        historyHolders.clear();
        historyBox.replaceChildren();
        for (const { key } of tables) {
          const holder = el("div");
          historyHolders.set(key, holder);
          historyBox.append(el("h4", "table-key", label(key)), holder);
        }
      }
      for (const { key, value } of tables) {
        redrawTable(historyHolders.get(key), value.kind === "table"
          ? sampleTable(value, panelId, (id) => groupOf.get(id) === name)
          : el("p", "absent", value.reason || "not available"));
      }
    },
  };
}

/** The key, values and href of a line of the report: a line that points somewhere ends with {href}. */
function splitLine(line) {
  const last = line[line.length - 1];
  if (last !== null && typeof last === "object") {
    return {key: line[0], values: line.slice(1, -1), href: last.href};
  }
  return {key: line[0], values: line.slice(1), href: null};
}

/** A value of the report: a secret reads as whether it is configured, never more; a link opens a new tab. */
function factCell(values, href) {
  const td = el("td");
  const text = values.join(", ");
  if (values.length === 1 && (text === "configured" || text === "not configured")) {
    td.append(el("span", text === "configured" ? "secret" : "secret no", text));
  } else if (href) {
    td.append(linkTo(href, text));
  } else {
    td.textContent = text;
  }
  return td;
}

/**
 * The lines of a section of the report: rows with a key as a key/value table, lines without one, the cells of a
 * table, as a table with {@code heads} for columns when the page knows them.
 */
function linesBlock(lines, heads) {
  const out = [];
  let kv = null, ext = null;
  for (const line of lines || []) {
    const {key, values, href} = splitLine(line);
    if (key !== null && key !== undefined) {
      ext = null;
      if (!kv) { kv = el("table", "kv"); out.push(kv); }
      const tr = el("tr");
      tr.append(el("td", null, key), factCell(values, href));
      kv.append(tr);
    } else {
      kv = null;
      if (!ext) {
        ext = el("table", "ext");
        if (heads) {
          const tr = el("tr");
          for (const h of heads) tr.append(el("th", null, h));
          ext.append(tr);
        }
        const scroll = el("div", "scroll");
        scroll.append(ext);
        out.push(scroll);
      }
      const tr = el("tr");
      for (const cell of values) {
        // A route that can be opened as it is: its URL cell is the link.
        if (href && cell === href) {
          const td = el("td");
          td.append(linkTo(href, cell));
          tr.append(td);
        } else {
          tr.append(el("td", /^\d+$/.test(cell) ? "n" : null, cell));
        }
      }
      ext.append(tr);
    }
  }
  return out;
}

function anomalyBox(anomaly) {
  const box = el("div", "anom");
  box.append(el("div", "code", (anomaly.code || "") + (anomaly.source ? " · " + anomaly.source : "")));
  if (anomaly.message) box.append(el("p", null, anomaly.message));
  if (anomaly.hint) box.append(el("p", "hint", anomaly.hint));
  return box;
}

function anomaliesOf(panelId, snapshot) {
  return ((snapshot.startup && snapshot.startup.anomalies) || []).filter((a) => a.source === panelId);
}

function panelHead(title, source) {
  const h = el("div", "panel-head");
  h.append(el("h2", null, title), el("span", "src", source));
  return h;
}

const plural = (n, one, many) => n + " " + (n === 1 ? one : many);

// ------------------------------------------------------------------------------------------------ the startup panel

function startupView(snapshot) {
  panelArea.append(panelHead("Startup", "the startup report · boot " + snapshot.console.boot));
  const report = snapshot.startup;
  if (!report) {
    panelArea.append(el("p", "summary", "booting… The report is written once every extension has started; this "
      + "tab shows it on the next poll that finds it."));
    return { update() {} };
  }
  panelArea.append(el("p", "summary", "Launch mode " + report.launchMode
    + (report.launchReason ? ", resolved from " + report.launchReason : "")
    + ". The console keeps the report of this boot in its detailed form, whatever the log carried."));

  const anomalies = el("section", "block");
  anomalies.append(el("h3", null, "Anomalies"));
  if (report.anomalies && report.anomalies.length) {
    const list = el("div", "anoms");
    list.append(...report.anomalies.map(anomalyBox));
    anomalies.append(list);
    if (report.truncated) anomalies.append(el("p", "note", "The snapshot carries the first anomalies only."));
  } else {
    anomalies.append(el("p", "none", "None."));
  }
  panelArea.append(anomalies);

  for (const section of report.sections || []) {
    const core = CORE_SECTIONS.get(section.id);
    const block = el("section", "block");
    const h3 = el("h3", null, core || section.headline || section.id);
    const sub = !core ? section.id : HEADLINE_REPEATS_ROWS.has(section.id) ? null : section.headline;
    if (sub) h3.append(el("span", "sub", sub));
    block.append(h3);
    if (section.summary && section.summary !== section.headline) block.append(el("p", "summary", section.summary));
    block.append(...linesBlock(section.lines, core ? TABLE_HEADS.get(section.id) : null));
    if (section.truncated) block.append(el("p", "note", "The snapshot carries the first lines only."));
    panelArea.append(block);
  }

  if (report.text) {
    const full = el("section", "block");
    full.append(el("h3", null, "Full report"));
    const actions = el("div", "row-actions");
    const copy = el("button", null, "Copy report");
    copy.type = "button";
    const message = el("span", "msg", "The report of this boot, in its detailed form.");
    actions.append(copy, message);
    const pre = el("pre", "report", report.text);
    copy.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(pre.textContent);
        message.textContent = "Copied.";
      } catch (refused) {
        const range = document.createRange();
        range.selectNodeContents(pre);
        const selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
        message.textContent = "Selected: press ⌘C or Ctrl+C to copy.";
      }
    });
    full.append(actions, pre);
    panelArea.append(full);
  }
  return { update() {} };
}

// ------------------------------------------------------------------------------------------------ a panel

/** What decides the layout of a panel: its values and groups. A change draws the panel again. */
function structure(panel) {
  const sample = panel.sample;
  // the actions a panel offers draw their buttons once: a change of them draws the panel again
  const acts = Array.isArray(panel.actions)
    ? "#" + panel.actions.map((a) => a.id + "@" + (groupName(a) || "")).join(",") : "";
  if (!sample) return "facts" + acts;
  if (sample.error) return "error" + acts;
  const keys = (values) => (values || []).map((v) => v.key + (v.kind === "table" ? "#" : "")).join(",");
  return keys(sample.values) + "|" + (sample.groups || []).map((g) => g.name + ":" + keys(g.values)).join("|")
    + acts;
}

/** A scope of a sample, the panel's own or a group's: its tiles, its tables and the charts that apply to it. */
function scopeView(container, panel, group, values, charts) {
  const tilesBox = el("div", "tiles");
  const tables = el("div");
  const chartsBox = el("div", "charts");
  const tiles = new Map();
  const tableBoxes = new Map();
  for (const value of values) {
    if (value.kind === "table") {
      const holder = el("div");
      tables.append(el("h4", "table-key", label(value.key)), holder);
      tableBoxes.set(value.key, holder);
    } else {
      const t = tile(value.key);
      tiles.set(value.key, t);
      tilesBox.append(t.root);
    }
  }
  const keys = new Set(values.map((v) => v.key));
  const boxes = charts.filter((chart) => chart.series.some((s) => keys.has(s.key))).map(chartBox);
  chartsBox.append(...boxes.map((b) => b.box));
  if (tiles.size) container.append(tilesBox);
  if (tableBoxes.size) container.append(tables);
  if (boxes.length) container.append(chartsBox);
  return {
    update(current, snapshot) {
      for (const value of current || []) {
        const t = tiles.get(value.key);
        if (t) setTile(t, value, previous(panel.id, group, value.key));
        const holder = tableBoxes.get(value.key);
        if (holder) redrawTable(holder, value.kind === "table" ? sampleTable(value, panel.id)
          : el("p", "absent", value.reason || "not available"));
      }
      for (const box of boxes) drawChart(box, panel.id, group, snapshot.console.time, pollMillis(snapshot));
    },
  };
}

function panelView(panel, snapshot) {
  panelArea.append(panelHead(panel.title || panel.id, panel.id + (panel.live ? " · live" : " · boot facts only")));
  const state = panelState(panel.id);
  const actions = Array.isArray(panel.actions) ? panel.actions : [];
  // a result is kept for as long as its action exists: a dev reload that removes the action forgets it (spec §4)
  // (not while a dev reload has withdrawn every action: the next boot brings the surviving ones back)
  if (actions.length) for (const id of [...state.results.keys()]) if (!actions.some((a) => a.id === id)) {
    state.results.delete(id);
  }
  const groupTabs = new Map();
  for (const key of [...actionRows.keys()]) if (key.startsWith(panel.id + "\u0000")) actionRows.delete(key);
  const rows = actions.map((action) => {
    const group = groupName(action);
    const outlet = group === null ? inlineOutlet(action.id) : groupOutlet(state, action.id, (id) => {
      const tab = groupTabs.get(group);
      if (tab) tab.changed(id);
    });
    const row = actionRow(panel.id, action, outlet);
    actionRows.set(actionKey(panel.id, row.id), row);
    return row;
  });
  const byGroup = new Map();              // the grouped rows, by group in order of first appearance
  for (const row of rows) {
    if (!row.group) continue;
    if (!byGroup.has(row.group)) byGroup.set(row.group, []);
    byGroup.get(row.group).push(row);
  }
  const groupOf = new Map(rows.filter((row) => row.group).map((row) => [row.id, row.group]));

  // Monitoring: everything the panel shows but its grouped actions and its replay tables. With no grouped action, the
  // panel itself, drawn as it always was.
  const box = byGroup.size ? el("div", "subpanel") : panelArea;
  if (byGroup.size) {
    if (!byGroup.has(state.tab)) state.tab = MONITORING;
    box.setAttribute("role", "tabpanel");
    box.setAttribute("aria-label", "Monitoring");
    const strip = el("div", "subtabs");
    strip.setAttribute("role", "tablist");
    strip.setAttribute("aria-label", (panel.title || panel.id) + " views");
    const bodies = new Map([[MONITORING, box]]);
    const buttons = [];
    const openTab = (id) => {
      state.tab = id;
      store(SUBTAB_KEY + panel.id, id);
      for (const [tabId, body] of bodies) body.hidden = tabId !== id;
      for (const b of buttons) {
        const on = b.dataset.tab === id;
        b.setAttribute("aria-selected", String(on));
        b.tabIndex = on ? 0 : -1;
      }
    };
    for (const [id, title] of [[MONITORING, "Monitoring"], ...[...byGroup.keys()].map((g) => [g, g])]) {
      const b = el("button", "subtab", title);
      b.type = "button";
      b.setAttribute("role", "tab");
      b.dataset.tab = id;
      b.addEventListener("click", () => openTab(id));
      buttons.push(b);
    }
    strip.append(...buttons);
    strip.addEventListener("keydown", (event) => {
      const at = buttons.indexOf(document.activeElement);
      if (at < 0) return;
      const step = { ArrowDown: 1, ArrowRight: 1, ArrowUp: -1, ArrowLeft: -1 }[event.key];
      if (!step) return;
      event.preventDefault();
      const next = buttons[(at + step + buttons.length) % buttons.length];
      openTab(next.dataset.tab);
      next.focus();
    });
    panelArea.append(strip, box);
    for (const [name, groupRows] of byGroup) {
      const tab = groupTab(panel.id, name, groupRows, state, () => openTab(name));
      groupTabs.set(name, tab);
      bodies.set(name, tab.root);
      panelArea.append(tab.root);
    }
    openTab(state.tab);
  }

  if (panel.summary) box.append(el("p", "summary", panel.summary));
  const opens = openButtons(panel.lines);
  if (opens) box.append(opens);
  const bar = actionsBar(rows.filter((row) => !row.group));
  if (bar) box.append(bar);
  const anomalies = anomaliesOf(panel.id, snapshot);
  if (anomalies.length) {
    const list = el("div", "anoms");
    list.append(...anomalies.map(anomalyBox));
    box.append(list);
  }
  const flags = el("div", "flags");
  const failure = el("div", "anom crit");
  failure.hidden = true;
  box.append(failure, flags);

  const sample = panel.sample && !panel.sample.error ? panel.sample : null;
  const groups = sample ? sample.groups || [] : [];
  const charts = panel.charts || [];
  // the tables with a replay column, [scope, key], move to the group tabs (spec §2.3)
  const replayTables = [];
  if (byGroup.size && sample) {
    for (const [scope, values] of [["", sample.values || []], ...groups.map((g) => [g.name, g.values || []])]) {
      for (const v of values) {
        if (v.kind === "table" && Array.isArray(v.columns) && v.columns.includes(REPLAY_COLUMN)) {
          replayTables.push([scope, v.key]);
        }
      }
    }
  }
  const staying = (scope, values) => values.filter((v) => !replayTables.some(([s, k]) => s === scope && k === v.key));
  const scopes = [];
  if (sample) scopes.push(["", scopeView(box, panel, "", staying("", sample.values || []), charts)]);

  // the boot facts of a group are the lines whose key is its name, or starts with it: "@Default size"
  const lines = panel.lines || [];
  const claimed = new Set();
  const byLength = [...groups].sort((a, b) => b.name.length - a.name.length);
  const factsOf = new Map(groups.map((g) => [g.name, { kind: null, lines: [] }]));
  lines.forEach((line, i) => {
    const key = line[0];
    if (typeof key !== "string") return;
    for (const g of byLength) {
      if (key === g.name && line.length > 1) {
        factsOf.get(g.name).kind = splitLine(line).values.join(", ");
        claimed.add(i);
        return;
      }
      if (key.startsWith(g.name + " ")) {
        factsOf.get(g.name).lines.push([key.slice(g.name.length + 1), ...line.slice(1)]);
        claimed.add(i);
        return;
      }
    }
  });

  groups.forEach((group, index) => {
    const card = el("article", "group");
    const gh = el("div", "group-head");
    gh.append(el("h3", null, group.name));
    const facts = factsOf.get(group.name);
    if (facts.kind) gh.append(el("span", "kind", facts.kind));
    card.append(gh);
    scopes.push([group.name, scopeView(card, panel, group.name, staying(group.name, group.values || []), charts)]);
    if (facts.lines.length) {
      const id = panel.id + "\u0000" + group.name;
      const details = el("details");
      details.open = page.openGroups.has(id) || (index === 0 && !page.closedGroups.has(id));
      details.addEventListener("toggle", () => {
        if (details.open) { page.openGroups.add(id); page.closedGroups.delete(id); }
        else { page.closedGroups.add(id); page.openGroups.delete(id); }
      });
      details.append(el("summary", null, "Boot facts"), ...linesBlock(facts.lines, null));
      card.append(details);
    }
    box.append(card);
  });

  const rest = lines.filter((line, i) => !claimed.has(i));
  if (rest.length) {
    const block = el("section", "block");
    block.append(el("h3", null, "Boot facts"), ...linesBlock(rest, null));
    if (panel.truncated) block.append(el("p", "note", "The snapshot carries the first lines only."));
    box.append(block);
  }

  return {
    update(current) {
      const now = current.panels.find((p) => p.id === panel.id);
      if (!now) return;
      const nowActions = Array.isArray(now.actions) ? now.actions : [];
      for (const row of rows) row.update(nowActions.find((a) => a.id === row.id));
      const s = now.sample;
      failure.hidden = !(s && s.error);
      if (s && s.error) {
        failure.replaceChildren(el("div", "code", "sample failed · " + s.error),
          el("p", null, "The panel's sample() threw " + s.error + ": its live values are missing from this poll, "
            + "its boot facts stay. The console logged VIDOCQ-DEVC-005 once and asks the panel again on every poll."));
      }
      flags.replaceChildren();
      if (s && s.slow) flags.append(el("span", "chip warn", "slow sample: " + duration(s.nanos)));
      if (s && s.truncated) flags.append(el("span", "chip", "some values dropped: past the console's limits"));
      if (!s || s.error) return;
      const values = new Map([["", s.values], ...(s.groups || []).map((g) => [g.name, g.values])]);
      for (const [name, scope] of scopes) scope.update(values.get(name), current);
      if (!groupTabs.size) return;
      const tables = replayTables
        .map(([scope, key]) => ({ key, value: (values.get(scope) || []).find((v) => v.key === key) }))
        .filter((table) => table.value);
      for (const tab of groupTabs.values()) tab.update(tables, groupOf);
    },
  };
}

// ------------------------------------------------------------------------------------------------ the page

function pollMillis(snapshot) {
  const millis = snapshot && snapshot.console && snapshot.console.pollMillis;
  return typeof millis === "number" && millis >= 250 && millis <= 60_000 ? millis : 1000;
}

function tabItems(snapshot) {
  const items = [{ id: STARTUP, title: "Startup", chip: startupChip(snapshot) }];
  for (const panel of snapshot.panels || []) {
    items.push({ id: panel.id, title: panel.title || panel.id, chip: panelChip(panel, snapshot) });
  }
  return items;
}

function startupChip(snapshot) {
  if (!snapshot.startup) return ["booting", ""];
  const n = (snapshot.startup.anomalies || []).length;
  return n ? [plural(n, "anomaly", "anomalies"), "warn"] : null;
}

function panelChip(panel, snapshot) {
  if (panel.sample && panel.sample.error) return ["failed", "crit"];
  const n = anomaliesOf(panel.id, snapshot).length;
  if (n) return [plural(n, "anomaly", "anomalies"), "warn"];
  return panel.live ? null : ["facts", ""];
}

function renderTabs(items) {
  const key = JSON.stringify([items, page.selected]);
  if (key === page.tabsKey) return;
  page.tabsKey = key;
  const focused = document.activeElement && document.activeElement.classList.contains("tab")
    ? document.activeElement.dataset.panel : null;
  tabs.replaceChildren(...items.map((item) => {
    const tab = el("button", "tab", item.title);
    tab.type = "button";
    tab.setAttribute("role", "tab");
    tab.dataset.panel = item.id;
    tab.setAttribute("aria-selected", String(item.id === page.selected));
    tab.tabIndex = item.id === page.selected ? 0 : -1;
    if (item.chip) tab.append(el("span", "chip " + item.chip[1], item.chip[0]));
    return tab;
  }));
  if (focused) {
    const again = [...tabs.children].find((t) => t.dataset.panel === focused);
    if (again) again.focus();
  }
}

function render() {
  const snapshot = page.snapshot;
  if (!snapshot) return;
  const c = snapshot.console;
  $("url").textContent = c.url || "";
  $("version").textContent = c.vidocq ? "Vidocq " + c.vidocq : "";
  const banner = $("port-banner");
  const taken = c.portTaken;
  if (taken && Number.isInteger(taken.configured) && Number.isInteger(taken.bound)) {
    banner.replaceChildren("Port ", el("b", null, taken.configured), " was taken: the console listens on port ",
      el("b", null, taken.bound), " instead. Free port " + taken.configured + ", or set vidocq.devconsole.port to "
      + "another port, 0 for any free one.");
    banner.hidden = false;
  } else {
    banner.hidden = true;
  }

  const items = tabItems(snapshot);
  if (!items.some((item) => item.id === page.selected)) page.selected = STARTUP;
  renderTabs(items);

  const panel = (snapshot.panels || []).find((p) => p.id === page.selected);
  const key = [page.boot, page.selected, panel ? structure(panel) : snapshot.state].join("\u0000");
  if (!page.view || page.view.key !== key) {
    panelArea.replaceChildren();
    page.view = panel ? panelView(panel, snapshot) : startupView(snapshot);
    page.view.key = key;
    panelArea.classList.remove("stale", "unreachable");
  }
  page.view.update(snapshot);
}

function showState() {
  const failing = page.failingSince !== 0;
  const unreachable = failing && Date.now() - page.failingSince >= FAST_RETRY_FOR_MILLIS;
  const snapshot = page.snapshot;
  let s, text;
  if (page.paused) {
    s = "paused"; text = "paused";
  } else if (unreachable) {
    s = "unreachable"; text = "unreachable · retrying every 5 s";
  } else if (failing) {
    s = "reloading"; text = "reloading… · retrying every 1 s";
  } else if (!snapshot) {
    s = "connecting"; text = "connecting…";
  } else {
    s = "ready";
    text = (snapshot.state === "booting" ? "booting" : "ready") + " · polling every "
      + Number((pollMillis(snapshot) / 1000).toFixed(2)) + " s";
  }
  $("state").dataset.s = s;
  $("state-text").textContent = text;
  const pause = $("pause");
  pause.textContent = page.paused ? "Resume" : "Pause";
  pause.setAttribute("aria-pressed", String(page.paused));

  panelArea.classList.toggle("stale", failing);
  panelArea.classList.toggle("unreachable", unreachable);
  const veil = panelArea.querySelector(":scope > .reloading-veil");
  if (veil) veil.remove();
  if (failing) {
    const v = el("div", "reloading-veil");
    v.append(el("span", null, unreachable ? "unreachable: the application does not answer"
      : "reloading… the application is restarting"));
    panelArea.append(v);
  }
}

function received(snapshot) {
  const known = snapshot.console.boot === page.boot;
  if (!known) {
    history.clear();                       // a new boot: its history starts again, its boot facts are drawn again
    page.since = -1;
    page.boot = snapshot.console.boot;
    page.view = null;
  }
  // This document answered a ?since= from the previous boot, so it carries an arbitrary tail of the new one's ring.
  // Dropping it costs one poll and buys the whole ring on the next, asked for with no since at all.
  if (known) record(snapshot);
  page.snapshot = snapshot;
  render();
}

function schedule(delay) {
  clearTimeout(page.timer);
  page.timer = 0;
  if (page.paused || document.hidden) return;
  page.timer = setTimeout(poll, delay);
}

async function poll() {
  page.timer = 0;
  if (page.inFlight || page.paused || document.hidden) return;
  page.inFlight = true;
  let snapshot = null;
  try {
    // Ask only for the points this page does not have. After a spell hidden, that is every point it missed.
    const url = page.since > 0 ? "api/snapshot?since=" + page.since : "api/snapshot";
    const response = await fetch(url, {
      cache: "no-store",
      headers: { Accept: "application/json" },
      signal: AbortSignal.timeout ? AbortSignal.timeout(REQUEST_TIMEOUT_MILLIS) : undefined,
    });
    if (!response.ok) throw new Error("status " + response.status);
    const body = await response.json();
    if (body && typeof body === "object" && body.console && Array.isArray(body.panels)) snapshot = body;
  } catch (unreachable) {
    snapshot = null;
  } finally {
    page.inFlight = false;
  }
  if (snapshot) {
    page.failingSince = 0;
    try {
      received(snapshot);
    } catch (bug) {
      console.error("Vidocq dev console: this snapshot could not be drawn", bug);
    }
  } else if (!page.failingSince) {
    page.failingSince = Date.now();
  }
  showState();
  const failingFor = page.failingSince ? Date.now() - page.failingSince : 0;
  schedule(!page.failingSince ? pollMillis(page.snapshot)
    : failingFor < FAST_RETRY_FOR_MILLIS ? FAST_RETRY_MILLIS : SLOW_RETRY_MILLIS);
}

function select(id) {
  if (id === page.selected) return;
  page.selected = id;
  store(PANEL_KEY, id);
  page.view = null;
  render();
  showState();
}

tabs.addEventListener("click", (event) => {
  const tab = event.target.closest(".tab");
  if (tab) select(tab.dataset.panel);
});
tabs.addEventListener("keydown", (event) => {
  const all = [...tabs.querySelectorAll(".tab")];
  const at = all.indexOf(document.activeElement);
  if (at < 0) return;
  const step = { ArrowDown: 1, ArrowRight: 1, ArrowUp: -1, ArrowLeft: -1 }[event.key];
  if (!step) return;
  event.preventDefault();
  const next = all[(at + step + all.length) % all.length];
  select(next.dataset.panel);
  const again = [...tabs.querySelectorAll(".tab")].find((t) => t.dataset.panel === next.dataset.panel);
  if (again) again.focus();
});
$("pause").addEventListener("click", () => {
  page.paused = !page.paused;
  store(PAUSED_KEY, String(page.paused));
  showState();
  schedule(0);
});
document.addEventListener("visibilitychange", () => {
  if (document.hidden) clearTimeout(page.timer);
  else schedule(0);
});
let resizing = 0;
window.addEventListener("resize", () => {
  clearTimeout(resizing);
  resizing = setTimeout(() => { if (page.view && page.snapshot) page.view.update(page.snapshot); }, 150);
});

panelArea.replaceChildren(el("p", "summary", "Connecting to the console…"));
showState();
schedule(0);
