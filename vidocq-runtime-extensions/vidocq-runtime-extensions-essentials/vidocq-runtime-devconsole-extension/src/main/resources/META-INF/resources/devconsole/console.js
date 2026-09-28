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
// - localStorage keeps the selected panel and the pause, nothing else, and may refuse both.
// - In a dev launch, a panel's actions are buttons. Each sends one same-origin POST, application/json, with the
//   token of the boot the snapshot carries (console.actionToken); a confirmation is asked inline, never with a
//   blocking dialog. The page shows the line the action returned, or the class of what it threw.
// - An action's json argument is a form generated from its JSON Schema when the schema is flat (isFlatSchema), a raw
//   JSON editor otherwise, with a "JSON" switch that keeps the values. A structured answer shows its body, through
//   the JSON viewer (jsonViewer) when it is JSON, and its details folded under "Exchange", through it too. A cell of
//   a sample table column named "replay" that reads as "<action id> <JSON object>" of an action of that panel is a
//   button that fills its form: nothing is sent until the user submits. Any other cell of such a column stays text.

const HISTORY_POINTS = 300;          // five minutes at one poll per second
const WINDOW_MILLIS = 300_000;       // what a chart shows: the last five minutes
const FAST_RETRY_MILLIS = 1000;
const SLOW_RETRY_MILLIS = 5000;
const FAST_RETRY_FOR_MILLIS = 60_000;
const REQUEST_TIMEOUT_MILLIS = 10_000;
const SVG = "http://www.w3.org/2000/svg";
const PANEL_KEY = "vidocq.devconsole.panel";
const PAUSED_KEY = "vidocq.devconsole.paused";
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
 * Replay button, and that column's header left blank when at least one cell is.
 */
