/**
 * store.js — in-memory application state + a tiny pub/sub.
 *
 * Views never talk to each other: they read `state`, call the async actions
 * here (or `api.*` directly) and re-render from the `subscribe()` callback.
 */

import { api, isDemo } from './api.js';

export const state = {
  /* boot */
  ready: false,
  loading: false,
  error: null,
  demo: false,

  /* identity / day */
  today: '',
  weekday: 0,
  dayIndex: 1,
  profile: {},
  settings: {},
  targets: {},

  /* today screen */
  stats: {},
  nextDose: null,
  level: null,
  levelRange: 'drug', // 'drug' | 'weight'
  weight: null,
  weightBoot: null,
  week: null,
  schedule: [],
  recent: [],
  completed: [],
  quickTypes: [],
  feelings: [],
  appMeta: {},

  /* coach */
  coachQuota: { used: 0, limit: 10 },
  coachMessages: [],
  coachSending: false,
  coachLocked: false,

  /* data screen */
  summary: null,
  calendarMonth: '',
  calendarDays: {},
  selectedDate: '',
  dayRecords: [],

  /* routing */
  route: '#/today',
};

/* ------------------------------------------------------------------ *
 * pub/sub
 * ------------------------------------------------------------------ */

const listeners = new Set();

/** @returns {() => void} unsubscribe */
export function subscribe(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

export function notify() {
  for (const fn of Array.from(listeners)) {
    try {
      fn(state);
    } catch (err) {
      if (typeof console !== 'undefined') console.error('[store] listener failed', err);
    }
  }
}

/** Shallow-merge a patch into state and notify subscribers. */
export function set(patch) {
  if (patch) Object.assign(state, patch);
  notify();
  return state;
}

export function get() {
  return state;
}

/**
 * Re-run `render` whenever one of `keys` changes identity in the store.
 * Views use this instead of subscribing to everything (avoids render storms).
 * @param {string[]} keys
 * @param {Function} render
 * @returns {() => void} unsubscribe
 */
export function connect(keys, render) {
  const list = Array.isArray(keys) ? keys : [keys];
  let last = list.map((key) => state[key]);
  return subscribe(() => {
    const next = list.map((key) => state[key]);
    let changed = false;
    for (let i = 0; i < next.length; i += 1) {
      if (next[i] !== last[i]) {
        changed = true;
        break;
      }
    }
    if (!changed) return;
    last = next;
    render();
  });
}

/* ------------------------------------------------------------------ *
 * formatting helpers (shared by every view)
 * ------------------------------------------------------------------ */

export function pad2(n) {
  return (n < 10 ? '0' : '') + n;
}

/** Date -> 'YYYY-MM-DD' (local time, never toISOString). */
export function dayKey(date) {
  const d = date instanceof Date ? date : new Date(date);
  if (!Number.isFinite(d.getTime())) return '';
  return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
}

/** '2026-10-12T07:12:00' -> '07:12' */
export function timeOf(value) {
  if (typeof value !== 'string' || value.length < 16) return '';
  const m = /T(\d{2}):(\d{2})/.exec(value);
  return m ? m[1] + ':' + m[2] : '';
}

export const WEEKDAY_LONG = ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期日'];

export function weekdayName(index) {
  const i = Number(index);
  if (!Number.isFinite(i)) return '';
  return WEEKDAY_LONG[((i % 7) + 7) % 7];
}

/** Localised date such as '10月12日'. */
export function shortDate(value) {
  const d = value instanceof Date ? value : new Date(value);
  if (!Number.isFinite(d.getTime())) return '';
  return d.getMonth() + 1 + '月' + d.getDate() + '日';
}

/** Trim trailing zeros: 144.0 -> '144', 0.82 -> '0.82' */
export function fmtNum(value, digits) {
  const n = typeof value === 'number' ? value : parseFloat(value);
  if (!Number.isFinite(n)) return '—';
  const fixed = Number.isFinite(digits) ? n.toFixed(digits) : String(Math.round(n * 100) / 100);
  return fixed.replace(/\.0+$/, '').replace(/(\.\d*?)0+$/, '$1');
}

/** Keep the decimals (144 -> '144.0') for values that read better fixed. */
export function fixedNum(value, digits) {
  const n = typeof value === 'number' ? value : parseFloat(value);
  if (!Number.isFinite(n)) return '—';
  return n.toFixed(Number.isFinite(digits) ? digits : 1);
}

/** '+0.4' / '-0.4' with a fixed number of decimals. */
export function signedNum(value, digits) {
  const n = typeof value === 'number' ? value : parseFloat(value);
  if (!Number.isFinite(n)) return '—';
  const places = Number.isFinite(digits) ? digits : 1;
  return (n > 0 ? '+' : n < 0 ? '-' : '+') + Math.abs(n).toFixed(places);
}

/** "3 分钟前" / "昨天" style relative day label. */
export function relativeDay(key) {
  if (!state.today || !key) return '';
  if (key === state.today) return '今天';
  const a = new Date(state.today + 'T00:00:00').getTime();
  const b = new Date(key + 'T00:00:00').getTime();
  if (!Number.isFinite(a) || !Number.isFinite(b)) return key;
  const diff = Math.round((a - b) / 86400000);
  if (diff === 1) return '昨天';
  if (diff === 2) return '前天';
  if (diff > 0) return diff + ' 天前';
  if (diff === -1) return '明天';
  return key;
}

export function clampPct(value, total) {
  const v = Number(value) || 0;
  const t = Number(total) || 0;
  if (t <= 0) return 0;
  return Math.max(0, Math.min(1, v / t));
}

/* ------------------------------------------------------------------ *
 * async actions
 * ------------------------------------------------------------------ */

let bootstrapPromise = null;

/** Load (once) the bootstrap payload that every screen depends on. */
export function loadBootstrap(force) {
  if (bootstrapPromise && !force) return bootstrapPromise;
  set({ loading: true, error: null });
  bootstrapPromise = api
    .bootstrap({ silent: true })
    .then((payload) => {
      const data = payload || {};
      set({
        ready: true,
        loading: false,
        error: null,
        demo: isDemo(),
        today: data.today || dayKey(new Date()),
        weekday: Number.isFinite(data.weekday) ? data.weekday : new Date().getDay(),
        dayIndex: Number.isFinite(data.day_index) ? data.day_index : 1,
        profile: data.profile || {},
        settings: data.settings || {},
        targets: data.targets || {},
        stats: data.today_stats || {},
        nextDose: data.next_dose || null,
        level: data.level || null,
        weightBoot: data.weight || null,
        week: data.week || null,
        schedule: Array.isArray(data.schedule) ? data.schedule : [],
        recent: Array.isArray(data.recent) ? data.recent : [],
        completed: Array.isArray(data.completed) ? data.completed : [],
        quickTypes: Array.isArray(data.quick_types) ? data.quick_types : [],
        feelings: Array.isArray(data.side_effect_chips)
          ? data.side_effect_chips
          : Array.isArray(data.feelings) ? data.feelings : [],
        coachQuota: data.coach_quota || { used: 0, limit: 0 },
        appMeta: {
          version: data.version || (state.appMeta && state.appMeta.version) || '',
          license: data.license || (state.appMeta && state.appMeta.license) || '',
        },
        calendarMonth: (data.today || dayKey(new Date())).slice(0, 7),
        selectedDate: data.today || dayKey(new Date()),
      });
      return state;
    })
    .catch((err) => {
      bootstrapPromise = null;
      set({ loading: false, ready: false, error: err && err.message ? err.message : '加载失败' });
      throw err;
    });
  return bootstrapPromise;
}

export function reloadBootstrap() {
  bootstrapPromise = null;
  return loadBootstrap(true).catch(() => state);
}

export function loadWeightChart(days) {
  return api
    .weightChart(days || 90, { silent: true })
    .then((payload) => {
      set({ weight: payload || null });
      return payload;
    })
    .catch(() => null);
}

export function loadLevelChart(days) {
  return api
    .levelChart(days || 14, { silent: true })
    .then((payload) => {
      if (payload) set({ level: payload });
      return payload;
    })
    .catch(() => null);
}

export function loadSummary(period) {
  return api
    .summary(period || 'week', { silent: true })
    .then((payload) => {
      set({ summary: payload || null });
      return payload;
    })
    .catch(() => null);
}

export function loadCalendar(month) {
  const target = month || state.calendarMonth || state.today.slice(0, 7);
  return api
    .calendar(target, { silent: true })
    .then((payload) => {
      set({ calendarMonth: target, calendarDays: (payload && payload.days) || {} });
      return payload;
    })
    .catch(() => {
      set({ calendarMonth: target, calendarDays: {} });
      return null;
    });
}

export function loadDayRecords(date) {
  const key = date || state.selectedDate || state.today;
  return api
    .records({ start: key, end: key }, { silent: true })
    .then((payload) => {
      set({ selectedDate: key, dayRecords: (payload && payload.items) || [] });
      return payload;
    })
    .catch(() => {
      set({ selectedDate: key, dayRecords: [] });
      return null;
    });
}

export function loadCoach() {
  return api
    .coachMessages({ silent: true })
    .then((payload) => {
      set({
        coachMessages: (payload && payload.items) || [],
        coachQuota: (payload && payload.quota) || state.coachQuota,
      });
      return payload;
    })
    .catch(() => null);
}

/**
 * `GET /api/settings` may return the flat settings object (documented
 * contract) or wrap it as `{settings, profile, targets, schedule, version…}`
 * (the bundled Python backend).  Normalise both into one shape.
 */
export function unwrapSettings(payload) {
  if (!payload || typeof payload !== 'object') return { settings: {}, profile: null, targets: null };
  const wrapped = payload.settings && typeof payload.settings === 'object';
  const settings = Object.assign({}, wrapped ? payload.settings : payload);
  if (settings.ai_api_key_set === undefined) {
    settings.ai_api_key_set = Boolean(settings.ai_api_key);
  }
  return {
    settings: settings,
    profile: payload.profile && typeof payload.profile === 'object' ? payload.profile : null,
    targets: payload.targets && typeof payload.targets === 'object' ? payload.targets : null,
    schedule: Array.isArray(payload.schedule) ? payload.schedule : null,
    appMeta: {
      version: payload.version || '',
      license: payload.license || '',
    },
  };
}

export function loadSettings() {
  return api
    .getSettings({ silent: true })
    .then((payload) => {
      const norm = unwrapSettings(payload);
      const patch = { settings: norm.settings };
      if (norm.profile) patch.profile = Object.assign({}, state.profile, norm.profile);
      if (norm.targets) patch.targets = Object.assign({}, state.targets, norm.targets);
      if (norm.schedule) patch.schedule = norm.schedule;
      if (norm.appMeta.version) patch.appMeta = Object.assign({}, state.appMeta, norm.appMeta);
      set(patch);
      return norm.settings;
    })
    .catch(() => null);
}

export function setQuota(quota) {
  if (!quota) return;
  set({ coachQuota: { used: Number(quota.used) || 0, limit: Number(quota.limit) || 0 } });
}

export default { state, set, get, subscribe, notify, connect, loadBootstrap, unwrapSettings };
