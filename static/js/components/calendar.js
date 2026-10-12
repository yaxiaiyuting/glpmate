/**
 * calendar.js — month grid + local-time date helpers.
 *
 * Exports: DOT_COLORS, DOT_LABELS, dotColor, dotLabel, WEEKDAYS, MONTHS,
 *          monthGrid, shiftMonth, formatMonthTitle, toDateKey, fromDateKey,
 *          formatDayTitle, addDays, monthRange.
 *
 * All dates are handled in LOCAL time; `toISOString()` is never used because
 * it shifts across the UTC boundary and would render the wrong day.
 */

import { el } from '../dom.js';

export const DOT_COLORS = {
  dose: '#E8A33D',
  weight: '#3DBFA0',
  food: '#E8607A',
  water: '#4A9BE8',
  training: '#3DBFA0',
  side_effect: '#8B7BD8',
  symptom: '#8B7BD8',
  sleep: '#8B7BD8',
  lab: '#4A9BE8',
  note: '#9AA5AD',
};

export const DOT_LABELS = {
  dose: '剂量',
  weight: '体重',
  food: '餐食',
  water: '饮水',
  training: '训练',
  side_effect: '副作用',
  symptom: '副作用',
  sleep: '睡眠',
  lab: '化验',
  note: '备注',
};

export const DOT_FALLBACK = '#6B757D';

export const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日'];

export const MONTHS = ['一月', '二月', '三月', '四月', '五月', '六月', '七月', '八月', '九月', '十月', '十一月', '十二月'];

export const MONTH_RE = /^\d{4}-\d{2}$/;
export const DAY_RE = /^\d{4}-\d{2}-\d{2}$/;

export function dotColor(kind) {
  return DOT_COLORS[kind] || DOT_FALLBACK;
}

export function dotLabel(kind) {
  return DOT_LABELS[kind] || String(kind === undefined || kind === null ? '' : kind);
}

/* ------------------------------------------------------------------ *
 * date helpers
 * ------------------------------------------------------------------ */

function pad2(n) {
  return (n < 10 ? '0' : '') + n;
}

/** 'YYYY-MM-DD' for a Date, in local time. */
export function toDateKey(date) {
  const d = date instanceof Date && Number.isFinite(date.getTime()) ? date : new Date();
  return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
}

/** Local midnight Date for 'YYYY-MM-DD' (invalid input -> today). */
export function fromDateKey(key) {
  if (typeof key === 'string' && DAY_RE.test(key)) {
    const parts = key.split('-').map(Number);
    const d = new Date(parts[0], parts[1] - 1, parts[2]);
    if (Number.isFinite(d.getTime())) return d;
  }
  const now = new Date();
  return new Date(now.getFullYear(), now.getMonth(), now.getDate());
}

export function addDays(key, amount) {
  const d = fromDateKey(key);
  const n = Number.isFinite(amount) ? Math.round(amount) : 0;
  return toDateKey(new Date(d.getFullYear(), d.getMonth(), d.getDate() + n));
}

/** Pure month arithmetic: ('2026-01', -1) -> '2025-12' */
export function shiftMonth(month, delta) {
  if (typeof month !== 'string' || !MONTH_RE.test(month)) return month;
  const parts = month.split('-').map(Number);
  const n = Number.isFinite(delta) ? Math.round(delta) : 0;
  const total = parts[0] * 12 + (parts[1] - 1) + n;
  const year = Math.floor(total / 12);
  const m = total - year * 12;
  return year + '-' + pad2(m + 1);
}

/** '2026-10' -> '十月 2026' */
export function formatMonthTitle(month) {
  if (typeof month !== 'string' || !MONTH_RE.test(month)) return String(month === undefined || month === null ? '' : month);
  const parts = month.split('-').map(Number);
  return MONTHS[parts[1] - 1] + ' ' + parts[0];
}

/** '2026-10-12' -> '周一, 12 十月' */
export function formatDayTitle(key) {
  if (typeof key !== 'string' || !DAY_RE.test(key)) return String(key === undefined || key === null ? '' : key);
  const d = fromDateKey(key);
  const weekday = WEEKDAYS[(d.getDay() + 6) % 7];
  return '周' + weekday + ', ' + d.getDate() + ' ' + MONTHS[d.getMonth()];
}

/** Inclusive first/last day of a month. */
export function monthRange(month) {
  if (typeof month !== 'string' || !MONTH_RE.test(month)) {
    const today = toDateKey(new Date());
    return monthRange(today.slice(0, 7));
  }
  const parts = month.split('-').map(Number);
  const start = month + '-01';
  const last = new Date(parts[0], parts[1], 0).getDate(); // day 0 of next month
  return { start: start, end: month + '-' + pad2(last) };
}

