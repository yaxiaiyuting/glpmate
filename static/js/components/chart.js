/**
 * chart.js — hand-rolled inline SVG charts (no chart library, no canvas).
 *
 * Exports:
 *   lineChart(opts)  -> SVGElement   big chart with grid, guides, markers
 *   sparkline(opts)  -> SVGElement   tiny trend line for list rows
 *   barGlyph(opts)   -> SVGElement   tiny bar strip for list rows
 *   ringGlyph(opts)  -> SVGElement   tiny progress ring for list rows
 *
 * Everything is pure: no layout measurement, no timers, no globals except a
 * gradient-id counter. Adversarial data (nulls, NaN, Infinity, strings,
 * unsorted timestamps, empty arrays) must never produce NaN in an attribute,
 * so every coordinate goes through `safe()`.
 */

import { svgEl } from '../dom.js';

let gradientSeq = 0;

const C = {
  line: 'rgba(255,255,255,0.06)',
  grid: 'rgba(255,255,255,0.10)',
  tick: '#6B757D',
  axis: 'rgba(255,255,255,0.14)',
  text: '#FFFFFF',
  text2: '#9AA5AD',
  text3: '#6B757D',
  chip: '#202830',
  bg: '#0B0F12',
};

/* ------------------------------------------------------------------ *
 * numeric guards
 * ------------------------------------------------------------------ */

function num(value) {
  const n = typeof value === 'number' ? value : parseFloat(value);
  return Number.isFinite(n) ? n : null;
}

function safe(value, fallback) {
  const n = typeof value === 'number' ? value : parseFloat(value);
  const base = Number.isFinite(n) ? n : fallback === undefined ? 0 : fallback;
  return Math.round(base * 100) / 100;
}

function timeOf(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' || value instanceof Date) {
    const ms = new Date(value).getTime();
    if (Number.isFinite(ms)) return ms;
  }
  return null;
}

function clamp(value, lo, hi) {
  if (!Number.isFinite(value)) return lo;
  return Math.max(lo, Math.min(hi, value));
}

function normalizeSeries(series) {
  const list = Array.isArray(series) ? series : [];
  const points = [];
  for (let i = 0; i < list.length; i += 1) {
    const item = list[i] && typeof list[i] === 'object' ? list[i] : {};
    const v = num(item.v !== undefined ? item.v : item.mg);
    if (v === null) continue;
    let t = timeOf(item.t);
    if (t === null) t = i;
    points.push({ t: t, v: v, item: item });
  }
  points.sort((a, b) => a.t - b.t);
  return points;
}

/* ------------------------------------------------------------------ *
 * lineChart
 * ------------------------------------------------------------------ */

/**
 * @param {object} opts see module docs / project README
 * @returns {SVGElement}
 */
