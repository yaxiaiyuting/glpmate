/**
 * icons.js — hand-rolled inline SVG icon set (no icon font, no network).
 *
 * Every icon is drawn on a 24x24 grid with `stroke="currentColor"` and
 * `fill="none"` so it inherits the surrounding text colour.
 *
 *   icon('check')                       -> <svg class="icon">…
 *   icon('water', { size: 22, className: 'x' })
 */

import { svgEl } from './dom.js';

/* ------------------------------------------------------------------ *
 * tiny shape helpers
 * ------------------------------------------------------------------ */

const P = (d, extra) => ['path', Object.assign({ d }, extra)];
const C = (cx, cy, r, extra) => ['circle', Object.assign({ cx, cy, r }, extra)];
const L = (x1, y1, x2, y2, extra) => ['line', Object.assign({ x1, y1, x2, y2 }, extra)];
const R = (x, y, w, h, rx, extra) => ['rect', Object.assign({ x, y, width: w, height: h, rx }, extra)];
const DOT = (cx, cy) => ['circle', { cx, cy, r: 0.95, fill: 'currentColor', stroke: 'none' }];

/* ------------------------------------------------------------------ *
 * the icon set
 * ------------------------------------------------------------------ */

const THUMB_UP = [
  P('M6.5 10.6h3v9.9h-3z'),
  P('M9.5 20.5h7.1a1.9 1.9 0 0 0 1.9-1.6l1-6a1.9 1.9 0 0 0-1.9-2.2h-4.1l.7-3.4a1.7 1.7 0 0 0-3-1.4L9.5 10.6z'),
];

