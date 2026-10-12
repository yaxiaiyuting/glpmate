/**
 * components/card.js — the layout vocabulary of the app: cards, section
 * headers, list rows, stat tiles, buttons and empty states.
 *
 * Everything returns DOM nodes built with `el()`; no HTML strings.
 */

import { el } from '../dom.js';
import { icon } from '../icons.js';

/** '#RRGGBB' -> 'rgba(r,g,b,a)' so icon badges can be tinted per record type. */
function rgbaOf(value, alpha) {
  const hex = String(value || '').trim();
  const long = /^#([0-9a-f]{6})$/i.exec(hex);
  const short = /^#([0-9a-f]{3})$/i.exec(hex);
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
    return null;
  }
  return 'rgba(' + r + ',' + g + ',' + b + ',' + (Number.isFinite(alpha) ? alpha : 0.14) + ')';
}

/**
 * Big rounded surface.
 * @param {{class?:string, tone?:string, flush?:boolean}} [opts]
 */
export function card(opts, ...children) {
  const o = opts || {};
  const classes = ['card'];
  if (o.tone) classes.push('card--' + o.tone);
  if (o.flush) classes.push('card--flush');
  if (o.class) classes.push(o.class);
  const node = el('section', { class: classes }, children);
  if (o.id) node.id = o.id;
  return node;
}

/** Card with a title row: `{title, subtitle, right}` */
export function cardHead(opts) {
  const o = opts || {};
  const head = el(
    'div',
    { class: 'card-head' },
    o.icon
      ? el('span', { class: 'card-head-icon tone-' + (o.tone || 'accent') }, icon(o.icon, { size: 18 }))
      : null,
    el(
      'div',
      { class: 'card-head-main' },
      o.title ? el('h2', { class: 'card-title', text: o.title }) : null,
      o.subtitle ? el('p', { class: 'card-sub', text: o.subtitle }) : null
    ),
    o.right ? el('div', { class: 'card-head-right' }, o.right) : null
  );
  return head;
}

/** Section label above a group of cards. */
export function sectionHead(title, right) {
  return el(
    'div',
    { class: 'section-head' },
    el('h2', { class: 'section-title', text: title }),
    right || null
  );
}

/**
 * Unified list row.
 * @param {{icon?:string, tone?:string, title?:string, subtitle?:string,
 *          value?:string, valueTone?:string, meta?:string, trailing?:Node,
 *          action?:{label:string,onClick:Function}, onClick?:Function,
 *          chevron?:boolean, class?:string}} opts
 */
export function listRow(opts) {
  const o = opts || {};
  const hasAction = !!(o.action && o.action.label);
  const clickable = typeof o.onClick === 'function';

  const main = el(
    'span',
    { class: 'row-main' },
    o.icon
      ? el(
          'span',
          {
            class: 'row-icon tone-' + (o.tone || 'muted'),
            style: o.toneColor
              ? { '--tone': o.toneColor, background: rgbaOf(o.toneColor, 0.14) }
              : null,
          },
          icon(o.icon, { size: 18 })
        )
      : null,
    el(
      'span',
      { class: 'row-text' },
      el('span', { class: 'row-title', text: o.title || '' }),
      o.subtitle ? el('span', { class: 'row-sub', text: o.subtitle }) : null
    ),
    o.value !== undefined && o.value !== null
      ? el(
          'span',
          { class: 'row-value' + (o.valueTone ? ' tone-text-' + o.valueTone : '') },
          el('span', { class: 'row-value-main', text: String(o.value) }),
          o.meta ? el('span', { class: 'row-value-meta', text: o.meta }) : null
        )
      : null
  );

  const trailing = [];
  if (hasAction) {
    trailing.push(
      el('button', {
        class: 'row-action',
        type: 'button',
        text: o.action.label,
        'aria-label': (o.title ? o.title + ' ' : '') + o.action.label,
        onClick: (event) => {
          event.stopPropagation();
          o.action.onClick(event);
        },
      })
    );
  }
  if (o.trailing) trailing.push(o.trailing);
  if (o.chevron) trailing.push(el('span', { class: 'row-chev' }, icon('chevron', { size: 16 })));

  const classes = ['row'];
  if (o.class) classes.push(o.class);
  if (clickable && !hasAction) classes.push('row--btn');

  if (clickable && hasAction) {
    return el(
      'div',
      { class: classes },
      el('button', { class: 'row-hit', type: 'button', onClick: o.onClick, 'aria-label': o.title || '' }, main),
      trailing.length ? el('span', { class: 'row-trailing' }, trailing) : null
    );
  }

  if (clickable) {
    return el('button', { class: classes, type: 'button', onClick: o.onClick }, main, trailing.length ? el('span', { class: 'row-trailing' }, trailing) : null);
  }

  return el('div', { class: classes }, main, trailing.length ? el('span', { class: 'row-trailing' }, trailing) : null);
}