/* ------------------------------------------------------------------ *
 * month grid
 * ------------------------------------------------------------------ */

function dayAriaLabel(key, info) {
  const d = fromDateKey(key);
  const weekday = WEEKDAYS[(d.getDay() + 6) % 7];
  const dots = info && Array.isArray(info.dots) ? info.dots : [];
  const parts = [d.getMonth() + 1 + '月' + d.getDate() + '日', '周' + weekday];
  if (dots.length) {
    const labels = dots.map(dotLabel).filter(Boolean);
    parts.push(labels.length + ' 条记录');
    if (labels.length) parts.push(labels.join('、'));
  } else {
    parts.push('无记录');
  }
  if (info && info.planned) parts.push('计划日');
  return parts.join('，');
}

/**
 * Build the month grid.
 * @param {{month:string, days?:object, selected?:string, maxDots?:number,
 *          onSelect?:Function, showWeekdays?:boolean}} opts
 * @returns {HTMLElement}
 */
export function monthGrid(opts) {
  const o = opts || {};
  const month = typeof o.month === 'string' && MONTH_RE.test(o.month) ? o.month : toDateKey(new Date()).slice(0, 7);
  const days = o.days && typeof o.days === 'object' ? o.days : {};
  const maxDots = Number.isFinite(o.maxDots) ? Math.max(1, Math.min(6, o.maxDots)) : 3;
  const showWeekdays = o.showWeekdays !== false;

  const wrap = el('div', { class: 'cal' });

  if (showWeekdays) {
    const head = el('div', { class: 'cal-dow-row' });
    for (const label of WEEKDAYS) head.appendChild(el('div', { class: 'cal-dow', text: label }));
    wrap.appendChild(head);
  }

  const grid = el('div', { class: 'cal-grid', role: 'grid', 'aria-label': formatMonthTitle(month) + ' 日历' });

  const parts = month.split('-').map(Number);
  const first = new Date(parts[0], parts[1] - 1, 1);
  const offset = (first.getDay() + 6) % 7; // Monday first
  const start = new Date(parts[0], parts[1] - 1, 1 - offset);
  // the server knows which day it is (timezones); fall back to the browser
  const todayKey = typeof o.todayKey === 'string' && DAY_RE.test(o.todayKey) ? o.todayKey : toDateKey(new Date());

  for (let i = 0; i < 42; i += 1) {
    const date = new Date(start.getFullYear(), start.getMonth(), start.getDate() + i);
    const key = toDateKey(date);
    const inMonth = date.getMonth() === parts[1] - 1;
    const info = days[key] && typeof days[key] === 'object' ? days[key] : null;
    const dots = info && Array.isArray(info.dots) ? info.dots.filter(Boolean) : [];
    const isToday = info && info.today ? true : key === todayKey;
    const planned = !!(info && info.planned);
    const selected = o.selected === key;

    const classes = ['cal-day'];
    if (!inMonth) classes.push('cal-day--muted');
    if (isToday) classes.push('cal-day--today');
    if (planned) classes.push('cal-day--planned');
    if (selected) classes.push('cal-day--selected');

    const interactive = inMonth && typeof o.onSelect === 'function';
    const cell = el(interactive ? 'button' : 'div', {
      class: classes,
      dataset: { date: key },
      role: 'gridcell',
      'aria-label': dayAriaLabel(key, info),
      'aria-current': isToday ? 'date' : null,
      type: interactive ? 'button' : null,
      disabled: interactive ? null : null,
      onClick: interactive
        ? (event) => {
            event.preventDefault();
            o.onSelect(key);
          }
        : null,
    });

    cell.appendChild(el('span', { class: 'cal-day-num', text: String(date.getDate()) }));

    const dotRow = el('span', { class: 'cal-dots' });
    const shown = dots.slice(0, maxDots);
    for (const kind of shown) {
      dotRow.appendChild(
        el('span', {
          class: 'cal-dot',
          dataset: { kind: String(kind) },
          style: { '--dot': dotColor(kind) },
          title: dotLabel(kind),
        })
      );
    }
    if (dots.length > shown.length) {
      dotRow.appendChild(el('span', { class: 'cal-dot cal-dot--more', text: '+' + (dots.length - shown.length) }));
    }
    cell.appendChild(dotRow);
    grid.appendChild(cell);
  }

  wrap.appendChild(grid);
  return wrap;
}

export default monthGrid;
