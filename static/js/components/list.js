/**
 * components/list.js — record rows, record type metadata and the quick-entry
 * circular buttons.
 */

import { el } from '../dom.js';
import { icon } from '../icons.js';
import { listRow, emptyState } from './card.js';
import { timeOf, fmtNum, relativeDay } from '../store.js';

/** type -> {icon, color, label, tone} */
export const TYPE_META = {
  weight: { icon: 'weight', color: '#3DBFA0', label: '体重' },
  body_fat: { icon: 'health', color: '#8B7BD8', label: '体脂' },
  food: { icon: 'food', color: '#E8607A', label: '餐食' },
  meal: { icon: 'food', color: '#E8607A', label: '餐食' },
  water: { icon: 'water', color: '#4A9BE8', label: '饮水' },
  dose: { icon: 'dose', color: '#E8A33D', label: '剂量' },
  training: { icon: 'training', color: '#3DBFA0', label: '训练' },
  exercise: { icon: 'training', color: '#3DBFA0', label: '训练' },
  steps: { icon: 'training', color: '#3DBFA0', label: '步数' },
  sleep: { icon: 'sleep', color: '#8B7BD8', label: '睡眠' },
  symptom: { icon: 'symptom', color: '#8B7BD8', label: '副作用' },
  side_effect: { icon: 'symptom', color: '#8B7BD8', label: '副作用' },
  lab: { icon: 'lab', color: '#4A9BE8', label: '化验' },
  bp: { icon: 'health', color: '#E8607A', label: '血压' },
  hba1c: { icon: 'lab', color: '#8B7BD8', label: '糖化' },
  lipid: { icon: 'lab', color: '#E8A33D', label: '血脂' },
  liver: { icon: 'lab', color: '#3DBFA0', label: '肝功能' },
  ketone: { icon: 'lab', color: '#35C6E8', label: '酮体' },
  muscle: { icon: 'training', color: '#3DBFA0', label: '肌肉量' },
  vital: { icon: 'health', color: '#4A9BE8', label: '体征' },
  measure: { icon: 'weight', color: '#3DBFA0', label: '测量' },
  meal_plan: { icon: 'food', color: '#E8607A', label: '计划餐' },
  glucose: { icon: 'lab', color: '#E8A33D', label: '血糖' },
  blood_pressure: { icon: 'health', color: '#E8607A', label: '血压' },
  waist: { icon: 'weight', color: '#3DBFA0', label: '腰围' },
  mood: { icon: 'health', color: '#35C6E8', label: '心情' },
  note: { icon: 'file-text', color: '#9AA5AD', label: '备注' },
};

export function typeMeta(type) {
  return TYPE_META[type] || { icon: 'dot', color: '#9AA5AD', label: String(type || '记录') };
}

/** '#3DBFA0' + alpha -> 'rgba(61,191,160,0.14)' (falls back to the raw colour) */
export function tint(hex, alpha) {
  const value = String(hex || '').trim();
  const short = /^#([0-9a-f]{3})$/i.exec(value);
  const long = /^#([0-9a-f]{6})$/i.exec(value);
  let r;
  let g;
  let b;
  if (long) {
    r = parseInt(long[1].slice(0, 2), 16);
    g = parseInt(long[1].slice(2, 4), 16);
    b = parseInt(long[1].slice(4, 6), 16);
  } else if (short) {
    r = parseInt(short[1].charAt(0) + short[1].charAt(0), 16);
    g = parseInt(short[1].charAt(1) + short[1].charAt(1), 16);
    b = parseInt(short[1].charAt(2) + short[1].charAt(2), 16);
  } else {
    return value || 'rgba(255,255,255,0.08)';
  }
  const a = Number.isFinite(alpha) ? alpha : 0.14;
  return 'rgba(' + r + ',' + g + ',' + b + ',' + a + ')';
}