export function lineChart(opts) {
  const o = opts || {};
  const width = num(o.width) || 360;
  const height = num(o.height) || 180;
  const pad = Object.assign({ top: 34, right: 10, bottom: 26, left: 10 }, o.pad || {});
  const color = typeof o.color === 'string' && o.color ? o.color : '#35C6E8';
  const valueFormat = typeof o.valueFormat === 'function' ? o.valueFormat : (v) => String(v);
  const tickFormat = typeof o.tickFormat === 'function' ? o.tickFormat : (t) => String(t);
  const curve = o.curve === 'straight' ? 'straight' : 'smooth';
  const fillArea = o.fillArea !== false;
  const points = normalizeSeries(o.series);

  const plotLeft = pad.left;
  const plotRight = Math.max(plotLeft + 1, width - pad.right);
  const plotTop = pad.top;
  const plotBottom = Math.max(plotTop + 1, height - pad.bottom);
  const plotW = plotRight - plotLeft;
  const plotH = plotBottom - plotTop;

  const root = svgEl('svg', {
    class: 'chart-svg',
    viewBox: `0 0 ${safe(width, 360)} ${safe(height, 180)}`,
    width: '100%',
    preserveAspectRatio: 'xMidYMid meet',
    role: 'img',
    'aria-label': typeof o.ariaLabel === 'string' ? o.ariaLabel : '趋势图',
  });
  root.appendChild(svgEl('title', { text: typeof o.ariaLabel === 'string' ? o.ariaLabel : '趋势图' }));

  // ---- empty state -------------------------------------------------
  if (!points.length) {
    root.appendChild(
      svgEl('line', {
        x1: safe(plotLeft), y1: safe(plotBottom), x2: safe(plotRight), y2: safe(plotBottom),
        stroke: C.axis, 'stroke-width': 1, 'stroke-dasharray': '2 6',
      })
    );
    root.appendChild(
      svgEl('text', {
        x: safe((plotLeft + plotRight) / 2), y: safe((plotTop + plotBottom) / 2),
        'text-anchor': 'middle', 'dominant-baseline': 'middle',
        fill: C.text3, 'font-size': 12, text: '暂无数据',
      })
    );
    return root;
  }

  // ---- scales ------------------------------------------------------
  const goal = num(o.goal);
  const gridLines = (Array.isArray(o.gridLines) ? o.gridLines : [])
    .map((g) => ({ v: num(g && g.v), label: g && g.label }))
    .filter((g) => g.v !== null);

  let minV = Math.min.apply(null, points.map((p) => p.v));
  let maxV = Math.max.apply(null, points.map((p) => p.v));
  // A far-away target (e.g. a goal weight much lower than the current one)
  // would flatten the curve, so by default the target does not widen the value
  // domain; it is only drawn when it happens to fall inside the visible range.
  if (goal !== null && o.goalInDomain === true) {
    minV = Math.min(minV, goal);
    maxV = Math.max(maxV, goal);
  }
  for (const g of gridLines) {
    minV = Math.min(minV, g.v);
    maxV = Math.max(maxV, g.v);
  }
  if (!Number.isFinite(minV) || !Number.isFinite(maxV)) {
    minV = 0;
    maxV = 1;
  }
  if (maxV - minV < 1e-9) {
    const pad0 = Math.abs(maxV) > 1 ? Math.abs(maxV) * 0.08 : 0.5;
    minV -= pad0;
    maxV += pad0;
  } else {
    const slack = (maxV - minV) * 0.12;
    minV -= slack;
    maxV += slack;
  }

  const tMin = points[0].t;
  const tMax = points[points.length - 1].t;
  const span = tMax - tMin;

  const xOf = (t) => {
    if (points.length === 1 || span <= 0) return (plotLeft + plotRight) / 2;
    return plotLeft + ((t - tMin) / span) * plotW;
  };
  const yOf = (v) => plotBottom - ((v - minV) / (maxV - minV)) * plotH;

  const scaled = points.map((p) => ({ x: clamp(xOf(p.t), plotLeft, plotRight), y: clamp(yOf(p.v), plotTop, plotBottom), p: p }));

  // ---- defs: area gradient ----------------------------------------
  if (fillArea) {
    gradientSeq += 1;
    const id = 'chartgrad-' + gradientSeq;
    const gradient = svgEl(
      'linearGradient',
      { id: id, x1: '0', y1: '0', x2: '0', y2: '1' },
      svgEl('stop', { offset: '0%', 'stop-color': color, 'stop-opacity': '0.30' }),
      svgEl('stop', { offset: '100%', 'stop-color': color, 'stop-opacity': '0' })
    );
    root.appendChild(svgEl('defs', null, gradient));
    const areaPath = buildPath(scaled, curve, true, plotBottom);
    if (areaPath) {
      root.appendChild(
        svgEl('path', {
          class: 'chart-area',
          d: areaPath,
          fill: `url(#${id})`,
          stroke: 'none',
        })
      );
    }
  }

  // ---- dashed horizontal grid + labels -----------------------------
  const gridValues = [];
  if (gridLines.length) {
    for (const g of gridLines) gridValues.push({ v: g.v, label: g.label });
  } else {
    const ticks = Math.max(2, Math.min(5, num(o.yTicks) || 3));
    for (let i = 0; i < ticks; i += 1) {
      const v = minV + ((maxV - minV) * i) / (ticks - 1);
      gridValues.push({ v: v, label: valueFormat(v) });
    }
  }
  for (const g of gridValues) {
    const y = clamp(yOf(g.v), plotTop, plotBottom);
    root.appendChild(
      svgEl('line', {
        class: 'chart-grid',
        x1: safe(plotLeft), y1: safe(y), x2: safe(plotRight), y2: safe(y),
        stroke: C.grid, 'stroke-width': 1, 'stroke-dasharray': '2 6',
      })
    );
    if (g.label !== undefined && g.label !== null && g.label !== '') {
      root.appendChild(
        svgEl('text', {
          x: safe(plotLeft), y: safe(y - 4),
          'text-anchor': 'start', fill: C.text3, 'font-size': 9.5, text: String(g.label),
        })
      );
    }
  }

  // baseline (bottom axis)
  root.appendChild(
    svgEl('line', {
      class: 'chart-baseline',
      x1: safe(plotLeft), y1: safe(plotBottom), x2: safe(plotRight), y2: safe(plotBottom),
      stroke: C.line, 'stroke-width': 1, 'stroke-dasharray': '2 6',
    })
  );

  // ---- goal line (only when it is inside the visible value range) ----
  if (goal !== null && goal >= minV && goal <= maxV) {
    const y = clamp(yOf(goal), plotTop, plotBottom);
    root.appendChild(
      svgEl('line', {
        class: 'chart-goal',
        x1: safe(plotLeft), y1: safe(y), x2: safe(plotRight), y2: safe(y),
        stroke: C.text3, 'stroke-width': 1, 'stroke-dasharray': '4 5',
      })
    );
    root.appendChild(
      svgEl('text', {
        x: safe(plotLeft), y: safe(y - 4), 'text-anchor': 'start',
        fill: C.text3, 'font-size': 9.5, text: '目标 ' + String(o.goalLabel || valueFormat(goal)),
      })
    );
  }

  // ---- vertical dotted guides --------------------------------------
  const guides = (Array.isArray(o.guides) ? o.guides : []).slice(0, 40);
  for (const g of guides) {
    const t = timeOf(g && g.t !== undefined ? g.t : g);
    if (t === null || span <= 0) continue;
    const x = clamp(xOf(t), plotLeft, plotRight);
    root.appendChild(
      svgEl('line', {
        class: 'chart-guide',
        x1: safe(x), y1: safe(plotTop - 6), x2: safe(x), y2: safe(plotBottom),
        stroke: 'rgba(255,255,255,0.14)', 'stroke-width': 1, 'stroke-dasharray': '1 5',
      })
    );
  }

  // ---- the line ----------------------------------------------------
  const linePath = buildPath(scaled, curve, false);
  if (linePath) {
    root.appendChild(
      svgEl('path', {
        class: 'chart-line' + (o.animate === false ? '' : ' chart-svg--animated'),
        d: linePath,
        fill: 'none',
        stroke: color,
        'stroke-width': 2,
        'stroke-linecap': 'round',
        'stroke-linejoin': 'round',
      })
    );
  }

  // ---- markers -----------------------------------------------------
  const markers = (Array.isArray(o.markers) ? o.markers : []).slice(0, 6);
  const usedIndex = new Set();
  const drawn = [];

  const drawMarker = (point, lines, emphasize) => {
    if (!point) return;
    root.appendChild(
      svgEl('circle', { cx: safe(point.x), cy: safe(point.y), r: 9, fill: color, opacity: 0.18 })
    );
    root.appendChild(
      svgEl('circle', {
        class: 'chart-point',
        cx: safe(point.x), cy: safe(point.y),
        r: emphasize ? 4.6 : 3.6,
        fill: color,
        stroke: 'rgba(255,255,255,0.92)',
        'stroke-width': 2,
      })
    );
    if (lines && lines.length) drawn.push({ point: point, lines: lines });
  };

  for (const marker of markers) {
    const t = timeOf(marker && marker.t);
    let best = null;
    let bestDist = Infinity;
    for (let i = 0; i < scaled.length; i += 1) {
      const d = t === null ? (i === scaled.length - 1 ? 0 : Infinity) : Math.abs(scaled[i].p.t - t);
      if (d < bestDist) {
        bestDist = d;
        best = i;
      }
    }
    const lines = (marker && marker.lines) || [];
    if (best !== null && bestDist < Infinity) {
      usedIndex.add(best);
      drawMarker(scaled[best], lines, true);
      // dotted guide through the marker
      root.appendChild(
        svgEl('line', {
          class: 'chart-guide',
          x1: safe(scaled[best].x), y1: safe(plotTop - 6), x2: safe(scaled[best].x), y2: safe(plotBottom),
          stroke: 'rgba(255,255,255,0.18)', 'stroke-width': 1, 'stroke-dasharray': '1 5',
        })
      );
    }
  }

  // always emphasise the latest point
  const lastIndex = scaled.length - 1;
  if (!usedIndex.has(lastIndex)) drawMarker(scaled[lastIndex], o.lastLines || null, true);

  // annotation chips (drawn after points so they sit on top)
  for (const item of drawn) drawAnnotation(root, item.point, item.lines, width, plotTop);

  // ---- x axis ticks ------------------------------------------------
  const tickCount = Math.max(2, Math.min(8, num(o.xTicks) || 4));
  const total = scaled.length;
  const seen = new Set();
  const candidates = [];
  for (let i = 0; i < tickCount; i += 1) {
    const ratio = tickCount === 1 ? 0 : i / (tickCount - 1);
    const index = clamp(Math.round(ratio * (total - 1)), 0, total - 1);
    if (seen.has(index)) continue;
    seen.add(index);
    const point = scaled[index];
    if (!point) continue;
    const value = String(tickFormat(point.p.t, point.p.item));
    const anchor = i === 0 ? 'start' : i === tickCount - 1 ? 'end' : 'middle';
    const x = clamp(point.x, plotLeft, plotRight);
    const width_ = textWidth(value, 10);
    const left = anchor === 'start' ? x : anchor === 'end' ? x - width_ : x - width_ / 2;
    candidates.push({ x: x, anchor: anchor, value: value, left: left, right: left + width_ });
  }
  // drop labels that would collide; the last (newest) label always wins
  const placed = [];
  for (let i = 0; i < candidates.length; i += 1) {
    const candidate = candidates[i];
    if (candidate.left < plotLeft - 1) {
      candidate.left = plotLeft - 1;
      candidate.right = candidate.left + textWidth(candidate.value, 10);
      candidate.anchor = 'start';
      candidate.x = plotLeft;
    }
    const previous = placed[placed.length - 1];
    if (previous && candidate.left < previous.right + 8) {
      if (i === candidates.length - 1) placed.pop();
      else continue;
    }
    placed.push(candidate);
  }
  for (const tick of placed) {
    root.appendChild(
      svgEl('text', {
        class: 'chart-tick',
        x: safe(tick.x),
        y: safe(plotBottom + 15),
        'text-anchor': tick.anchor,
        fill: C.tick,
        'font-size': 10,
        text: tick.value,
      })
    );
  }

  return root;
}

