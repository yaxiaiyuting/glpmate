/**
 * router.js — hand-rolled hash router.
 *
 * Views are modules exporting `render(container, ctx)` which may return a
 * cleanup function (or an object with `destroy`).  The router clears the
 * outlet and runs the previous cleanup before mounting the next view, so
 * charts and timers never leak between screens.
 */

import { clear, el } from './dom.js';
import { set } from './store.js';

/** '#/data?month=2026-10' -> { path:'/data', params:{month:'2026-10'} } */
export function parseHash(hash) {
  let raw = typeof hash === 'string' && hash ? hash : typeof location !== 'undefined' ? location.hash : '';
  raw = raw.replace(/^#/, '');
  if (!raw) raw = '/today';
  if (raw.charAt(0) !== '/') raw = '/' + raw;
  const questionMark = raw.indexOf('?');
  const path = questionMark === -1 ? raw : raw.slice(0, questionMark);
  const queryString = questionMark === -1 ? '' : raw.slice(questionMark + 1);
  const params = {};
  if (queryString) {
    for (const pair of queryString.split('&')) {
      if (!pair) continue;
      const eq = pair.indexOf('=');
      const key = decodeURIComponent(eq === -1 ? pair : pair.slice(0, eq));
      const value = eq === -1 ? '' : decodeURIComponent(pair.slice(eq + 1).replace(/\+/g, ' '));
      params[key] = value;
    }
  }
  return { path: path.replace(/\/+$/, '') || '/today', params: params };
}

/** Build a hash for a route + params. */
export function href(path, params) {
  let out = path.charAt(0) === '/' ? path : '/' + path;
  const parts = [];
  if (params) {
    for (const key of Object.keys(params)) {
      const value = params[key];
      if (value === null || value === undefined || value === '') continue;
      parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(String(value)));
    }
  }
  return '#' + out + (parts.length ? '?' + parts.join('&') : '');
}

export function navigate(path, params, opts) {
  const target = path.charAt(0) === '#' ? path : href(path, params);
  if (location.hash === target) {
    if (!opts || opts.force !== false) window.dispatchEvent(new HashChangeEvent('hashchange'));
    return;
  }
  if (opts && opts.replace) location.replace(target);
  else location.hash = target;
}

export function back() {
  history.back();
}

/**
 * @param {{outlet:HTMLElement, routes:Array<{path:string, view:object, title?:string}>,
 *          fallback?:string, onChange?:Function, onError?:Function}} options
 */
export function createRouter(options) {
  const outlet = options.outlet;
  const routes = options.routes || [];
  const fallback = options.fallback || '/today';
  const onChange = options.onChange;
  const byPath = new Map(routes.map((r) => [r.path, r]));

  let current = null;
  let token = 0;

  function teardown() {
    if (current && typeof current.cleanup === 'function') {
      try {
        current.cleanup();
      } catch (err) {
        if (typeof console !== 'undefined') console.error('[router] cleanup failed', err);
      }
    }
    current = null;
  }

  function errorPanel(err) {
    return el(
      'div',
      { class: 'view' },
      el(
        'div',
        { class: 'card card--error' },
        el('h2', { class: 'card-title', text: '这个页面加载失败' }),
        el('p', { class: 'card-sub', text: err && err.message ? err.message : '未知错误' }),
        el(
          'button',
          { class: 'btn btn--ghost', type: 'button', onClick: () => render() },
          '重试'
        )
      )
    );
  }

  async function render() {
    const { path, params } = parseHash();
    let route = byPath.get(path);
    if (!route) {
      route = byPath.get(fallback);
      if (path !== fallback) {
        // unknown route: normalise the URL but keep the app usable
        location.replace('#' + fallback);
      }
    }
    if (!route) return;

    const myToken = (token += 1);
    teardown();
    clear(outlet);
    set({ route: '#' + route.path });

    try {
      const result = await route.view.render(outlet, { params: params, path: route.path, navigate: navigate });
      let cleanup = null;
      if (typeof result === 'function') cleanup = result;
      else if (result && typeof result.destroy === 'function') cleanup = () => result.destroy();
      if (myToken !== token) {
        if (cleanup) cleanup();
        return;
      }
      current = { path: route.path, cleanup: cleanup };
    } catch (err) {
      if (typeof console !== 'undefined') console.error('[router] render failed', err);
      if (myToken !== token) return;
      clear(outlet);
      outlet.appendChild(errorPanel(err));
      current = { path: route.path, cleanup: null };
      if (options.onError) options.onError(err);
    }

    if (onChange) onChange(route.path, params);
    window.scrollTo({ top: 0, behavior: 'auto' });
  }

  function onHashChange() {
    render();
  }

  return {
    start() {
      window.addEventListener('hashchange', onHashChange);
      if (!location.hash) location.replace('#' + fallback);
      render();
      return () => window.removeEventListener('hashchange', onHashChange);
    },
    render: render,
    navigate: navigate,
    teardown: teardown,
    currentPath() {
      return current ? current.path : null;
    },
  };
}

export default createRouter;