/** Icon badge tinted with the record colour. */
export function typeBadge(type, size) {
  const meta = typeMeta(type);
  return el(
    'span',
    { class: 'row-icon', style: { '--tone': meta.color, background: tint(meta.color, 0.14) } },
    icon(meta.icon, { size: size || 18 })
  );
}

/** Human readable value for a record. */
export function recordValue(record) {
  const r = record || {};
  if (r.value !== null && r.value !== undefined && r.value !== '') {
    return fmtNum(r.value) + (r.unit ? ' ' + r.unit : '');
  }
  return '';
}

/**
 * One record row (icon + title + muted subtitle + right time + optional 修正).
 */
export function recordRow(record, opts) {
  const o = opts || {};
  const r = record || {};
  const meta = typeMeta(r.type);
  const subtitle = r.subtitle || recordValue(r) || '';
  return listRow({
    icon: meta.icon,
    title: r.title || meta.label,
    subtitle: subtitle,
    value: timeOf(r.at) || (o.showDate ? relativeDay(String(r.at || '').slice(0, 10)) : ''),
    valueTone: 'muted',
    toneColor: meta.color,
    action: o.onEdit ? { label: '修正', onClick: () => o.edit(r) } : null,
    onClick: o.onSelect ? () => o.onSelect(r) : null,
    chevron: !!o.chevron && !o.onEdit,
    class: 'row--record',
  });
}

/** Rows for a day, with an empty state. */
export function recordList(items, opts) {
  const o = opts || {};
  const rows = Array.isArray(items) ? items : [];
  if (!rows.length) {
    return emptyState({
      icon: o.emptyIcon || 'sparkle',
      title: o.emptyTitle || '这一天还没有记录',
      hint: o.emptyHint || '点右下角的按钮开始记录',
      actionLabel: o.actionLabel,
      onAction: o.onAction,
    });
  }
  const wrap = el('div', { class: 'list' });
  for (const item of rows) wrap.appendChild(recordRow(item, o));
  return wrap;
}

/**
 * The "快速记录" row: circular icon buttons plus the "N 种类型，可搜索" footer.
 * @param {Array<{type:string,label:string,icon?:string,color?:string}>} types
 * @param {{onPick?:Function, onSearch?:Function, total?:number}} opts
 */
export function quickRow(types, opts) {
  const o = opts || {};
  const items = (Array.isArray(types) ? types : []).slice(0, 5);
  const wrap = el('div', { class: 'quick' });

  const strip = el('div', { class: 'quick-strip' });
  if (!items.length) {
    strip.appendChild(el('p', { class: 'quick-empty', text: '还没有可用的记录类型' }));
  }
  for (const item of items) {
    const itemType = item.type || item.key;
    const meta = typeMeta(itemType);
    const color = item.color || meta.color;
    strip.appendChild(
      el(
        'button',
        {
          class: 'quick-btn',
          type: 'button',
          'aria-label': '记录' + (item.label || meta.label),
          style: { '--tone': color },
          onClick: () => {
            if (o.onPick) o.onPick(item);
          },
        },
        el('span', { class: 'quick-circle', style: { background: tint(color, 0.14) } }, icon(item.icon || meta.icon, { size: 19 })),
        el('span', { class: 'quick-label', text: item.label || meta.label })
      )
    );
  }
  wrap.appendChild(strip);

  const total = Number.isFinite(o.total) ? o.total : (Array.isArray(types) ? types.length : 0);
  wrap.appendChild(
    el(
      'button',
      {
        class: 'quick-more',
        type: 'button',
        onClick: () => {
          if (o.onSearch) o.onSearch();
        },
      },
      el('span', { class: 'quick-more-text', text: (total || 15) + ' 种类型，可搜索' }),
      el('span', { class: 'row-chev' }, icon('chevron', { size: 16 }))
    )
  );
  return wrap;
}

export default { recordRow, recordList, quickRow, typeMeta, typeBadge, recordValue, tint, TYPE_META };