/* ------------------------------------------------------------------ *
 * path builders
 * ------------------------------------------------------------------ */

function buildPath(points, curve, closeArea, baseline) {
  if (!points.length) return '';
  if (points.length === 1) {
    const p = points[0];
    // a dot cannot be a line; give the area path a sliver so it stays visible
    if (!closeArea) return `M${safe(p.x)} ${safe(p.y)} L${safe(p.x)} ${safe(p.y)}`;
    return '';
  }
  let d = `M${safe(points[0].x)} ${safe(points[0].y)}`;
  if (curve === 'straight') {
    for (let i = 1; i < points.length; i += 1) {
      d += ` L${safe(points[i].x)} ${safe(points[i].y)}`;
    }
  } else {
    const tension = 0.2;
    for (let i = 0; i < points.length - 1; i += 1) {
      const p0 = points[i - 1] || points[i];
      const p1 = points[i];
      const p2 = points[i + 1];
      const p3 = points[i + 2] || points[i + 1];
      const c1x = p1.x + (p2.x - p0.x) * tension;
      const c1y = p1.y + (p2.y - p0.y) * tension;
      const c2x = p2.x - (p3.x - p1.x) * tension;
      const c2y = p2.y - (p3.y - p1.y) * tension;
      d += ` C${safe(c1x)} ${safe(c1y)}, ${safe(c2x)} ${safe(c2y)}, ${safe(p2.x)} ${safe(p2.y)}`;
    }
  }
  if (closeArea) {
    const first = points[0];
    const last = points[points.length - 1];
    // close the area on the supplied baseline so the fill can never bleed
    // outside the chart box (overflow is hidden on .chart-svg)
    const base = Number.isFinite(baseline) ? baseline : Math.max(first.y, last.y);
    d += ` L${safe(last.x)} ${safe(base)} L${safe(first.x)} ${safe(base)} Z`;
  }
  return d;
}

