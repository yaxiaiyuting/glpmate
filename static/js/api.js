/**
 * api.js — the single place that talks to the backend.
 *
 * Every call returns parsed JSON and every failure is surfaced as an
 * `ApiError` (optionally toasted so views can stay terse).  All endpoints are
 * same-origin `/api/...`; there are no third-party requests anywhere.
 *
 * When the backend is unreachable the module falls back once to the local
 * fixtures in `demo.js` and tells the user through a toast.
 */

import { toast } from './components/toast.js';
import * as demo from './demo.js';

const BASE = '/api';

export class ApiError extends Error {
  constructor(message, status, payload) {
    super(message);
    this.name = 'ApiError';
    this.status = status || 0;
    this.payload = payload || null;
    this.code = payload && payload.error ? payload.error : status === 0 ? 'network' : 'http_' + status;
  }
}

function buildQuery(query) {
  if (!query) return '';
  const parts = [];
  for (const key of Object.keys(query)) {
    const value = query[key];
    if (value === null || value === undefined || value === '') continue;
    parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(String(value)));
  }
  return parts.length ? '?' + parts.join('&') : '';
}

function describe(status) {
  if (status === 0) return '网络连接失败';
  if (status === 400) return '请求参数有误';
  if (status === 401 || status === 403) return '没有权限';
  if (status === 404) return '接口不存在';
  if (status === 409) return '数据冲突';
  if (status === 429) return '请求过于频繁';
  if (status >= 500) return '服务器出错了 (' + status + ')';
  return '请求失败 (' + status + ')';
}

export function isDemo() {
  return demo.isActive();
}

export function demoFromUrl() {
  return demo.fromUrl();
}

export function enableDemo() {
  demo.enable();
}

/**
 * Core request helper.
 * @param {string} method
 * @param {string} path
 * @param {{query?:object, body?:any, form?:FormData, silent?:boolean, allowError?:boolean}} [opts]
 */
async function request(method, path, opts) {
  const o = opts || {};
  const url = BASE + path + buildQuery(o.query);

  const viaDemo = () => demo.handle(method, path, o.query || {}, o.body);

  if (demo.isActive()) {
    try {
      return await Promise.resolve(viaDemo());
    } catch (err) {
      const wrapped = err instanceof ApiError ? err : new ApiError(err.message || '演示数据出错', err.status || 0, err.payload);
      if (!o.silent) toast(wrapped.message, 'error');
      throw wrapped;
    }
  }

  const init = { method: method, headers: { Accept: 'application/json' }, credentials: 'same-origin' };
  if (o.form) {
    init.body = o.form;
  } else if (o.body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(o.body);
  }

  let response;
  try {
    response = await fetch(url, init);
  } catch (netErr) {
    if (!demo.hasProbed()) {
      demo.enable();
      if (!o.silent) toast('未连接后端，已切换到演示数据', 'info');
      try {
        return await Promise.resolve(demo.handle(method, path, o.query || {}, o.body));
      } catch (err) {
        throw new ApiError(err.message || '演示数据出错', err.status || 0, err.payload);
      }
    }
    const failure = new ApiError('网络连接失败，请检查服务是否在运行', 0, null);
    if (!o.silent) toast(failure.message, 'error');
    throw failure;
  }

  demo.markProbed();

  let payload = null;
  const text = await response.text();
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch (parseErr) {
      payload = null;
    }
  }

  if (!response.ok) {
    const message = (payload && (payload.message || payload.detail)) || describe(response.status);
    const failure = new ApiError(message, response.status, payload);
    if (!o.silent && !o.allowError) toast(failure.message, 'error');
    throw failure;
  }

  // The contract sometimes reports failures inside a 200 body.
  if (payload && typeof payload === 'object' && payload.error && !o.allowError) {
    const failure = new ApiError(payload.message || describe(response.status), response.status, payload);
    if (!o.silent) toast(failure.message, 'error');
    throw failure;
  }

  return payload;
}

/* ------------------------------------------------------------------ *
 * endpoints
 * ------------------------------------------------------------------ */

export const api = {
  bootstrap: (o) => request('GET', '/bootstrap', o),

  records: (query, o) => request('GET', '/records', Object.assign({ query: query }, o)),
  createRecord: (payload, o) => request('POST', '/records', Object.assign({ body: payload }, o)),
  updateRecord: (id, payload, o) => request('PATCH', '/records/' + encodeURIComponent(id), Object.assign({ body: payload }, o)),
  deleteRecord: (id, o) => request('DELETE', '/records/' + encodeURIComponent(id), o),
  summary: (period, o) => request('GET', '/records/summary', Object.assign({ query: { period: period || 'week' } }, o)),

  calendar: (month, o) => request('GET', '/calendar', Object.assign({ query: { month: month } }, o)),
  weightChart: (days, o) => request('GET', '/charts/weight', Object.assign({ query: { days: days || 90 } }, o)),
  levelChart: (days, o) => request('GET', '/charts/level', Object.assign({ query: { days: days || 14 } }, o)),

  coachMessages: (o) => request('GET', '/coach/messages', o),
  sendCoachMessage: (text, o) => request('POST', '/coach/messages', Object.assign({ body: { text: text } }, o)),
  coachPhoto: (file, o) => {
    const form = new FormData();
    form.append('file', file);
    return request('POST', '/coach/photo', Object.assign({ form: form }, o));
  },
  coachConfirm: (cardId, action, o) =>
    request('POST', '/coach/confirm', Object.assign({ body: { card_id: cardId, action: action } }, o)),

  getSettings: (o) => request('GET', '/settings', o),
  putSettings: (payload, o) => request('PUT', '/settings', Object.assign({ body: payload }, o)),
  testSettings: (o) => request('POST', '/settings/test', Object.assign({ allowError: true }, o)),

  /** 注册试验疗效参考（只做参照，不参与计算）。 */
  efficacy: (drugId, o) => request('GET', '/efficacy', Object.assign({ query: { drug: drugId || '' } }, o)),
  drugs: (o) => request('GET', '/drugs', o),

  exportAll: (o) => request('GET', '/export', o),
  importAll: (payload, o) => request('POST', '/import', Object.assign({ body: payload }, o)),
  /** Bundled backend: wipes every record. Optional — callers fall back to
   *  deleting records one by one when the route is missing. */
  wipe: (o) => request('POST', '/wipe', Object.assign({ body: { confirm: 'DELETE', keep_settings: true } }, o)),
};

/** Convenience: unwrap `{items:[...]}` style responses into an array. */
export function itemsOf(payload) {
  if (!payload) return [];
  if (Array.isArray(payload)) return payload;
  if (Array.isArray(payload.items)) return payload.items;
  return [];
}

export default api;
