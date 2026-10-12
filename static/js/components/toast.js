/**
 * toast.js — tiny non-blocking notifications.
 *
 * toast('已保存', 'success') / toast('保存失败', 'error') / toast('…', 'info')
 */

import { el } from '../dom.js';
import { icon } from '../icons.js';

let host = null;
let seq = 0;

function ensureHost() {
  if (host && document.body.contains(host)) return host;
  host = el('div', { class: 'toast-host', role: 'status', 'aria-live': 'polite', 'aria-atomic': 'true' });
  document.body.appendChild(host);
  return host;
}

const ICONS = { success: 'check', error: 'symptom', info: 'info' };

/**
 * @param {string} message  plain text (never HTML)
 * @param {'success'|'error'|'info'} [kind]
 * @param {{timeout?:number}} [opts]
 */
export function toast(message, kind, opts) {
  const type = kind === 'success' || kind === 'error' ? kind : 'info';
  const options = opts || {};
  const node = el(
    'div',
    { class: 'toast toast--' + type, dataset: { id: String((seq += 1)) } },
    el('span', { class: 'toast-icon' }, icon(ICONS[type], { size: 16, stroke: 2 })),
    el('span', { class: 'toast-text', text: message === null || message === undefined ? '' : String(message) })
  );

  const dismiss = () => {
    if (!node.parentNode) return;
    node.classList.add('toast--out');
    window.setTimeout(() => {
      if (node.parentNode) node.parentNode.removeChild(node);
    }, 180);
  };

  node.addEventListener('click', dismiss);
  ensureHost().appendChild(node);
  window.setTimeout(dismiss, Number.isFinite(options.timeout) ? options.timeout : 3600);
  return dismiss;
}

export function clearToasts() {
  if (host) host.textContent = '';
}

export default toast;
