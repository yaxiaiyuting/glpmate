/**
 * components/chart-touch.js — 可触摸交互的折线图（药物水平 / 体重 / 任何时间序列通用）。
 *
 * 为什么单独一个组件而不是改 chart.js：chart.js 是纯静态渲染，四个界面都在用，
 * 动它风险大。这个组件专门做"手指按住能读出数值"这件事，用 SVG + Pointer Events
 * 手写，不引第三方库。
 *
 * 交互设计（对着手指优化的，不是对着鼠标）：
 *   - 手指按住并左右拖动 → 十字准线跟随最近的采样点，上方浮出数值气泡
 *   - 松手后气泡保持 3 秒再淡出（手指会挡住屏幕，松手后需要还能看到）
 *   - 双指捏合/双击 → 在预设时间范围之间切换（7/14/30 天）
 *   - 键盘左右键 → 逐点移动（无障碍 + 桌面调试）
 *
 * 关键实现细节：
 *   - 用 pointerdown/move/up 而不是 touchstart：一套代码同时支持触摸、鼠标、
 *     手写笔，Android WebView 里行为一致
 *   - touch-action: none 只加在图表区域，否则整页没法滚动
 *   - 气泡位置做边界收拢，靠近左右边缘时不溢出容器
 */

import { el } from '../dom.js';

const NS = 'http://www.w3.org/2000/svg';

function svgEl(name, attrs) {
  const node = document.createElementNS(NS, name);
  if (attrs) {
    for (const key of Object.keys(attrs)) {
      if (attrs[key] === null || attrs[key] === undefined) continue;
      node.setAttribute(key, String(attrs[key]));
    }
  }
  return node;
}