const ICONS = {
  /* navigation ---------------------------------------------------- */
  today: [
    P('M3 10.6 12 3.2l9 7.4'),
    P('M5.6 9.6V20a1 1 0 0 0 1 1h10.8a1 1 0 0 0 1-1V9.6'),
    P('M9.6 21v-5.6h4.8V21'),
  ],
  data: [R(3.6, 12, 3.2, 8, 1.1), R(10.4, 6.6, 3.2, 13.4, 1.1), R(17.2, 9.6, 3.2, 10.4, 1.1)],
  coach: [
    P('M4 6.2A2.2 2.2 0 0 1 6.2 4h11.6A2.2 2.2 0 0 1 20 6.2v7.2a2.2 2.2 0 0 1-2.2 2.2H9.4L4 20z'),
    P('M8.4 9.4h7.2'),
    P('M8.4 12.6h4.4'),
  ],
  settings: [
    L(4, 7.2, 20, 7.2),
    L(4, 12, 20, 12),
    L(4, 16.8, 20, 16.8),
    C(9.2, 7.2, 2),
    C(15.4, 12, 2),
    C(8.2, 16.8, 2),
  ],
  back: [P('M14.8 5.2 8 12l6.8 6.8')],
  chevron: [P('M9.4 5.2 16.2 12l-6.8 6.8')],
  'chevron-down': [P('M5.6 9.2 12 15.6l6.4-6.4')],
  close: [P('M6.2 6.2 17.8 17.8'), P('M17.8 6.2 6.2 17.8')],
  menu: [L(4, 7, 20, 7), L(4, 12, 20, 12), L(4, 17, 20, 17)],
  plus: [P('M12 5.2v13.6'), P('M5.2 12h13.6')],
  minus: [P('M5.2 12h13.6')],
  check: [P('M4.8 12.6 9.6 17.4 19.2 6.6')],
  search: [C(10.8, 10.8, 6.3), L(15.4, 15.4, 20.4, 20.4)],
  'arrow-right': [P('M4 12h15'), P('M13.2 6.2 19 12l-5.8 5.8')],
  'arrow-left': [P('M20 12H5'), P('M10.8 6.2 5 12l5.8 5.8')],
  refresh: [P('M19.6 12a7.6 7.6 0 1 1-2.2-5.4'), P('M19.8 3.6v4.8h-4.8')],
  'wifi-off': [
    P('M3.2 3.2 20.8 20.8'),
    P('M2.6 8.4a15 15 0 0 1 5-2.7'),
    P('M21.4 8.4a15 15 0 0 0-8.2-3.2'),
    P('M6 12.4a10 10 0 0 1 3-1.5'),
    P('M18 12.4a10 10 0 0 0-2.6-1.4'),
    DOT(12, 17.4),
  ],

  /* capture / media ----------------------------------------------- */
  camera: [R(3, 7.2, 18, 13, 3), C(12, 13.4, 3.4), P('M8.8 7.2 10.2 4.4h3.6l1.4 2.8')],
  mic: [R(9, 3, 6, 9.4, 3), P('M5.6 11.2a6.4 6.4 0 0 0 12.8 0'), L(12, 17.6, 12, 20.8)],
  send: [P('M12 19.4V5'), P('M6.2 10.8 12 5l5.8 5.8')],
  'thumb-up': THUMB_UP,
  'thumb-down': [['g', { transform: 'rotate(180 12 12)' }, THUMB_UP]],
  copy: [
    R(8.4, 8.4, 12, 12, 2.6),
    P('M15.6 5.6V5a2 2 0 0 0-2-2H5.6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2H6'),
  ],
  share: [C(6.2, 12, 2.2), C(17.8, 6.2, 2.2), C(17.8, 17.8, 2.2), L(8.2, 10.9, 15.8, 7.3), L(8.2, 13.1, 15.8, 16.7)],
  edit: [P('M4 20h4L20 8l-4-4L4 16z'), P('M14.4 5.6 18.4 9.6')],
  trash: [P('M4 7h16'), P('M9.2 7V4.4h5.6V7'), P('M6.6 7l.9 13.1h9l.9-13.1')],
  download: [P('M12 3.6v11'), P('M7.4 10.4 12 15l4.6-4.6'), P('M4.6 20.4h14.8')],
  upload: [P('M12 15.4v-11'), P('M7.4 8.6 12 4l4.6 4.6'), P('M4.6 20.4h14.8')],

  /* status / misc -------------------------------------------------- */
  lock: [R(4.8, 10.6, 14.4, 9.4, 2.6), P('M8.4 10.6V7.8a3.6 3.6 0 0 1 7.2 0v2.8'), DOT(12, 15.2)],
  star: [P('M12 3.6l2.7 5.5 6 .9-4.3 4.3 1 6-5.4-2.9-5.4 2.9 1-6L3.3 10l6-.9z')],
  'star-filled': [P('M12 3.6l2.7 5.5 6 .9-4.3 4.3 1 6-5.4-2.9-5.4 2.9 1-6L3.3 10l6-.9z', { fill: 'currentColor' })],
  info: [C(12, 12, 8.6), L(12, 11.2, 12, 16.4), DOT(12, 8.2)],
  sparkle: [
    P('M11 3.4l1.8 4.6 4.6 1.8-4.6 1.8L11 16.2 9.2 11.6 4.6 9.8l4.6-1.8z'),
    P('M17.6 14.4l.9 2.3 2.3.9-2.3.9-.9 2.3-.9-2.3-2.3-.9 2.3-.9z'),
  ],
  clock: [C(12, 12, 8.6), P('M12 7.2V12l3.2 2')],
  calendar: [R(3.6, 5.2, 16.8, 15.2, 3), L(3.6, 10, 20.4, 10), L(8.2, 3.2, 8.2, 6.6), L(15.8, 3.2, 15.8, 6.6)],
  user: [C(12, 8.6, 3.9), P('M4.8 20.6a7.2 7.2 0 0 1 14.4 0')],
  bell: [
    P('M6.6 10.2a5.4 5.4 0 0 1 10.8 0c0 4.8 1.8 6.2 1.8 6.2H4.8s1.8-1.4 1.8-6.2z'),
    P('M10.2 19.6a2 2 0 0 0 3.6 0'),
  ],
  'file-text': [
    P('M13.6 3.6H7.2a2 2 0 0 0-2 2v12.8a2 2 0 0 0 2 2h9.6a2 2 0 0 0 2-2V8.8z'),
    P('M13.6 3.6v5.2h5.2'),
    L(8.6, 13.2, 15.4, 13.2),
    L(8.6, 16.4, 13.2, 16.4),
  ],

  /* domain --------------------------------------------------------- */
  dose: [P('M18.2 5.8 8.6 15.4l-3 1 1-3 9.6-9.6z'), P('M14.6 4.6 19.4 9.4'), P('M19.8 2.6l1.6 1.6')],
  pill: [R(3.2, 8.6, 17.6, 6.8, 3.4), L(12, 8.6, 12, 15.4)],
  weight: [R(4, 5, 16, 15.4, 3.2), P('M7.6 11.4a4.6 4.6 0 0 1 8.8 0'), L(12, 11.4, 14.4, 8.2)],
  water: [P('M12 3.4s6 6.3 6 10.1a6 6 0 0 1-12 0c0-3.8 6-10.1 6-10.1z')],
  food: [P('M4 11.4h16a8 8 0 0 1-16 0z'), L(12, 3.4, 13.6, 9), L(16, 3.4, 14.8, 9)],
  training: [
    R(2.4, 9, 3, 6, 1.2),
    R(18.6, 9, 3, 6, 1.2),
    R(5.8, 10.2, 2.6, 3.6, 1),
    R(15.6, 10.2, 2.6, 3.6, 1),
    L(8.4, 12, 15.6, 12),
  ],
  sleep: [P('M20.2 14.8A8.6 8.6 0 0 1 9.2 3.8a8.6 8.6 0 1 0 11 11z')],
  symptom: [P('M12 4.4l8.6 15.2H3.4z'), L(12, 9.8, 12, 14.4), DOT(12, 17)],
  health: [
    P('M20 8.4a4.4 4.4 0 0 0-8-2.9 4.4 4.4 0 0 0-8 2.9c0 4.9 8 10 8 10s8-5.1 8-10z'),
    P('M4.6 11.4h3.8l1.6-3 2.6 5.2 1.5-2.2h5.3'),
  ],
  lab: [
    P('M9.4 3.6h5.2'),
    P('M10.6 3.6v5.8L5.2 18.6A1.6 1.6 0 0 0 6.6 21h10.8a1.6 1.6 0 0 0 1.4-2.4L13.4 9.4V3.6'),
    L(8.2, 15, 15.8, 15),
  ],
  flame: [P('M12 3s5 4.6 5 9.1a5 5 0 0 1-10 0c0-1.6.8-3 1.7-4.2C10 9.5 12 8 12 3z')],
  protein: [P('M12 3.4c3.6 0 6.6 4.9 6.6 9.1a6.6 6.6 0 0 1-13.2 0c0-4.2 3-9.1 6.6-9.1z')],
  carb: [
    P('M12 20.8V8.4'),
    P('M12 12.2c-3 0-4.6-2-4.6-4.6 3 0 4.6 2 4.6 4.6z'),
    P('M12 12.2c3 0 4.6-2 4.6-4.6-3 0-4.6 2-4.6 4.6z'),
    P('M12 17c-3 0-4.6-2-4.6-4.6 3 0 4.6 2 4.6 4.6z'),
    P('M12 17c3 0 4.6-2 4.6-4.6-3 0-4.6 2-4.6 4.6z'),
  ],
  fat: [P('M12 3.4s5.4 5.9 5.4 9.4a5.4 5.4 0 0 1-10.8 0c0-3.5 5.4-9.4 5.4-9.4z')],
  fiber: [P('M20 4c0 9-5.4 13.2-10 13.2a5 5 0 0 1 0-10c3 0 5-1 10-3.2z'), P('M4.4 20.4c3-5 6.8-8 10.8-9.4')],

  /* fallback ------------------------------------------------------- */
  dot: [C(12, 12, 4.2)],
  circle: [C(12, 12, 8.6)],
};

