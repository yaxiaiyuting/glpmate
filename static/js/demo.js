/**
 * demo.js — optional offline fixture layer.
 *
 * The app talks to the real JSON API by default.  When the backend is not
 * reachable (or when the page is opened with `?demo=1` / `#/today?demo=1`)
 * this module serves the exact same contract shapes from memory so the UI can
 * be reviewed standalone.  Nothing here is used once a real response arrives.
 */

const TODAY = '2026-10-12';

let mode = 'off'; // 'off' | 'on'
let probed = false;

export function isActive() {
  return mode === 'on';
}

export function hasProbed() {
  return probed;
}

export function enable() {
  mode = 'on';
  probed = true;
  seed();
}

export function markProbed() {
  probed = true;
}

export function fromUrl(search) {
  const q = String(search === undefined ? (typeof location !== 'undefined' ? location.search + location.hash : '') : search);
  return /(^|[?&#/])demo=1(&|$)/.test(q);
}

/* ------------------------------------------------------------------ *
 * fixture state
 * ------------------------------------------------------------------ */

const FEELINGS = ['我很好', '恶心', '便秘', '疲劳', '头痛', '腹胀', '食欲下降', '反酸'];

export const QUICK_TYPES = [
  { type: 'weight', label: '体重', icon: 'weight', color: '#3DBFA0', unit: 'kg' },
  { type: 'body_fat', label: '体脂', icon: 'health', color: '#8B7BD8', unit: '%' },
  { type: 'lab', label: '化验', icon: 'lab', color: '#4A9BE8', unit: '' },
  { type: 'blood_pressure', label: '血压', icon: 'health', color: '#E8607A', unit: 'mmHg' },
  { type: 'water', label: '饮水', icon: 'water', color: '#4A9BE8', unit: 'ml' },
  { type: 'food', label: '餐食', icon: 'food', color: '#E8607A', unit: 'kcal' },
  { type: 'training', label: '训练', icon: 'training', color: '#3DBFA0', unit: '分钟' },
  { type: 'sleep', label: '睡眠', icon: 'sleep', color: '#8B7BD8', unit: '小时' },
  { type: 'symptom', label: '副作用', icon: 'symptom', color: '#8B7BD8', unit: '' },
  { type: 'dose', label: '剂量', icon: 'dose', color: '#E8A33D', unit: 'mg' },
  { type: 'mood', label: '心情', icon: 'health', color: '#35C6E8', unit: '' },
  { type: 'waist', label: '腰围', icon: 'weight', color: '#3DBFA0', unit: 'cm' },
  { type: 'glucose', label: '血糖', icon: 'lab', color: '#E8A33D', unit: 'mmol/L' },
  { type: 'note', label: '备注', icon: 'file-text', color: '#9AA5AD', unit: '' },
  { type: 'steps', label: '步数', icon: 'training', color: '#3DBFA0', unit: '步' },
];

function seedRecords() {
  const rows = [
    [1, 'weight', TODAY + 'T07:12:00', '体重', '144.0 kg', 144.0, 'kg', { goal: 80 }],
    // the three meals below add up to the reference day:
    // 1000 kcal / 蛋白质 50 g / 碳水 150 g / 脂肪 24 g / 纤维 0 g
    [2, 'food', TODAY + 'T12:20:00', '午餐', '鸡胸肉沙拉 · 420 kcal', 420, 'kcal', { slot: 'lunch', desc: '鸡胸肉沙拉', kcal: 420, protein_g: 24, carb_g: 50, fat_g: 10, fiber_g: 0 }],
    [3, 'food', TODAY + 'T08:05:00', '早餐', '希腊酸奶 + 蓝莓 · 310 kcal', 310, 'kcal', { slot: 'breakfast', desc: '希腊酸奶 + 蓝莓', kcal: 310, protein_g: 20, carb_g: 40, fat_g: 8, fiber_g: 0 }],
    [4, 'food', TODAY + 'T18:40:00', '晚餐', '红烧鲫鱼 + 米饭 · 270 kcal', 270, 'kcal', { slot: 'dinner', desc: '红烧鲫鱼 + 米饭', kcal: 270, protein_g: 6, carb_g: 60, fat_g: 6, fiber_g: 0 }],
    [5, 'water', TODAY + 'T09:00:00', '饮水', '250 ml', 250, 'ml', { amount_ml: 250 }],
    [14, 'water', TODAY + 'T11:20:00', '饮水', '300 ml', 300, 'ml', { amount_ml: 300 }],
    [15, 'water', TODAY + 'T15:40:00', '饮水', '250 ml', 250, 'ml', { amount_ml: 250 }],
    [6, 'dose', TODAY + 'T12:00:00', '剂量', '穆峰达 2.5 mg', 2.5, 'mg', { drug: '穆峰达', site: '腹部左侧' }],
    [7, 'weight', '2026-10-11T07:05:00', '体重', '144.2 kg', 144.2, 'kg', {}],
    [8, 'food', '2026-10-11T12:30:00', '午餐', '三文鱼饭 · 520 kcal', 520, 'kcal', { meal: 'lunch' }],
    [9, 'water', '2026-10-11T10:00:00', '饮水', '500 ml', 500, 'ml', {}],
    [10, 'training', '2026-10-11T19:00:00', '训练', '快走 40 分钟', 40, '分钟', {}],
    [11, 'weight', '2026-10-10T07:20:00', '体重', '144.6 kg', 144.6, 'kg', {}],
    [12, 'symptom', '2026-10-10T21:00:00', '副作用', '轻度恶心', 2, '级', {}],
    [13, 'sleep', '2026-10-10T23:30:00', '睡眠', '7.2 小时', 7.2, '小时', {}],
  ];
  return rows.map((r) => ({
    id: r[0], type: r[1], at: r[2], title: r[3], subtitle: r[4], value: r[5], unit: r[6], meta: r[7],
  }));
}

let records = seedRecords();
let nextId = 100;
let settings = {
  ai_base_url: 'https://api.deepseek.com',
  ai_model: 'deepseek-chat',
  ai_api_key: 'sk-…abcd',
  ai_api_key_set: true,
  vision_base_url: '',
  vision_model: '',
  daily_quota: 10,
  temperature: 0.6,
  display_name: '姜天佑',
  language: 'zh-CN',
};

const PROFILE = { display_name: '姜天佑', email: 'jty@example.com', joined: '2026-10-12', start_date: '2026-10-12' };
const TARGETS = { calories: 1600, protein: 90, carb: 180, fat: 55, fiber: 25, water: 5.0, weight_goal: 80 };

let messages = [
  {
    id: 1, role: 'user', at: TODAY + 'T12:02:00',
    text: '今天午餐吃了一碗红烧鲫鱼，大概多少卡路里？',
  },
  {
    id: 2, role: 'assistant', at: TODAY + 'T12:02:30',
    text: '红烧鲫鱼（约 200g）大约 **320 kcal**，蛋白质 36 g、碳水 8 g、脂肪 18 g。\n\n'
      + '已经帮你折算进今日目标：\n\n'
      + '- 卡路里 1000 / 1600\n- 蛋白质 50 / 90\n- 碳水 150 / 180\n\n'
      + '> 估算值仅供参考，不是医疗测量。',
    card: {
      kind: 'meal_confirm', card_id: 1, status: 'added',
      title: '红烧鲫鱼 · 约 200g', subtitle: '320 kcal · 蛋白 36 g · 碳水 8 g · 脂肪 18 g',
    },
  },
  {
    id: 3, role: 'assistant', at: TODAY + 'T12:03:00',
    text: '还需要我帮你记录饮水吗？今天目标是 5.0 升。',
  },
];

let quota = { used: 3, limit: 10 };

function seed() {
  records = seedRecords();
  nextId = 100;
  quota = { used: 3, limit: 10 };
}

/* ------------------------------------------------------------------ *
 * helpers
 * ------------------------------------------------------------------ */

function sumBy(rows, type) {
  return rows
    .filter((r) => r.type === type)
    .reduce((total, r) => total + (Number(r.value) || 0), 0);
}

function key(date) {
  const d = date instanceof Date ? date : new Date(date);
  const p = (n) => (n < 10 ? '0' : '') + n;
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
}

function levelSeries() {
  const series = [];
  const start = new Date('2026-10-09T12:00:00');
  const values = [0.08, 0.14, 0.2, 0.25, 0.29, 0.32, 0.35, 0.4, 0.46, 0.52, 0.58, 0.64, 0.7, 0.76, 0.82];
  for (let i = 0; i < values.length; i += 1) {
    const t = new Date(start.getTime() + i * 7 * 3600 * 1000);
    series.push({ t: t.toISOString().slice(0, 19), mg: values[i], pct: Math.round(values[i] * 33) });
  }
  return {
    pct_peak: 32,
    current_mg: 0.82,
    now_pct: 27,
    series: series,
    annotations: [
      { t: '2026-10-09T12:00:00', kind: 'dose', label: '2.5 mg' },
      { t: '2026-10-12T12:00:00', kind: 'dose', label: '2.5 mg' },
    ],
  };
}

function weightSeries(days) {
  const out = [];
  const n = Math.max(2, Math.min(365, Number(days) || 90));
  for (let i = n - 1; i >= 0; i -= 1) {
    const d = new Date(2026, 9, 12 - i);
    const drift = (n - 1 - i) * 0.055;
    out.push({ t: key(d), v: Math.round((147.4 - drift) * 10) / 10 });
  }
  out[out.length - 1].v = 144.0;
  return { series: out, goal: 80 };
}

function calendarDays() {
  const days = {};
  const put = (day, dots, planned) => {
    days['2026-10-' + (day < 10 ? '0' + day : day)] = {
      dots: dots, planned: !!planned, today: day === 12,
    };
  };
  put(1, ['dose', 'weight', 'water']);
  put(2, ['weight', 'food']);
  put(3, ['weight', 'food', 'water', 'training']);
  put(4, ['weight', 'sleep']);
  put(5, ['dose', 'weight', 'food', 'water']);
  put(6, ['weight', 'side_effect']);
  put(7, ['weight', 'food', 'training', 'water']);
  put(8, ['weight', 'food']);
  put(9, ['weight', 'food', 'water', 'sleep']);
  put(10, ['weight', 'side_effect', 'sleep']);
  put(11, ['weight', 'food', 'water', 'training']);
  put(12, ['dose', 'weight', 'food', 'water']);
  for (const day of [13, 14, 15, 16, 17, 18, 19]) {
    days['2026-10-' + day] = { dots: [], planned: day === 19, today: false };
  }
  return days;
}

function summary() {
  const totals = { calories: 0, protein: 0, carb: 0, fat: 0, fiber: 0, water: 0 };
  for (const r of records) {
    if (!r.at.startsWith(TODAY)) continue;
    if (r.type === 'food') {
      totals.calories += Number(r.value) || 0;
      const m = r.meta || {};
      totals.protein += Number(m.protein_g) || 0;
      totals.carb += Number(m.carb_g) || 0;
      totals.fat += Number(m.fat_g) || 0;
      totals.fiber += Number(m.fiber_g) || 0;
    }
    if (r.type === 'water') {
      const meta = r.meta || {};
      const ml = meta.amount_ml !== undefined ? Number(meta.amount_ml) : Number(r.value) || 0;
      totals.water += (Number.isFinite(ml) ? ml : 0) / 1000;
    }
  }
  totals.water = Math.round(totals.water * 10) / 10;
  return {
    calories: { v: totals.calories, t: TARGETS.calories },
    protein: { v: totals.protein, t: TARGETS.protein },
    carb: { v: totals.carb, t: TARGETS.carb },
    fat: { v: totals.fat, t: TARGETS.fat },
    fiber: { v: totals.fiber, t: TARGETS.fiber },
    water: { v: totals.water, t: TARGETS.water },
    meals_logged: records.filter((r) => r.at.startsWith(TODAY) && r.type === 'food').length,
    side_effects: records.filter((r) => r.at.startsWith(TODAY) && r.type === 'side_effect').length,
    dose_taken: records.some((r) => r.at.startsWith(TODAY) && r.type === 'dose' && !(r.meta && r.meta.skipped)),
    training_min: sumBy(records, 'training'),
    protein_per_day: totals.protein,
  };
}

function bootstrap() {
  return {
    today: TODAY,
    weekday: 0,
    day_index: 1,
    profile: PROFILE,
    settings: Object.assign({}, settings),
    targets: TARGETS,
    today_stats: summary(),
    next_dose: {
      drug: '穆峰达', dose_mg: 2.5, at: '2026-10-19T12:00:00', weekday: 0, time: '12:00',
      days_left: 7, today_time: '12:00', taken_today: true,
    },
    level: levelSeries(),
    recent: records.filter((r) => r.at.startsWith(TODAY)),
    completed: [
      { id: 1, type: 'weight', title: '体重', subtitle: '144.0 kg', value: 144.0, unit: 'kg', at: TODAY + 'T07:12:00' },
      { id: 2, type: 'food', title: '午餐', subtitle: '鸡胸肉沙拉 · 24 g 蛋白', value: 420, unit: 'kcal', at: TODAY + 'T12:20:00' },
      { id: 3, type: 'food', title: '早餐', subtitle: '希腊酸奶 + 蓝莓 · 20 g 蛋白', value: 310, unit: 'kcal', at: TODAY + 'T08:05:00' },
      { id: 6, type: 'dose', title: '剂量', subtitle: '穆峰达 2.5 mg', value: 2.5, unit: 'mg', at: TODAY + 'T12:00:00' },
    ],
    quick_types: QUICK_TYPES,
    coach_quota: quota,
    feelings: FEELINGS,
  };
}

/* ------------------------------------------------------------------ *
 * request handler
 * ------------------------------------------------------------------ */

function fail(status, message, payload) {
  const err = new Error(message);
  err.status = status;
  err.payload = payload || { error: message };
  return err;
}

/**
 * Serve a request from the fixture.
 * @param {string} method
 * @param {string} path  e.g. '/records'
 * @param {object} query parsed query params
 * @param {any} body parsed JSON body (or FormData for photo uploads)
 */
export function handle(method, path, query, body) {
  const m = method.toUpperCase();
  const q = query || {};

  if (path === '/bootstrap' && m === 'GET') return bootstrap();

  if (path === '/records' && m === 'GET') {
    let items = records.slice();
    if (q.start) items = items.filter((r) => r.at.slice(0, 10) >= q.start);
    if (q.end) items = items.filter((r) => r.at.slice(0, 10) <= q.end);
    if (q.types) {
      const want = String(q.types).split(',').map((s) => s.trim()).filter(Boolean);
      items = items.filter((r) => want.indexOf(r.type) !== -1);
    }
    items.sort((a, b) => (a.at < b.at ? 1 : -1));
    return { items: items };
  }

  if (path === '/records' && m === 'POST') {
    const payload = body && typeof body === 'object' ? body : {};
    const type = payload.type || 'note';
    const meta = QUICK_TYPES.find((t) => t.type === type) || { label: type, unit: '' };
    const record = {
      id: (nextId += 1),
      type: type,
      at: payload.at || new Date().toISOString().slice(0, 19),
      title: payload.title || meta.label,
      subtitle: payload.subtitle || (payload.value !== undefined ? payload.value + ' ' + (payload.unit || meta.unit) : ''),
      value: payload.value === undefined ? null : payload.value,
      unit: payload.unit || meta.unit,
      meta: payload.meta || {},
    };
    records.unshift(record);
    return record;
  }

  const recordMatch = /^\/records\/(\d+)$/.exec(path);
  if (recordMatch) {
    const id = Number(recordMatch[1]);
    const index = records.findIndex((r) => r.id === id);
    if (index === -1) throw fail(404, '记录不存在', { error: 'not_found' });
    if (m === 'PATCH') {
      records[index] = Object.assign({}, records[index], body || {});
      return records[index];
    }
    if (m === 'DELETE') {
      records.splice(index, 1);
      return { ok: true, id: id };
    }
  }

  if (path === '/records/summary' && m === 'GET') return summary();

  if (path === '/calendar' && m === 'GET') {
    const month = q.month || TODAY.slice(0, 7);
    return { days: month === '2026-10' ? calendarDays() : {} };
  }

  if (path === '/charts/weight' && m === 'GET') return weightSeries(q.days);
  if (path === '/charts/level' && m === 'GET') return levelSeries();

  if (path === '/coach/messages' && m === 'GET') {
    return { items: messages.slice(), quota: Object.assign({}, quota) };
  }
  if (path === '/coach/messages' && m === 'POST') {
    if (quota.used >= quota.limit) {
      const err = fail(429, '额度已用完', { error: 'quota_exceeded', used: quota.used, limit: quota.limit });
      throw err;
    }
    const text = (body && body.text) || '';
    quota.used += 1;
    const user = { id: (nextId += 1), role: 'user', at: new Date().toISOString().slice(0, 19), text: text };
    const assistant = {
      id: (nextId += 1), role: 'assistant', at: new Date().toISOString().slice(0, 19),
      text: '收到。演示模式下的回答：\n\n- 已记录你的描述\n- 今日蛋白质还差 **40 g**\n\n> 连接真实后端后会返回模型回答。',
    };
    messages.push(user, assistant);
    return { user: user, assistant: assistant, quota: Object.assign({}, quota) };
  }
  if (path === '/coach/photo' && m === 'POST') {
    const card = {
      kind: 'meal_confirm', card_id: (nextId += 1), status: 'pending',
      title: '红烧鲫鱼 · 约 200g', subtitle: '320 kcal · 蛋白 36 g · 碳水 8 g · 脂肪 18 g',
    };
    return {
      estimate: {
        items: [{ name: '红烧鲫鱼', qty: '约 200g', kcal: 320, protein_g: 36, carb_g: 8, fat_g: 18, fiber_g: 1 }],
        total: { kcal: 320, protein_g: 36, carb_g: 8, fat_g: 18, fiber_g: 1 },
        confidence: 'medium',
        note: '演示模式估算，仅供参考。',
      },
      card: card,
      message: { id: (nextId += 1), role: 'assistant', at: new Date().toISOString().slice(0, 19), text: '我识别到一道菜，确认后即可加入今日记录。', card: card },
    };
  }
  if (path === '/coach/confirm' && m === 'POST') {
    const payload = body || {};
    const cardId = payload.card_id;
    for (const msg of messages) {
      if (msg.card && String(msg.card.card_id) === String(cardId)) msg.card.status = payload.action === 'discard' ? 'discarded' : 'added';
    }
    if (payload.action === 'discard') return { ok: true, record_ids: [] };
    const record = {
      id: (nextId += 1), type: 'food', at: new Date().toISOString().slice(0, 19),
      title: '红烧鲫鱼', subtitle: '320 kcal · 蛋白 36 g', value: 320, unit: 'kcal',
      meta: { protein_g: 36, carb_g: 8, fat_g: 18, fiber_g: 1 },
    };
    records.unshift(record);
    return { ok: true, record_ids: [record.id] };
  }

  if (path === '/settings' && m === 'GET') return Object.assign({}, settings);
  if (path === '/settings' && m === 'PUT') {
    const payload = body && typeof body === 'object' ? body : {};
    const next = Object.assign({}, settings, payload);
    if (payload.ai_api_key) next.ai_api_key_set = true;
    next.ai_api_key = settings.ai_api_key;
    settings = next;
    return Object.assign({}, settings);
  }
  if (path === '/settings/test' && m === 'POST') {
    return { ok: true, model: settings.ai_model, latency_ms: 820 };
  }

  if (path === '/export' && m === 'GET') {
    return { version: 1, exported_at: new Date().toISOString(), profile: PROFILE, settings: settings, records: records };
  }
  if (path === '/import' && m === 'POST') {
    const payload = body && typeof body === 'object' ? body : {};
    const incoming = Array.isArray(payload.records) ? payload.records : [];
    let importedRecords = 0;
    for (const item of incoming) {
      if (!item || typeof item !== 'object') continue;
      records.unshift(Object.assign({ id: (nextId += 1), meta: {} }, item));
      importedRecords += 1;
    }
    if (payload.settings) settings = Object.assign({}, settings, payload.settings);
    return { ok: true, imported: { records: importedRecords } };
  }

  throw fail(404, '演示模式未实现该接口：' + path, { error: 'not_found' });
}

export default { isActive, enable, handle, fromUrl, markProbed, QUICK_TYPES };
