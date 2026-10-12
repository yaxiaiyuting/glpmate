/**
 * app.js — entry point: builds the shell, wires the router and the tab bar.
 *
 * Loaded directly by the browser as an ES module; there is no bundler and no
 * network dependency beyond this origin.
 */

import { el, clear } from './dom.js';
import { icon } from './icons.js';
import { createRouter, navigate } from './router.js';
import * as store from './store.js';
import { toast } from './components/toast.js';
import * as demo from './demo.js';
import * as todayView from './views/today.js';
import * as dataView from './views/data.js';
import * as coachView from './views/coach.js';
import * as settingsView from './views/settings.js';

const TABS = [
  { path: '/today', label: '今天', icon: 'today', view: todayView },
  { path: '/data', label: '数据', icon: 'data', view: dataView },
  { path: '/coach', label: '助手', icon: 'coach', view: coachView },
  { path: '/settings', label: '设置', icon: 'settings', view: settingsView },
];

let router = null;

/* ------------------------------------------------------------------ *
 * shell
 * ------------------------------------------------------------------ */

function buildTabBar() {
  const bar = el('nav', { class: 'tabbar', 'aria-label': '主导航' });
  for (const tab of TABS) {
    bar.appendChild(
      el(
        'a',
        {
          class: 'tab',
          href: '#' + tab.path,
          dataset: { route: tab.path },
          'aria-label': tab.label,
        },
        el('span', { class: 'tab-icon' }, icon(tab.icon, { size: 22 })),
        el('span', { class: 'tab-label', text: tab.label })
      )
    );
  }
  return bar;
}

function markActiveTab(path) {
  const tabs = document.querySelectorAll('.tab');
  for (const tab of tabs) {
    const active = tab.dataset.route === path;
    tab.classList.toggle('is-active', active);
    if (active) tab.setAttribute('aria-current', 'page');
    else tab.removeAttribute('aria-current');
  }
}

function demoBanner() {
  return el(
    'div',
    { class: 'demo-banner', role: 'status' },
    icon('wifi-off', { size: 14 }),
    el('span', { text: '演示数据 · 未连接后端' })
  );
}

/* ------------------------------------------------------------------ *
 * self-diagnosis
 * ------------------------------------------------------------------ */

/**
 * `?diag=1` 时把运行期错误写进 DOM（#diag）。
 * 手机上没有控制台，出问题时打开这个参数，页面自己会把错误显示出来，
 * 也方便用 --dump-dom 之类的工具做无头排查。
 */
function installDiagnostics() {
  if (!/[?&]diag=1/.test(location.search)) return;
  const box = el('div', { id: 'diag', class: 'diag', hidden: false });
  box.appendChild(el('p', { class: 'diag-title', text: '运行诊断（?diag=1）' }));
  const note = (label, text) => {
    box.appendChild(el('p', { class: 'diag-line', text: label + ': ' + text }));
  };
  const onError = (event) => {
    note('error', (event && event.message) || 'unknown');
    if (event && event.filename) note('at', event.filename + ':' + (event.lineno || 0));
  };
  window.addEventListener('error', onError, true);
  window.addEventListener('unhandledrejection', (event) => {
    const reason = event && event.reason;
    note('unhandledrejection', (reason && (reason.stack || reason.message)) || String(reason));
  });
  const originalError = console.error;
  console.error = function (...args) {
    note('console.error', args.map((a) => (a && a.stack) || String(a)).join(' '));
    originalError.apply(console, args);
  };
  document.body.appendChild(box);
  note('readyState', document.readyState);
  note('modules', typeof window.__glpmateBooted);
}

/* ------------------------------------------------------------------ *
 * boot
 * ------------------------------------------------------------------ */

function boot() {
  const app = document.getElementById('app') || document.body;
  const outlet = document.getElementById('outlet');
  const tabHost = document.getElementById('tabbar');

  if (!outlet) {
    app.appendChild(el('p', { class: 'boot-error', text: '缺少 #outlet 容器，无法启动。' }));
    return;
  }

  const tabBar = buildTabBar();
  if (tabHost) {
    clear(tabHost);
    tabHost.appendChild(tabBar);
  } else {
    app.appendChild(tabBar);
  }

  router = createRouter({
    outlet: outlet,
    routes: TABS.map((tab) => ({ path: tab.path, view: tab.view })),
    fallback: '/today',
    onChange: (path) => markActiveTab(path),
  });

  router.start();
  markActiveTab(router.currentPath() || '/today');

  // first paint of shared data
  store.loadBootstrap().catch(() => {
    toast('加载数据失败，正在使用空状态', 'error');
  });

  if (store.state.demo || demo.isActive()) {
    document.body.appendChild(demoBanner());
  }

  document.body.classList.add('is-ready');
  window.__glpmateBooted = true;
}

installDiagnostics();

/* ------------------------------------------------------------------ *
 * global error surfacing
 * ------------------------------------------------------------------ */

window.addEventListener('error', (event) => {
  if (event && event.message) toast('出错了：' + event.message, 'error');
});

window.addEventListener('unhandledrejection', (event) => {
  const reason = event && event.reason;
  if (reason && reason.name === 'ApiError') return; // already toasted by api.js
  const message = reason && reason.message ? reason.message : '未知错误';
  toast('操作失败：' + message, 'error');
});

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', boot);
} else {
  boot();
}

export { boot, TABS, navigate };
export default { boot };
