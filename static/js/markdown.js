/**
 * markdown.js — minimal, SAFE Markdown -> DOM renderer.
 *
 * Design rule: nothing here ever produces an HTML string. Every piece of text
 * coming from a model (or a user) ends up in a text node via `textContent`,
 * so injected markup is impossible by construction.
 *
 * Supported blocks : headings, ul/ol (one nesting level), blockquote, fenced
 *                    code, hr, GFM tables, paragraphs (single newline -> <br>)
 * Supported inline : **bold** __bold__ *em* _em_ `code` ~~del~~ [text](url)
 *                    bare http(s) links, backslash escapes
 *
 * renderMarkdown(src) -> DocumentFragment
 * renderInline(src)   -> DocumentFragment
 */

import { el } from './dom.js';

const MAX_LEN = 200000;
const MAX_DEPTH = 6;

const ESCAPABLE = '\\`*_{}[]()#+-.!>~|"\'';
const PARAGRAPH_BREAK = /^\s*$|^\s{0,3}(#{1,6}\s|```|~~~|>|\||([-*_])(\s*\2){2,})|^\s*([-*+]|\d+[.)])\s+/;

/* ------------------------------------------------------------------ *
 * URL sanitising
 * ------------------------------------------------------------------ */

/** Strip control characters that browsers ignore inside a scheme. */
function stripControl(value) {
  return value.replace(/[\u0000-\u0020\u007f\u00a0\u1680\u2000-\u200f\u2028\u2029\u202f\u205f\u3000]/g, '');
}

/**
 * Allow only http(s), mailto, tel and same-document relative URLs.
 * Everything else (javascript:, data:, vbscript:, protocol-relative …)
 * degrades to plain text.
 * @returns {string|null}
 */
export function safeUrl(raw) {
  if (typeof raw !== 'string') return null;
  const trimmed = raw.trim();
  if (!trimmed) return null;
  const probe = stripControl(trimmed).toLowerCase();
  if (/^(https?:|mailto:|tel:)/.test(probe)) return trimmed;
  // relative / anchor links, but never protocol-relative "//host"
  if (/^[/#.]/.test(probe) && !probe.startsWith('//')) return trimmed;
  return null;
}

/* ------------------------------------------------------------------ *
 * inline parsing
 * ------------------------------------------------------------------ */

function anchor(href, label, children) {
  return el(
    'a',
    { class: 'md-a', href: href, target: '_blank', rel: 'noopener noreferrer', title: label },
    children
  );
}

/**
 * Parse inline markdown into an array of DOM nodes.
 * @param {string} src
 * @param {number} depth recursion guard
 * @returns {Node[]}
 */
export function inlineNodes(src, depth) {
  const level = depth || 0;
  const out = [];
  let buffer = '';
  let i = 0;

  const flush = () => {
    if (buffer) {
      out.push(document.createTextNode(buffer));
      buffer = '';
    }
  };

  while (i < src.length) {
    const ch = src[i];

    // backslash escape
    if (ch === '\\' && i + 1 < src.length && ESCAPABLE.indexOf(src[i + 1]) !== -1) {
      buffer += src[i + 1];
      i += 2;
      continue;
    }

    // `code span`
    if (ch === '`') {
      const m = /^(`+)([\s\S]*?)\1/.exec(src.slice(i));
      if (m) {
        flush();
        out.push(el('code', { class: 'md-code-inline', text: m[2].trim() || ' ' }));
        i += m[0].length;
        continue;
      }
    }

    // **strong** / *em* (also __ and _)
    if ((ch === '*' || ch === '_') && level < MAX_DEPTH) {
      const strong = src.startsWith(ch + ch, i);
      const delim = strong ? ch + ch : ch;
      const end = src.indexOf(delim, i + delim.length);
      if (end > i + delim.length) {
        const inner = src.slice(i + delim.length, end);
        if (inner.trim()) {
          flush();
          const kids = inlineNodes(inner, level + 1);
          out.push(el(strong ? 'strong' : 'em', { class: strong ? 'md-strong' : 'md-em' }, kids));
          i = end + delim.length;
          continue;
        }
      }
    }

    // ~~strikethrough~~
    if (ch === '~' && src.startsWith('~~', i) && level < MAX_DEPTH) {
      const end = src.indexOf('~~', i + 2);
      if (end > i + 2) {
        flush();
        out.push(el('del', { class: 'md-del' }, inlineNodes(src.slice(i + 2, end), level + 1)));
        i = end + 2;
        continue;
      }
    }

    // [text](url)
    if (ch === '[') {
      const m = /^\[([^\]]*)\]\(\s*([^)\s]+)(?:\s+"[^"]*")?\s*\)/.exec(src.slice(i));
      if (m) {
        flush();
        const href = safeUrl(m[2]);
        const label = inlineNodes(m[1], level + 1);
        if (href) {
          out.push(anchor(href, m[1], label));
        } else {
          // unsafe scheme -> keep the human readable text only
          out.push(document.createTextNode(m[1] || m[2]));
        }
        i += m[0].length;
        continue;
      }
    }

    // bare url
    if ((ch === 'h' || ch === 'H') && /^https?:\/\//i.test(src.slice(i, i + 8))) {
      const m = /^https?:\/\/[^\s<>()[\]"'`]+/i.exec(src.slice(i));
      if (m) {
        const url = m[0].replace(/[.,;:!?]+$/, '');
        if (url.length > 8) {
          flush();
          out.push(anchor(url, url, [document.createTextNode(url)]));
          i += url.length;
          continue;
        }
      }
    }

    buffer += ch;
    i += 1;
  }

  flush();
  return out;
}

/** Inline markdown for a single line. */
export function renderInline(src) {
  const text = coerce(src);
  const fragment = document.createDocumentFragment();
  for (const node of inlineNodes(escapeNul(text), 0)) fragment.appendChild(node);
  return fragment;
}

/* ------------------------------------------------------------------ *
 * helpers
 * ------------------------------------------------------------------ */

function coerce(src) {
  if (typeof src === 'string') return src;
  if (src === null || src === undefined) return '';
  try {
    return String(src);
  } catch (_err) {
    return '';
  }
}

function escapeNul(value) {
  return value.replace(/\u0000/g, '');
}

function line(text) {
  const fragment = document.createDocumentFragment();
  for (const node of inlineNodes(text, 0)) fragment.appendChild(node);
  return fragment;
}

function splitRow(row) {
  let value = row.trim();
  if (value.startsWith('|')) value = value.slice(1);
  if (value.endsWith('|')) value = value.slice(0, -1);
  return value.split('|').map((cell) => cell.trim());
}

function isTableSeparator(row) {
  if (!row || row.indexOf('-') === -1) return false;
  const cells = splitRow(row);
  return cells.length > 0 && cells.every((cell) => /^:?-{1,}:?$/.test(cell.replace(/\s/g, '')));
}

function isBlockStart(lines, index) {
  const current = lines[index];
  if (current === undefined) return true;
  if (PARAGRAPH_BREAK.test(current)) return true;
  if (current.indexOf('|') !== -1 && isTableSeparator(lines[index + 1] || '')) return true;
  return false;
}

/* ------------------------------------------------------------------ *
 * block parsing
 * ------------------------------------------------------------------ */

/**
 * Render markdown to a DocumentFragment (never to an HTML string).
 * @param {string} src
 * @returns {DocumentFragment}
 */
export function renderMarkdown(src) {
  const fragment = document.createDocumentFragment();
  let source = escapeNul(coerce(src)).replace(/\r\n?/g, '\n');
  if (source.length > MAX_LEN) source = source.slice(0, MAX_LEN) + '…';

  const lines = source.split('\n');
  let i = 0;

  while (i < lines.length) {
    const current = lines[i];

    // blank
    if (!current.trim()) {
      i += 1;
      continue;
    }

    // fenced code block
    const fence = /^\s*(```|~~~)\s*([\w+#.-]*)\s*$/.exec(current);
    if (fence) {
      const marker = fence[1];
      const body = [];
      i += 1;
      while (i < lines.length && !new RegExp('^\\s*' + marker + '\\s*$').test(lines[i])) {
        body.push(lines[i]);
        i += 1;
      }
      i += 1; // closing fence (or EOF)
      fragment.appendChild(el('pre', { class: 'md-pre' }, el('code', { class: 'md-code', text: body.join('\n') })));
      continue;
    }

    // horizontal rule
    if (/^\s{0,3}([-*_])(\s*\1){2,}\s*$/.test(current)) {
      fragment.appendChild(el('hr', { class: 'md-hr' }));
      i += 1;
      continue;
    }

    // heading
    const heading = /^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$/.exec(current);
    if (heading) {
      const level = Math.min(heading[1].length, 6);
      fragment.appendChild(el('h' + level, { class: 'md-h' + level }, line(heading[2])));
      i += 1;
      continue;
    }

    // blockquote (recursive)
    if (/^\s{0,3}>/.test(current)) {
      const body = [];
      while (i < lines.length && (/^\s{0,3}>/.test(lines[i]) || (body.length && lines[i].trim()))) {
        body.push(lines[i].replace(/^\s{0,3}>\s?/, ''));
        i += 1;
      }
      fragment.appendChild(el('blockquote', { class: 'md-quote' }, renderMarkdown(body.join('\n'))));
      continue;
    }

    // table
    if (current.indexOf('|') !== -1 && isTableSeparator(lines[i + 1] || '')) {
      const header = splitRow(current);
      i += 2;
      const rows = [];
      while (i < lines.length && lines[i].trim() && lines[i].indexOf('|') !== -1) {
        rows.push(splitRow(lines[i]));
        i += 1;
      }
      const table = el('table', { class: 'md-table' });
      const thead = el('thead');
      const headRow = el('tr');
      for (const cell of header) headRow.appendChild(el('th', null, line(cell)));
      thead.appendChild(headRow);
      table.appendChild(thead);
      const tbody = el('tbody');
      for (const row of rows) {
        const tr = el('tr');
        for (let c = 0; c < header.length; c += 1) {
          tr.appendChild(el('td', null, line(row[c] === undefined ? '' : row[c])));
        }
        tbody.appendChild(tr);
      }
      table.appendChild(tbody);
      fragment.appendChild(table);
      continue;
    }

    // list (one nesting level via 2+ space indent)
    const listItem = /^(\s*)([-*+]|\d+[.)])\s+(.*)$/.exec(current);
    if (listItem) {
      const ordered = /\d/.test(listItem[2]);
      const list = el(ordered ? 'ol' : 'ul', { class: ordered ? 'md-ol' : 'md-ul' });
      let sublist = null;
      while (i < lines.length) {
        const item = /^(\s*)([-*+]|\d+[.)])\s+(.*)$/.exec(lines[i]);
        if (!item) break;
        const indent = item[1].replace(/\t/g, '  ').length;
        if (indent >= 2) {
          if (!sublist) {
            sublist = el('ul', { class: 'md-ul md-ul--nested' });
            (list.lastChild || list).appendChild(sublist);
          }
          sublist.appendChild(el('li', { class: 'md-li' }, line(item[3])));
        } else {
          sublist = null;
          list.appendChild(el('li', { class: 'md-li' }, line(item[3])));
        }
        i += 1;
      }
      fragment.appendChild(list);
      continue;
    }

    // paragraph
    const paragraph = [];
    while (i < lines.length && !isBlockStart(lines, i)) {
      paragraph.push(lines[i].trim());
      i += 1;
    }
    if (paragraph.length) {
      const p = el('p', { class: 'md-p' });
      paragraph.forEach((raw, index) => {
        if (index > 0) p.appendChild(el('br', { class: 'md-br' }));
        for (const node of inlineNodes(raw, 0)) p.appendChild(node);
      });
      fragment.appendChild(p);
    } else {
      i += 1;
    }
  }

  return fragment;
}

export default renderMarkdown;