function textWidth(value, size) {
  const str = String(value === null || value === undefined ? '' : value);
  let units = 0;
  for (const ch of str) units += ch.charCodeAt(0) > 0x2e80 ? 1 : 0.56;
  return units * size;
}

function drawAnnotation(root, point, lines, width, plotTop) {
  const list = lines.filter((l) => l !== null && l !== undefined && l !== '').slice(0, 3);
  if (!list.length) return;

  const headSize = 11;
  const subSize = 9.5;
  let boxW = 0;
  let boxH = 6;
  list.forEach((raw, i) => {
    const size = i === 0 ? headSize : subSize;
    boxW = Math.max(boxW, textWidth(raw, size));
    boxH += (i === 0 ? headSize : subSize) + 3;
  });
  boxW = Math.min(width - 4, boxW + 16);
  boxH = Math.min(58, boxH);

  let x = clamp(point.x - boxW / 2, 2, Math.max(2, width - boxW - 2));
  let y = point.y - 12 - boxH;
  if (y < 2) y = Math.min(point.y + 12, plotTop);

  root.appendChild(
    svgEl('rect', {
      class: 'chart-annotation',
      x: safe(x), y: safe(y), width: safe(boxW), height: safe(boxH),
      rx: 8, fill: C.chip, stroke: 'rgba(255,255,255,0.06)', 'stroke-width': 1,
    })
  );

  let cursor = y + 4;
  list.forEach((raw, i) => {
    const size = i === 0 ? headSize : subSize;
    cursor += size;
    root.appendChild(
      svgEl('text', {
        x: safe(x + boxW / 2),
        y: safe(cursor),
        'text-anchor': 'middle',
        fill: i === 0 ? C.text : C.text2,
        'font-size': size,
        'font-weight': i === 0 ? 600 : 400,
        text: String(raw),
      })
    );
    cursor += 3;
  });
}

