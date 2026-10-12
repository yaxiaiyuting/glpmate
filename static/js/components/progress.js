/**
 * components/progress.js — thin bars and the macro progress row.
 */

import { el } from '../dom.js';
import { clampPct, fmtNum } from '../store.js';

/**
 * Thin rounded progress bar.
 * @param {{pct?:number, value?:number, target?:number, color?:string, class?:string, label?:string}} opts
 */
export function thinBar(opts) {
  const o = opts || {};
  const ratio = o.pct !== undefined && o.pct !== null ? Number(o.pct) : clampPct(o.value, o.target);
  const pct = Math.max(0, Math.min(1, Number.isFinite(ratio) ? ratio : 0));
  return el(
    'span',
    {
      class: 'bar' + (o.class ? ' ' + o.class : ''),
      role: 'progressbar',
      'aria-valuemin': '0',
      'aria-valuemax': '100',
      'aria-valuenow': String(Math.round(pct * 100)),
      'aria-label': o.label || null,
    },
    el('span', {
      class: 'bar-fill',
      style: { width: (pct * 100).toFixed(1) + '%', background: o.color || 'var(--accent)' },
    })
  );
}

/**
 * One macro cell: label, "1000/1600", thin bar.
 * @param {{label:string, value:number, target:number, unit?:string, color:string, digits?:number}} opts
 */
export function macroCell(opts) {
  const o = opts || {};
  const over = Number(o.value) > Number(o.target) && Number(o.target) > 0;
  return el(
    'div',
    { class: 'macro' },
    el('span', { class: 'macro-label', text: o.label }),
    el(
      'span',
      { class: 'macro-value' },
      el('span', { class: 'macro-now', text: fmtNum(o.value, o.digits) }),
      el('span', { class: 'macro-target', text: '/' + fmtNum(o.target, o.digits) })
    ),
    thinBar({
      value: o.value,
      target: o.target,
      color: o.color,
      label: o.label + ' ' + fmtNum(o.value, o.digits) + ' / ' + fmtNum(o.target, o.digits),
    }),
    over ? el('span', { class: 'macro-over', text: '已超出' }) : null
  );
}

/**
 * 5-column macro row (卡路里/蛋白质/碳水/脂肪/纤维).
 * @param {Array<{key:string,label:string,value:number,target:number,color:string}>} items
 */
export function macroRow(items) {
  const list = Array.isArray(items) ? items : [];
  return el(
    'div',
    { class: 'macro-row' },
    list.map((item) =>
      macroCell({
        label: item.label,
        value: item.value,
        target: item.target,
        color: item.color,
        digits: item.digits,
      })
    )
  );
}

/**
 * Labelled progress line used by the water card and the "版块" rows.
 * @param {{label?:string, value:number, target:number, unit?:string,
 *          color?:string, valueText?:string, sub?:string}} opts
 */
export function progressLine(opts) {
  const o = opts || {};
  return el(
    'div',
    { class: 'progress-line' },
    el(
      'div',
      { class: 'progress-line-head' },
      el('span', { class: 'progress-line-value', text: o.valueText || fmtNum(o.value) + ' / ' + fmtNum(o.target) + (o.unit ? ' ' + o.unit : '') }),
      o.sub ? el('span', { class: 'progress-line-sub', text: o.sub }) : null
    ),
    thinBar({ value: o.value, target: o.target, color: o.color, label: o.label })
  );
}

export default { thinBar, macroCell, macroRow, progressLine };