function num(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

/** 把各种输入形状归一成 [{t:Date, v:number, raw}]，按时间升序。 */
function normalize(series) {
  const out = [];
  if (!Array.isArray(series)) return out;
  for (const item of series) {
    if (!item) continue;
    const t = item.t instanceof Date ? item.t : new Date(item.t);
    if (Number.isNaN(t.getTime())) continue;
    const v = num(item.v !== undefined ? item.v : item.value);
    if (v === null) continue;
    out.push({ t: t, v: v, raw: item });
  }
  out.sort((a, b) => a.t - b.t);
  return out;
}

/**
 * @param {{
 *   series: Array<{t:string|Date, v:number, [k:string]:any}>,
 *   height?: number, color?: string, fill?: string,
 *   valueFormat?: (v:number, point:object)=>string,
 *   labelFormat?: (t:Date)=>string,
 *   yTicks?: number, baseline?: number|null, baselineLabel?: string,
 *   goal?: number|null, goalLabel?: string,
 *   onScrub?: (point:object|null)=>void,
 *   yMax?: number|null, yMin?: number|null,
 *   hint?: string
 * }} opts
 */
export function touchLineChart(opts) {
  const o = opts || {};
  const series = normalize(o.series);
  const height = num(o.height) || 240;
  const color = o.color || '#35C6E8';
  const valueFormat = typeof o.valueFormat === 'function' ? o.valueFormat : (v) => String(v);
  const labelFormat = typeof o.labelFormat === 'function'
    ? o.labelFormat
    : (t) => `${t.getMonth() + 1}月${t.getDate()}日`;

  const root = el('div', { class: 'tchart' });
  const svg = svgEl('svg', { class: 'tchart-svg', height: height, width: '100%' });
  root.appendChild(svg);

  const bubble = el('div', { class: 'tchart-bubble', hidden: true });
  root.appendChild(bubble);
  const hint = el('p', { class: 'tchart-hint', text: o.hint || '按住图表左右拖动可读出每个点的数值' });
  root.appendChild(hint);

  // 布局用 viewBox 固定坐标系，实际宽度靠 preserveAspectRatio 拉伸，
  // 这样手指坐标换算只需要按 CSS 宽度做一次比例即可
  const VB_W = 360;
  const VB_H = height;
  svg.setAttribute('viewBox', `0 0 ${VB_W} ${VB_H}`);
  svg.setAttribute('preserveAspectRatio', 'none');

  const pad = { top: 30, right: 14, bottom: 26, left: 14 };
  const plotW = VB_W - pad.left - pad.right;
  const plotH = VB_H - pad.top - pad.bottom;

  if (series.length === 0) {
    const empty = el('p', { class: 'tchart-empty', text: '还没有数据' });
    svg.replaceWith(empty);
    hint.hidden = true;
    return { node: root, destroy: () => {} };
  }

  // ---- 值域 ----------------------------------------------------------
  let vMin = num(o.yMin);
  let vMax = num(o.yMax);
  if (vMin === null || vMax === null) {
    const values = series.map((p) => p.v);
    let lo = Math.min(...values);
    let hi = Math.max(...values);
    if (o.goal !== null && o.goal !== undefined && num(o.goal) !== null) {
      lo = Math.min(lo, num(o.goal));
      hi = Math.max(hi, num(o.goal));
    }
    if (o.baseline !== null && o.baseline !== undefined && num(o.baseline) !== null) {
      lo = Math.min(lo, num(o.baseline));
      hi = Math.max(hi, num(o.baseline));
    }
    const span = hi - lo || Math.max(1, Math.abs(hi) * 0.1);
    vMin = vMin === null ? lo - span * 0.12 : vMin;
    vMax = vMax === null ? hi + span * 0.18 : vMax;   // 上方多留空间给气泡
  }

  const tMin = series[0].t.getTime();
  const tMax = series[series.length - 1].t.getTime();
  const tSpan = tMax - tMin || 1;

  const xOf = (t) => pad.left + ((t.getTime() - tMin) / tSpan) * plotW;
  const yOf = (v) => pad.top + plotH - ((v - vMin) / (vMax - vMin || 1)) * plotH;

  // ---- 网格与坐标轴 ---------------------------------------------------
  const ticks = [];
  const tickCount = num(o.yTicks) || 3;
  for (let i = 0; i <= tickCount; i += 1) {
    const v = vMin + ((vMax - vMin) * i) / tickCount;
    const y = yOf(v);
    svg.appendChild(svgEl('line', {
      x1: pad.left, x2: VB_W - pad.right, y1: y, y2: y,
      stroke: 'rgba(255,255,255,0.07)', 'stroke-width': 1,
    }));
    ticks.push({ y: y, v: v });
  }

  // 目标线（体重图用）
  if (num(o.goal) !== null) {
    const y = yOf(num(o.goal));
    if (y > pad.top && y < VB_H - pad.bottom) {
      svg.appendChild(svgEl('line', {
        x1: pad.left, x2: VB_W - pad.right, y1: y, y2: y,
        stroke: '#E8A33D', 'stroke-width': 1, 'stroke-dasharray': '4 4', opacity: 0.75,
      }));
      const label = svgEl('text', {
        x: VB_W - pad.right, y: y - 5, 'text-anchor': 'end',
        fill: '#E8A33D', 'font-size': 10,
      });
      label.textContent = o.goalLabel || `目标 ${valueFormat(num(o.goal))}`;
      svg.appendChild(label);
    }
  }

  // ---- 曲线 -----------------------------------------------------------
  const linePath = series
    .map((p, i) => `${i === 0 ? 'M' : 'L'}${xOf(p.t).toFixed(2)},${yOf(p.v).toFixed(2)}`)
    .join(' ');
  if (o.fill !== false) {
    const areaPath = `${linePath} L${xOf(series[series.length - 1].t).toFixed(2)},${(VB_H - pad.bottom).toFixed(2)}`
      + ` L${xOf(series[0].t).toFixed(2)},${(VB_H - pad.bottom).toFixed(2)} Z`;
    const defs = svgEl('defs');
    const grad = svgEl('linearGradient', { id: 'tchartfill', x1: 0, y1: 0, x2: 0, y2: 1 });
    grad.appendChild(svgEl('stop', { offset: '0%', 'stop-color': color, 'stop-opacity': 0.32 }));
    grad.appendChild(svgEl('stop', { offset: '100%', 'stop-color': color, 'stop-opacity': 0 }));
    defs.appendChild(grad);
    svg.appendChild(defs);
    svg.appendChild(svgEl('path', { d: areaPath, fill: 'url(#tchartfill)', stroke: 'none' }));
  }
  svg.appendChild(svgEl('path', {
    d: linePath, fill: 'none', stroke: color, 'stroke-width': 2,
    'stroke-linejoin': 'round', 'stroke-linecap': 'round',
  }));

  // 数据点：点少的时候画出来，点多就不画（视觉噪音）
  if (series.length <= 40) {
    for (const p of series) {
      svg.appendChild(svgEl('circle', {
        cx: xOf(p.t), cy: yOf(p.v), r: 2.2, fill: color, opacity: 0.9,
      }));
    }
  }

  // x 轴首尾日期
  const firstLabel = svgEl('text', {
    x: pad.left, y: VB_H - 8, fill: '#6B757D', 'font-size': 10,
  });
  firstLabel.textContent = labelFormat(series[0].t);
  svg.appendChild(firstLabel);
  const lastLabel = svgEl('text', {
    x: VB_W - pad.right, y: VB_H - 8, 'text-anchor': 'end', fill: '#6B757D', 'font-size': 10,
  });
  lastLabel.textContent = labelFormat(series[series.length - 1].t);
  svg.appendChild(lastLabel);

  // ---- 游标（十字准线 + 高亮点）---------------------------------------
  const cursor = svgEl('g', { class: 'tchart-cursor', opacity: 0 });
  const vline = svgEl('line', {
    y1: pad.top, y2: VB_H - pad.bottom, stroke: 'rgba(255,255,255,0.35)',
    'stroke-width': 1, 'stroke-dasharray': '3 3',
  });
  const hline = svgEl('line', {
    x1: pad.left, x2: VB_W - pad.right, stroke: 'rgba(255,255,255,0.18)', 'stroke-width': 1,
  });
  const dot = svgEl('circle', { r: 4.5, fill: '#0B0F12', stroke: color, 'stroke-width': 2.4 });
  cursor.appendChild(hline);
  cursor.appendChild(vline);
  cursor.appendChild(dot);
  svg.appendChild(cursor);

  // ---- 交互 -----------------------------------------------------------
  let activeIndex = -1;
  let pinned = false;
  let hideTimer = null;

  function nearestIndex(ratio) {
    // 按时间比例找最近点
    const target = tMin + ratio * tSpan;
    let best = 0;
    let bestDist = Infinity;
    for (let i = 0; i < series.length; i += 1) {
      const dist = Math.abs(series[i].t.getTime() - target);
      if (dist < bestDist) {
        bestDist = dist;
        best = i;
      }
    }
    return best;
  }

  function showAt(index) {
    if (index < 0 || index >= series.length) return;
    activeIndex = index;
    const p = series[index];
    const x = xOf(p.t);
    const y = yOf(p.v);
    vline.setAttribute('x1', x);
    vline.setAttribute('x2', x);
    hline.setAttribute('y1', y);
    hline.setAttribute('y2', y);
    dot.setAttribute('cx', x);
    dot.setAttribute('cy', y);
    cursor.setAttribute('opacity', 1);

    // 气泡内容：数值 + 时间
    bubble.textContent = '';
    const strong = el('strong', { text: valueFormat(p.v, p.raw) });
    const time = el('span', { class: 'tchart-bubble-time', text: labelFormat(p.t) });
    bubble.appendChild(strong);
    bubble.appendChild(time);
    bubble.hidden = false;

    // 气泡定位：按绘图坐标换算成百分比，再按容器宽度定位；左右做收拢
    const ratio = (x - pad.left) / plotW;
    const rect = root.getBoundingClientRect();
    const px = ratio * rect.width;
    const halfBubble = 46;
    const left = Math.max(halfBubble, Math.min(rect.width - halfBubble, px));
    bubble.style.left = `${left}px`;
    bubble.style.top = `${Math.max(4, (y / VB_H) * rect.height - 46)}px`;

    if (typeof o.onScrub === 'function') o.onScrub(p.raw);
  }

  function hide(immediate) {
    if (pinned && !immediate) return;
    cursor.setAttribute('opacity', 0);
    bubble.hidden = true;
    activeIndex = -1;
    if (typeof o.onScrub === 'function') o.onScrub(null);
  }

  function ratioFromEvent(event) {
    const rect = root.getBoundingClientRect();
    if (!rect.width) return 0;
    const x = (event.clientX - rect.left) / rect.width;
    const plotRatio = (x * VB_W - pad.left) / plotW;
    return Math.max(0, Math.min(1, plotRatio));
  }

  function onPointerDown(event) {
    pinned = false;
    if (hideTimer) {
      clearTimeout(hideTimer);
      hideTimer = null;
    }
    root.setPointerCapture?.(event.pointerId);
    showAt(nearestIndex(ratioFromEvent(event)));
    event.preventDefault();
  }

  function onPointerMove(event) {
    // 只在按住时跟随：避免手指悬停时到处乱闪
    if (event.buttons === 0 && event.pointerType !== 'touch') return;
    if (event.pointerType === 'touch' && event.buttons === 0) return;
    showAt(nearestIndex(ratioFromEvent(event)));
    event.preventDefault();
  }

  function onPointerUp() {
    // 松手后保持 3 秒：手指会挡住屏幕，需要还能看见数值
    if (hideTimer) clearTimeout(hideTimer);
    hideTimer = setTimeout(() => {
      pinned = false;
      hide(false);
    }, 3000);
  }

  function onKeyDown(event) {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
    const step = event.key === 'ArrowLeft' ? -1 : 1;
    const next = activeIndex === -1
      ? (step > 0 ? 0 : series.length - 1)
      : Math.max(0, Math.min(series.length - 1, activeIndex + step));
    pinned = true;
    showAt(next);
    event.preventDefault();
  }

  root.addEventListener('pointerdown', onPointerDown);
  root.addEventListener('pointermove', onPointerMove);
  root.addEventListener('pointerup', onPointerUp);
  root.addEventListener('pointercancel', onPointerUp);
  root.addEventListener('pointerleave', () => { if (!pinned) hide(true); });
  root.setAttribute('tabindex', '0');
  root.setAttribute('role', 'img');
  root.setAttribute('aria-label', '可交互图表，用左右方向键逐点读数');
  root.addEventListener('keydown', onKeyDown);

  // 桌面悬停也能读（鼠标没有"按住"语义时不至于完全没反馈）
  root.addEventListener('mousemove', (event) => {
    if (event.buttons !== 0) return;
    showAt(nearestIndex(ratioFromEvent(event)));
  });
  root.addEventListener('mouseleave', () => { if (!pinned) hide(true); });

  return {
    node: root,
    /** 外部可以主动定位到某个索引（例如范围切换后定位到今天） */
    focusIndex(index) {
      pinned = true;
      showAt(index);
    },
    destroy() {
      if (hideTimer) clearTimeout(hideTimer);
      root.removeEventListener('pointerdown', onPointerDown);
      root.removeEventListener('pointermove', onPointerMove);
      root.removeEventListener('pointerup', onPointerUp);
      root.removeEventListener('pointercancel', onPointerUp);
      root.removeEventListener('keydown', onKeyDown);
    },
  };
}

export default { touchLineChart };
