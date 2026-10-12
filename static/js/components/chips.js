/**
 * components/chips.js — selectable chips (feelings, filters, meal slots).
 */

import { el } from '../dom.js';

/**
 * A single chip.
 * @param {{label:string, active?:boolean, tone?:string, onClick?:Function,
 *          ariaLabel?:string, removable?:boolean, onRemove?:Function}} opts
 */
export function chip(opts) {
  const o = opts || {};
  const classes = ['chip'];
  if (o.active) classes.push('is-active');
  if (o.tone) classes.push('chip--' + o.tone);

  if (typeof o.onClick !== 'function') {
    return el('span', { class: classes.concat('chip--static'), text: o.label });
  }

  return el(
    'button',
    {
      class: classes,
      type: 'button',
      'aria-pressed': o.active ? 'true' : 'false',
      'aria-label': o.ariaLabel || o.label,
      onClick: (event) => {
        event.preventDefault();
        o.onClick(o.label, !o.active);
      },
    },
    el('span', { text: o.label }),
    o.removable
      ? el('span', {
          class: 'chip-remove',
          role: 'button',
          tabindex: '0',
          'aria-label': '移除 ' + o.label,
          text: '×',
          onClick: (event) => {
            event.stopPropagation();
            if (o.onRemove) o.onRemove(o.label);
          },
        })
      : null
  );
}

/**
 * Row of chips that wraps.
 * @param {Array<string|{label:string,value?:any}>} labels
 * @param {{active?:string|string[], onSelect?:Function, tone?:string,
 *          multi?:boolean, class?:string}} [opts]
 */
export function chipRow(labels, opts) {
  const o = opts || {};
  const activeList = Array.isArray(o.active) ? o.active : o.active ? [o.active] : [];
  return el(
    'div',
    { class: 'chip-row' + (o.class ? ' ' + o.class : ''), role: 'group' },
    (Array.isArray(labels) ? labels : []).map((entry) => {
      const item = typeof entry === 'string' ? { label: entry } : entry || {};
      const label = item.label === undefined ? String(item.value) : item.label;
      return chip({
        label: label,
        tone: o.tone,
        active: activeList.indexOf(label) !== -1,
        onClick: o.onSelect
          ? () => {
              o.onSelect(label, item);
            }
          : null,
      });
    })
  );
}

export default { chip, chipRow };