/* ------------------------------------------------------------------ *
 * small glyph charts
 * ------------------------------------------------------------------ */

export function sparkline(opts) {
  const o = opts || {};
  const width = num(o.width) || 68;
  const height = num(o.height) || 22;
  const color = typeof o.color === 'string' && o.color ? o.color : '#3DBFA0';
  const values = (Array.isArray(o.values) ? o.values : []).map(num).filter((v) => v !== null);

  const root = svgEl('svg', {
    class: 'spark-svg',
    viewBox: `0 0 ${safe(width, 68)} ${safe(height, 22)}`,
    width: safe(width, 68),
    height: safe(height, 22),
    'aria-hidden': 'true',
    focusable: 'false',
  });
  if (values.length < 2) return root;

  let min = Math.min.apply(null, values);
  let max = Math.max.apply(null, values);
  if (max - min < 1e-9) {
    min -= 0.5;
    max += 0.5;
  }
  const stepX = width / (values.length - 1);
  const pts = values.map((v, i) => ({
    x: clamp(i * stepX, 0, width),
    y: clamp(height - 2 - ((v - min) / (max - min)) * (height - 4), 1, height - 1),
  }));

  if (o.fillArea !== false) {
    gradientSeq += 1;
    const id = 'sparkgrad-' + gradientSeq;
    root.appendChild(
      svgEl(
        'defs',
        null,
        svgEl(
          'linearGradient',
          { id: id, x1: '0', y1: '0', x2: '0', y2: '1' },
          svgEl('stop', { offset: '0%', 'stop-color': color, 'stop-opacity': '0.3' }),
          svgEl('stop', { offset: '100%', 'stop-color': color, 'stop-opacity': '0' })
        )
      )
    );
    root.appendChild(svgEl('path', { d: buildPath(pts, 'smooth', true, height), fill: `url(#${id})`, stroke: 'none' }));
  }
  root.appendChild(
    svgEl('path', {
      d: buildPath(pts, 'smooth', false),
      fill: 'none',
      stroke: color,
      'stroke-width': 1.6,
      'stroke-linecap': 'round',
      'stroke-linejoin': 'round',
    })
  );
  const last = pts[pts.length - 1];
  root.appendChild(svgEl('circle', { cx: safe(last.x), cy: safe(last.y), r: 1.8, fill: color }));
  return root;
}

