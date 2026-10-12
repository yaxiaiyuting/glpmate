/**
 * dom.js — tiny hyperscript-style DOM builder.
 *
 * The whole app builds UI through `el()` / `svgEl()`; no `innerHTML` is ever
 * fed with user or model supplied strings.
 *
 *   el('div', { class: 'card', onClick: fn }, '文本', el('b', null, 'x'))
 *
 * Props understood:
 *   class / className   -> class attribute (string or array of strings)
 *   text                -> textContent (always treated as text)
 *   style               -> object of CSS properties (camelCase or kebab-case)
 *   dataset             -> object of data-* attributes
 *   on<Event>           -> addEventListener('<event>')  (onClick -> click)
 *   anything else       -> setAttribute(name, value)
 */

const SVG_NS = 'http://www.w3.org/2000/svg';

/** Tags that must be created inside the SVG namespace. */
export function isSvgTag(tag) {
  return (
    tag === 'svg' ||
    tag === 'g' ||
    tag === 'path' ||
    tag === 'circle' ||
    tag === 'ellipse' ||
    tag === 'rect' ||
    tag === 'line' ||
    tag === 'polyline' ||
    tag === 'polygon' ||
    tag === 'text' ||
    tag === 'tspan' ||
    tag === 'defs' ||
    tag === 'linearGradient' ||
    tag === 'radialGradient' ||
    tag === 'stop' ||
    tag === 'clipPath' ||
    tag === 'mask' ||
    tag === 'use' ||
    tag === 'title'
  );
}

function appendChild(node, child) {
  if (child === null || child === undefined || child === false || child === true) return;
  if (Array.isArray(child)) {
    for (const c of child) appendChild(node, c);
    return;
  }
  if (child instanceof Node) {
    node.appendChild(child);
    return;
  }
  node.appendChild(document.createTextNode(String(child)));
}

function applyProps(node, props, svg) {
  if (!props) return;
  for (const key of Object.keys(props)) {
    const value = props[key];
    if (value === null || value === undefined || value === false) continue;

    if (key === 'class' || key === 'className') {
      const cls = Array.isArray(value) ? value.filter(Boolean).join(' ') : String(value);
      if (cls) node.setAttribute('class', cls);
      continue;
    }
    if (key === 'text') {
      node.textContent = String(value);
      continue;
    }
    if (key === 'style') {
      if (typeof value === 'string') {
        node.setAttribute('style', value);
      } else {
        for (const prop of Object.keys(value)) {
          const cssProp = prop.startsWith('--') ? prop : prop.replace(/[A-Z]/g, (m) => '-' + m.toLowerCase());
          node.style.setProperty(cssProp, value[prop] === null ? '' : String(value[prop]));
        }
      }
      continue;
    }
    if (key === 'dataset') {
      for (const d of Object.keys(value)) {
        if (value[d] === null || value[d] === undefined) continue;
        node.dataset[d] = String(value[d]);
      }
      continue;
    }
    if (key === 'ref' && typeof value === 'function') {
      value(node);
      continue;
    }
    if (key.startsWith('on') && typeof value === 'function') {
      const event = key.slice(2).toLowerCase();
      node.addEventListener(event, value);
      continue;
    }
    if (value === true) {
      node.setAttribute(key, '');
      continue;
    }
    if (svg) {
      node.setAttribute(key, String(value));
    } else {
      node.setAttribute(key, String(value));
    }
  }
}

/**
 * Create an HTML element.
 * @param {string} tag
 * @param {object|null} props
 * @param {...any} children
 * @returns {HTMLElement}
 */
export function el(tag, props, ...children) {
  const node = document.createElement(tag);
  applyProps(node, props, false);
  for (const child of children) appendChild(node, child);
  return node;
}

/**
 * Create an SVG element.
 * @param {string} tag
 * @param {object|null} props
 * @param {...any} children
 * @returns {SVGElement}
 */
export function svgEl(tag, props, ...children) {
  const node = document.createElementNS(SVG_NS, tag);
  applyProps(node, props, true);
  for (const child of children) appendChild(node, child);
  return node;
}

/** Document fragment helper. */
export function frag(...children) {
  const f = document.createDocumentFragment();
  for (const child of children) appendChild(f, child);
  return f;
}

/** Remove every child of a node. */
export function clear(node) {
  if (!node) return node;
  while (node.firstChild) node.removeChild(node.firstChild);
  return node;
}

/**
 * Replace the contents of `parent` with `children`.
 * Accepts a DocumentFragment, Node, string or array.
 */
export function mount(parent, ...children) {
  clear(parent);
  for (const child of children) appendChild(parent, child);
  return parent;
}

/** Text node helper (never parses HTML). */
export function text(value) {
  return document.createTextNode(value === null || value === undefined ? '' : String(value));
}

/** Set textContent safely. */
export function setText(node, value) {
  if (node) node.textContent = value === null || value === undefined ? '' : String(value);
  return node;
}

/** Toggle a class. */
export function toggleClass(node, name, on) {
  if (!node) return node;
  node.classList.toggle(name, !!on);
  return node;
}

/** Short alias used across views. */
export const $ = (sel, root) => (root || document).querySelector(sel);
export const $$ = (sel, root) => Array.prototype.slice.call((root || document).querySelectorAll(sel));

export default { el, svgEl, frag, clear, mount, text, setText, toggleClass, isSvgTag, $, $$ };
