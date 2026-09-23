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

/** A table of a sample, its columns and rows. */
function sampleTable(value) {
  const table = el("table", "ext");
  const head = el("tr");
  for (const column of value.columns || []) head.append(el("th", null, column));
  const thead = el("thead");
  thead.append(head);
  const body = el("tbody");
  for (const row of value.rows || []) {
    const tr = el("tr");
    for (const cell of row) tr.append(el("td", /^\d+$/.test(cell) ? "n" : null, cell));
    body.append(tr);
  }
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

/**
 * The actions a panel offers, in a dev launch only (the snapshot then carries console.actionToken): one button each,
 * with a field per argument, a list when the server named the values it accepts. A confirmation is asked inline,
 * never with the browser's blocking dialog, which stops the page and any automation. The request is a same-origin
 * fetch with the token of the boot; the page shows the result line, or the class of what the action threw, never
 * more.
 */
function actionsBar(panel) {
  const actions = Array.isArray(panel.actions) ? panel.actions : [];
  if (!actions.length) return null;
  const bar = el("div", "actions");
  const rows = actions.map((action) => actionRow(panel.id, action));
  bar.append(...rows.map((row) => row.root));
  return {
    root: bar,
    update(current) {
      const now = (current.actions || []);
      for (const row of rows) row.update(now.find((a) => a.id === row.id));
    },
  };
}

function actionRow(panelId, action) {
  const root = el("form", "action");
  root.noValidate = true;
  const fields = [];
  for (const argument of action.arguments || []) {
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
    fields.push(input);
    root.append(wrap);
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
  root.append(go, ask, message);

  let sending = false;
  let shown = null;            // the time of the outcome of the snapshot last shown; a newer one replaces the message
  const busy = (on) => { for (const c of [go, yes, no, ...fields]) c.disabled = on; };
  const say = (text, cls) => { message.textContent = text; message.className = "msg" + (cls ? " " + cls : ""); };
  const closeAsk = () => { ask.hidden = true; go.hidden = false; };

  async function send() {
    const token = page.snapshot && page.snapshot.console && page.snapshot.console.actionToken;
    if (typeof token !== "string") { say("No token: reload the page.", "failed"); return; }
    const body = {};
    for (const input of fields) body[input.name] = input.value;
    sending = true;
    busy(true);
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
      if (response.status === 200 && typeof answer.result === "string") say(answer.result, "ok");
      else if (response.status === 500 && typeof answer.error === "string") say("failed: " + answer.error, "failed");
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
    root,
    update(now) {
      if (!now || sending) return;
      busy(!!now.running);
      if (now.running) { say("running…", "running"); shown = null; return; }
      const last = now.last;
      if (last && typeof last.text === "string" && last.time !== shown) {
        shown = last.time;
        say((last.ok ? "" : "failed: ") + last.text + " · " + clockTime(last.time), last.ok ? "ok" : "failed");
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
        if (holder) holder.replaceChildren(value.kind === "table" ? sampleTable(value)
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