function sampleTable(value, panelId) {
  const table = el("table", "ext");
  const columns = value.columns || [];
  const replayAt = columns.indexOf(REPLAY_COLUMN);
  const body = el("tbody");
  let buttons = 0;
  for (const row of value.rows || []) {
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
 * The actions a panel offers, in a dev launch only (the snapshot then carries console.actionToken): one form each.
 * Actions with a group go into a folded section per group, in order of first appearance; past FILTER_FROM actions a
 * text filter narrows them. A confirmation is asked inline, never with the browser's blocking dialog. The request is
 * a same-origin fetch with the token of the boot.
 */
function actionsBar(panel) {
  const actions = Array.isArray(panel.actions) ? panel.actions : [];
  if (!actions.length) return null;
  const bar = el("div", "actions");
  const rows = actions.map((action) => actionRow(panel.id, action));
  for (const key of [...actionRows.keys()]) if (key.startsWith(panel.id + "\u0000")) actionRows.delete(key);
  for (const row of rows) actionRows.set(actionKey(panel.id, row.id), row);

  let filter = null;
  if (rows.length > FILTER_FROM) {
    filter = el("input", "action-filter");
    filter.type = "search";
    filter.placeholder = "Filter " + rows.length + " actions";
    filter.autocomplete = "off";
    filter.spellcheck = false;
    bar.append(filter);
  }
  const sections = new Map();
  for (const row of rows) {
    if (!row.group) { bar.append(row.root); continue; }
    let section = sections.get(row.group);
    if (!section) {
      const box = el("details", "action-group");
      const title = el("summary");
      box.append(title);
      bar.append(box);
      section = { name: row.group, box, title, rows: [] };
      sections.set(row.group, section);
    }
    section.box.append(row.root);
    section.rows.push(row);
  }
  const titles = () => {
    for (const s of sections.values()) {
      const shown = s.rows.filter((r) => !r.root.hidden).length;
      s.title.textContent = s.name + " (" + (shown === s.rows.length ? shown : shown + " of " + s.rows.length) + ")";
    }
  };
  titles();
  if (filter) {
    filter.addEventListener("input", () => {
      const words = filter.value.trim().toLowerCase();
      for (const row of rows) row.root.hidden = words !== "" && !row.text.includes(words);
      for (const s of sections.values()) {
        const any = s.rows.some((r) => !r.root.hidden);
        s.box.hidden = !any;
        if (words !== "" && any) s.box.open = true;
      }
      titles();
    });
  }
  return {
    root: bar,
    update(current) {
      const now = (current.actions || []);
      for (const row of rows) row.update(now.find((a) => a.id === row.id));
    },
  };
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
const NOT_FLAT = ["$ref", "properties", "items", "anyOf", "oneOf", "allOf", "not", "patternProperties"];

/**
 * Whether a json argument's schema gets a generated form (spec §2.4): its root is "type": "object", and every
 * property is a string, a number, an integer or a boolean, or an enum of strings, with no $ref and no nesting. The
 * one place this rule is written.
 */
function isFlatSchema(schema) {
  if (!isObject(schema) || schema.type !== "object") return false;
  if (["$ref", "anyOf", "oneOf", "allOf", "not"].some((k) => k in schema)) return false;
  if (schema.properties === undefined) return true;
  if (!isObject(schema.properties)) return false;
  return Object.values(schema.properties).every((p) => isObject(p) && !NOT_FLAT.some((k) => k in p)
    && (Array.isArray(p.enum)
      ? (p.type === undefined || p.type === "string") && p.enum.length > 0 && p.enum.every((v) => typeof v === "string")
      : SCALAR_TYPES.has(p.type)));
}

/**
 * The raw editor's first value: the required properties, each with its default, or an empty value of its type. A
 * property named "__proto__" is a schema-declared name like any other: a bare {} would silently drop a write to
 * that key (or repoint the object's own prototype) instead of storing it, so the object is prototype-less.
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
      : SKELETON.has(p.type) ? structuredClone(SKELETON.get(p.type)) : null;
  }
  return object;
}

/** {@code values} without the members a panel masked: the user types those again. */
const unmasked = (values) => Object.fromEntries(Object.entries(values).filter(([, v]) => v !== MASKED));

/**
 * A json argument: a form generated from its schema when the schema is flat, using required, default, description
 * and enum, and a raw JSON editor otherwise, starting from the required properties. A "JSON" switch shows the form's
 * value as JSON; switching back keeps the values. value() returns the JSON text sent, or throws what is wrong.
 */
function jsonField(argument) {
  const root = el("div", "json-arg");
  const schema = argument.schema;
  const name = argument.label || argument.name;
  const flat = isFlatSchema(schema);
  const required = new Set(Array.isArray(schema.required) ? schema.required.filter((n) => typeof n === "string")
    : []);
  const head = el("div", "json-head");
  head.append(el("span", "json-label", name));
  const note = el("span", "json-note");
  const editor = el("textarea", "json-editor");
  editor.spellcheck = false;
  editor.rows = 6;
  editor.name = argument.name;
  editor.value = JSON.stringify(skeleton(schema), null, 2);
  const form = el("div", "json-form");
  const inputs = new Map();
  const raw = el("input");
  raw.type = "checkbox";
  if (flat) {
    for (const [property, definition] of Object.entries(schema.properties || {})) {
      const kind = Array.isArray(definition.enum) ? "enum" : definition.type;
      const wrap = el("label", "arg");
      wrap.append(el("span", null, property + (required.has(property) ? " *" : "")));
      let input;
      if (kind === "enum" || kind === "boolean") {
        input = el("select");
        for (const v of ["", ...(kind === "enum" ? definition.enum : ["true", "false"])]) {
          const option = el("option", null, v === "" ? "–" : v);
          option.value = v;
          input.append(option);
        }
      } else {
        input = el("input");
        input.type = "text";
        input.autocomplete = "off";
        input.spellcheck = false;
        if (kind !== "string") input.inputMode = "decimal";
      }
      if (definition.default !== undefined && definition.default !== null) input.value = String(definition.default);
      if (typeof definition.description === "string") input.title = definition.description;
      input.name = argument.name + "." + property;
      wrap.append(input);
      form.append(wrap);
      inputs.set(property, { input, kind });
    }
    const toggle = el("label", "json-switch");
    toggle.append(raw, el("span", null, "JSON"));
    head.append(toggle);
  }
  head.append(note);
  root.append(head);
  if (flat) root.append(form);
  root.append(editor);
  const rawMode = () => !flat || raw.checked;
  const show = () => { form.hidden = rawMode(); editor.hidden = !rawMode(); };
  show();

  /**
   * The form's values as an object; strict, it refuses a number that is none and a missing required property. A
   * property literally named "__proto__" is a name like any other: a bare {} would silently drop the write, which
   * would then make it forever "required" instead of present.
   */
  function formObject(strict) {
    const object = Object.create(null);
    for (const [property, { input, kind }] of inputs) {
      const text = input.value.trim();
      if (text === "") continue;
      if (kind === "integer" || kind === "number") {
        const n = Number(text);
        const valid = kind === "integer" ? /^-?\d+$/.test(text) : Number.isFinite(n);
        if (!valid && strict) throw new Error(property + ": not " + (kind === "integer" ? "an integer" : "a number"));
        object[property] = valid ? n : text;
      } else if (kind === "boolean") {
        object[property] = text === "true";
      } else {
        object[property] = input.value;
      }
    }
    if (strict) {
      for (const property of required) {
        if (!Object.hasOwn(object, property)) throw new Error(property + " is required");
      }
    }
    return object;
  }
  function editorObject() {
    let value;
    try { value = JSON.parse(editor.value); } catch (unparsable) { throw new Error(name + ": not valid JSON"); }
    if (!isObject(value)) throw new Error(name + ": not a JSON object");
    return value;
  }
  function toForm(object) {
    for (const [property, { input }] of inputs) {
      // Object.hasOwn: a property object lacks, such as "constructor" or "toString", must read as absent, never
      // as the inherited member of that name.
      const v = Object.hasOwn(object, property) ? object[property] : undefined;
      input.value = v === undefined || v === null ? "" : typeof v === "object" ? JSON.stringify(v) : String(v);
    }
  }
  raw.addEventListener("change", () => {
    note.textContent = "";
    if (raw.checked) {
      editor.value = JSON.stringify(formObject(false), null, 2);
    } else {
      try { toForm(editorObject()); } catch (invalid) { raw.checked = true; note.textContent = invalid.message; }
    }
    show();
  });
  return {
    name: argument.name,
    root,
    value: () => JSON.stringify(rawMode() ? editorObject() : formObject(true)),
    fill(values) {
      if (!isObject(values)) return;
      const kept = unmasked(values);
      editor.value = JSON.stringify(kept, null, 2);
      if (flat) toForm(kept);
      note.textContent = Object.keys(kept).length < Object.keys(values).length ? "masked values: type them again" : "";
    },
    disable(on) { for (const c of [editor, raw, ...[...inputs.values()].map((i) => i.input)]) c.disabled = on; },
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

/**
 * {@code text} through the JSON viewer, with its tools, when {@code json} and it parses; as text otherwise, a body
 * that claims JSON but is none included. {@code nodes} is the viewer's fold state.
 */
function textOrJson(text, json, nodes) {
  const viewer = json ? jsonViewer(text, nodes) : null;
  if (!viewer) return { view: el("pre", "result-body", text), tools: null };
  return { view: viewer.root, tools: viewerTools(viewer) };
}

/** What an answer shows under its line: its body, then its details folded under "Exchange". */
function resultOutput(answer) {
  const out = [];
  if (typeof answer.body === "string") {
    const body = textOrJson(answer.body, isJsonType(answer.contentType), new Map());
    if (body.tools) out.push(body.tools);
    out.push(body.view);
  }
  if (typeof answer.details === "string") {
    const exchange = el("details", "exchange");
    const details = textOrJson(answer.details, true, new Map());
    exchange.append(el("summary", null, "Exchange"), ...(details.tools ? [details.tools] : []), details.view);
    out.push(exchange);
  }
  return out;
}

function actionRow(panelId, action) {
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
  const message = el("span", "msg");
  const output = el("div", "result");
  root.append(go, ask, message, output);

  let sending = false;
  let shown = null;            // the time of the outcome of the snapshot last shown; a newer one replaces the message
  const busy = (on) => {
    for (const c of [go, yes, no]) c.disabled = on;
    for (const field of fields) field.disable(on);
  };
  const say = (text, cls) => { message.textContent = text; message.className = "msg" + (cls ? " " + cls : ""); };
  const closeAsk = () => { ask.hidden = true; go.hidden = false; };
  const reveal = () => {
    root.hidden = false;
    const box = root.closest("details");
    if (box) { box.hidden = false; box.open = true; }
  };

  async function send() {
    const token = page.snapshot && page.snapshot.console && page.snapshot.console.actionToken;
    if (typeof token !== "string") { say("No token: reload the page.", "failed"); return; }
    // Object.create(null): an argument named "__proto__" (a server-declared name like any other) must still reach
    // the request body as an own property, not be swallowed by the prototype's own accessor of that name.
    const body = Object.create(null);
    try {
      for (const field of fields) body[field.name] = field.value();
    } catch (invalid) {
      say(invalid.message, "failed");
      return;
    }
    sending = true;
    busy(true);
    output.replaceChildren();
    say("running…", "running");
    try {
      const response = await fetch("api/action/" + encodeURIComponent(panelId) + "/" + encodeURIComponent(action.id), {
        method: "POST",
        cache: "no-store",
        headers: { "Content-Type": "application/json", "X-Vidocq-Console-Token": token },
        body: JSON.stringify(body),
      });
      const type = response.headers.get("Content-Type") || "";
      const answer = type.startsWith("application/json") ? await response.json() : { text: await response.text() };
      if (response.status === 200 && typeof answer.result === "string") {
        say(answer.result, answer.error === true ? "failed" : "ok");
        output.replaceChildren(...resultOutput(answer));
      } else if (response.status === 500 && typeof answer.error === "string") say("failed: " + answer.error, "failed");
      else if (response.status === 202) say("still running after 60 s: the outcome will show here", "running");
      else if (response.status === 409) say("another action of this panel is running", "failed");
      else say("refused (" + response.status + ")" + (answer.text ? ": " + answer.text : ""), "failed");
    } catch (unreachable) {
      say("the console did not answer", "failed");
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

  return {
    id: action.id,
    group: typeof action.group === "string" && action.group ? action.group : null,
    text: [action.label, action.id, action.description].filter((t) => typeof t === "string").join(" ").toLowerCase(),
    root,
    /** Fills the form with a replayed call's arguments, by name; sends nothing. */
    fill(values) {
      for (const field of fields) if (Object.hasOwn(values, field.name)) field.fill(values[field.name]);
      reveal();
      root.scrollIntoView({ block: "nearest" });
      go.focus();
    },
    update(now) {
      if (!now || sending) return;
      busy(!!now.running);
      if (now.running) { say("running…", "running"); shown = null; return; }
      const last = now.last;
      if (last && typeof last.text === "string" && last.time !== shown) {
        shown = last.time;
        say((last.ok ? "" : "failed: ") + last.text + " · " + clockTime(last.time),
          last.ok && last.error !== true ? "ok" : "failed");
      }
    },
  };
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
  const acts = Array.isArray(panel.actions) ? "#" + panel.actions.map((a) => a.id).join(",") : "";
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
        if (holder) holder.replaceChildren(value.kind === "table" ? sampleTable(value, panel.id)
          : el("p", "absent", value.reason || "not available"));
      }
      for (const box of boxes) drawChart(box, panel.id, group, snapshot.console.time, pollMillis(snapshot));
    },
  };
}

function panelView(panel, snapshot) {
  panelArea.append(panelHead(panel.title || panel.id, panel.id + (panel.live ? " · live" : " · boot facts only")));
  if (panel.summary) panelArea.append(el("p", "summary", panel.summary));
  const opens = openButtons(panel.lines);
  if (opens) panelArea.append(opens);
  const actions = actionsBar(panel);
  if (actions) panelArea.append(actions.root);
  const anomalies = anomaliesOf(panel.id, snapshot);
  if (anomalies.length) {
    const list = el("div", "anoms");
    list.append(...anomalies.map(anomalyBox));
    panelArea.append(list);
  }
  const flags = el("div", "flags");
  const failure = el("div", "anom crit");
  failure.hidden = true;
  panelArea.append(failure, flags);

  const sample = panel.sample && !panel.sample.error ? panel.sample : null;
  const groups = sample ? sample.groups || [] : [];
  const charts = panel.charts || [];
  const scopes = [];
  if (sample) scopes.push(["", scopeView(panelArea, panel, "", sample.values || [], charts)]);

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
    const box = el("article", "group");
    const gh = el("div", "group-head");
    gh.append(el("h3", null, group.name));
    const facts = factsOf.get(group.name);
    if (facts.kind) gh.append(el("span", "kind", facts.kind));
    box.append(gh);
    scopes.push([group.name, scopeView(box, panel, group.name, group.values || [], charts)]);
    if (facts.lines.length) {
      const id = panel.id + "\u0000" + group.name;
      const details = el("details");
      details.open = page.openGroups.has(id) || (index === 0 && !page.closedGroups.has(id));
      details.addEventListener("toggle", () => {
        if (details.open) { page.openGroups.add(id); page.closedGroups.delete(id); }
        else { page.closedGroups.add(id); page.openGroups.delete(id); }
      });
      details.append(el("summary", null, "Boot facts"), ...linesBlock(facts.lines, null));
      box.append(details);
    }
    panelArea.append(box);
  });

  const rest = lines.filter((line, i) => !claimed.has(i));
  if (rest.length) {
    const block = el("section", "block");
    block.append(el("h3", null, "Boot facts"), ...linesBlock(rest, null));
    if (panel.truncated) block.append(el("p", "note", "The snapshot carries the first lines only."));
    panelArea.append(block);
  }

  return {
    update(current) {
      const now = current.panels.find((p) => p.id === panel.id);
      if (!now) return;
      if (actions) actions.update(now);
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