export function barGlyph(opts) {
  const o = opts || {};
  const width = num(o.width) || 68;
  const height = num(o.height) || 22;
  const color = typeof o.color === 'string' && o.color ? o.color : '#4A9BE8';
  const raw = (Array.isArray(o.values) ? o.values : []).slice(0, 7).map((v) => num(v) || 0);
  const values = raw.length ? raw : [0];
  const max = Math.max(num(o.max) || 0, Math.max.apply(null, values), 1);

  const root = svgEl('svg', {
    class: 'spark-svg',
    viewBox: `0 0 ${safe(width, 68)} ${safe(height, 22)}`,
    width: safe(width, 68),
    height: safe(height, 22),
    'aria-hidden': 'true',
    focusable: 'false',
  });
  const gap = values.length > 1 ? 3 : 0;
  const barW = Math.max(2, (width - gap * (values.length - 1)) / values.length);
  values.forEach((v, i) => {
    const h = clamp((v / max) * (height - 2), v > 0 ? 2 : 1, height - 1);
    root.appendChild(
      svgEl('rect', {
        x: safe(i * (barW + gap)),
        y: safe(height - h),
        width: safe(barW),
        height: safe(h),
        rx: 1.5,
        fill: v > 0 ? color : 'rgba(255,255,255,0.10)',
      })
    );
  });
  return root;
}

export function ringGlyph(opts) {
  const o = opts || {};
  const size = num(o.size) || 22;
  const stroke = num(o.stroke) || 3;
  const color = typeof o.color === 'string' && o.color ? o.color : '#35C6E8';
  const pct = clamp(num(o.pct) || 0, 0, 1);
  const r = (size - stroke) / 2;
  const circumference = 2 * Math.PI * r;

  const root = svgEl('svg', {
    class: 'ring-svg',
    viewBox: `0 0 ${safe(size, 22)} ${safe(size, 22)}`,
    width: safe(size, 22),
    height: safe(size, 22),
    'aria-hidden': 'true',
    focusable: 'false',
  });
  root.appendChild(
    svgEl('circle', {
      cx: size / 2, cy: size / 2, r: safe(r, 1),
      fill: 'none', stroke: 'rgba(255,255,255,0.10)', 'stroke-width': safe(stroke, 3),
    })
  );
  if (pct > 0) {
    root.appendChild(
      svgEl('circle', {
        cx: size / 2, cy: size / 2, r: safe(r, 1),
        fill: 'none', stroke: color, 'stroke-width': safe(stroke, 3), 'stroke-linecap': 'round',
        'stroke-dasharray': `${safe(circumference * pct)} ${safe(circumference)}`,
        transform: `rotate(-90 ${safe(size / 2)} ${safe(size / 2)})`,
      })
    );
  }
  return root;
}

export default { lineChart, sparkline, barGlyph, ringGlyph };