export const ICON_NAMES = Object.keys(ICONS);

function buildShapes(node, shapes) {
  for (const shape of shapes) {
    if (!shape) continue;
    const [tag, attrs, children] = shape;
    const child = svgEl(tag, attrs || null);
    if (children && children.length) buildShapes(child, children);
    node.appendChild(child);
  }
}

/**
 * Build an icon element.
 * @param {string} name  one of ICON_NAMES (unknown names fall back to a dot)
 * @param {{size?:number, stroke?:number, className?:string, fill?:boolean, title?:string}} [opts]
 * @returns {SVGElement}
 */
export function icon(name, opts) {
  const options = opts || {};
  const size = Number.isFinite(options.size) ? options.size : 20;
  const stroke = Number.isFinite(options.stroke) ? options.stroke : 1.8;
  const shapes = ICONS[name] || ICONS.dot;

  const node = svgEl('svg', {
    class: 'icon' + (options.className ? ' ' + options.className : ''),
    viewBox: '0 0 24 24',
    width: size,
    height: size,
    fill: options.fill ? 'currentColor' : 'none',
    stroke: 'currentColor',
    'stroke-width': stroke,
    'stroke-linecap': 'round',
    'stroke-linejoin': 'round',
    'aria-hidden': 'true',
    focusable: 'false',
  });
  if (options.title) {
    node.setAttribute('role', 'img');
    node.removeAttribute('aria-hidden');
    node.appendChild(svgEl('title', { text: options.title }));
  }
  buildShapes(node, shapes);
  return node;
}

export default icon;