/** Wrapper that draws hairline dividers between rows. */
export function list(rows, opts) {
  const o = opts || {};
  return el('div', { class: 'list' + (o.class ? ' ' + o.class : '') }, rows);
}

/**
 * Buttons.
 * kind: 'primary' | 'ghost' | 'subtle' | 'danger' | 'pill'
 */
export function button(opts) {
  const o = opts || {};
  const kind = o.kind || 'primary';
  const classes = ['btn', 'btn--' + kind];
  if (o.block) classes.push('btn--block');
  if (o.small) classes.push('btn--small');
  if (o.class) classes.push(o.class);
  return el(
    'button',
    {
      class: classes,
      type: o.type || 'button',
      disabled: o.disabled ? true : null,
      onClick: o.onClick || null,
      'aria-label': o.ariaLabel || null,
    },
    o.icon ? icon(o.icon, { size: o.iconSize || 18 }) : null,
    o.label ? el('span', { text: o.label }) : null,
    o.children || null
  );
}

/** Small cyan pill with an arrow, e.g. "我的剂量 →". */
export function pillButton(opts) {
  const o = opts || {};
  return el(
    'button',
    {
      class: 'pill-btn' + (o.tone ? ' pill-btn--' + o.tone : ''),
      type: 'button',
      onClick: o.onClick || null,
      'aria-label': o.ariaLabel || o.label || null,
      disabled: o.disabled ? true : null,
    },
    el('span', { text: o.label || '' }),
    o.arrow === false ? null : icon('arrow-right', { size: 15, stroke: 2 })
  );
}

/** The ⓘ note row with an optional right-aligned link. */
export function infoNote(text, action) {
  return el(
    'div',
    { class: 'info-note' },
    el('span', { class: 'info-note-icon' }, icon('info', { size: 15 })),
    el('span', { class: 'info-note-text', text: text }),
    action
      ? el('button', {
          class: 'info-note-action',
          type: 'button',
          text: action.label,
          onClick: action.onClick,
        })
      : null
  );
}

/** Stat tile used on the data screen and the two half cards on "今天". */
export function statTile(opts) {
  const o = opts || {};
  return el(
    'div',
    { class: 'tile' + (o.class ? ' ' + o.class : '') },
    o.label ? el('span', { class: 'tile-label', text: o.label }) : null,
    el(
      'span',
      { class: 'tile-value' + (o.tone ? ' tone-' + o.tone : '') },
      el('span', { class: 'tile-number', text: o.value === undefined || o.value === null ? '—' : String(o.value) }),
      o.unit ? el('span', { class: 'tile-unit', text: o.unit }) : null
    ),
    o.hint ? el('span', { class: 'tile-hint', text: o.hint }) : null
  );
}

/** Empty / loading placeholder. */
export function emptyState(opts) {
  const o = opts || {};
  return el(
    'div',
    { class: 'empty' },
    el('span', { class: 'empty-icon' }, icon(o.icon || 'sparkle', { size: 22 })),
    el('p', { class: 'empty-title', text: o.title || '还没有记录' }),
    o.hint ? el('p', { class: 'empty-hint', text: o.hint }) : null,
    o.actionLabel
      ? button({ label: o.actionLabel, kind: 'ghost', small: true, onClick: o.onAction })
      : null
  );
}

/** Backend unreachable / bootstrap failed: explain it and offer a retry. */
export function errorState(opts) {
  const o = opts || {};
  return el(
    'div',
    { class: 'card card--error', role: 'alert' },
    el('h2', { class: 'card-title', text: o.title || '加载失败' }),
    el('p', { class: 'card-sub', text: o.message || '无法连接后端服务，请确认服务正在运行。' }),
    el(
      'div',
      { class: 'form-actions' },
      button({ label: o.actionLabel || '重试', kind: 'ghost', block: true, onClick: o.onRetry })
    )
  );
}

/** Grey shimmer block used while the first payload is in flight. */
export function skeleton(lines) {
  const count = Math.max(1, Number(lines) || 3);
  const wrap = el('div', { class: 'skeleton', 'aria-hidden': 'true' });
  for (let i = 0; i < count; i += 1) wrap.appendChild(el('span', { class: 'skeleton-line' }));
  return wrap;
}

/** Small status dot + label. */
export function statusPill(label, tone) {
  return el(
    'span',
    { class: 'status-pill' + (tone ? ' status-pill--' + tone : '') },
    el('span', { class: 'status-dot' }),
    el('span', { text: label })
  );
}

export default { card, cardHead, sectionHead, listRow, list, button, pillButton, infoNote, statTile, emptyState, errorState, skeleton, statusPill };
