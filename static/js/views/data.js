/**
 * views/data.js — the data / history screen (数据).
 *
 * Layout: header + add button, week stats, month calendar, day list,
 * 版块 overview rows with glyph charts and the doctor-report row.
 */

import { el, clear } from '../dom.js';
import { icon } from '../icons.js';
import { api } from '../api.js';
import {
  state, connect, fmtNum, fixedNum, signedNum, clampPct, timeOf, shortDate, relativeDay,
  loadSummary, loadCalendar, loadDayRecords, loadWeightChart, reloadBootstrap,
} from '../store.js';
import {
  card, cardHead, sectionHead, listRow, button, statTile, emptyState, errorState, skeleton,
} from '../components/card.js';
import { sparkline, barGlyph, ringGlyph } from '../components/chart.js';
import { touchLineChart } from '../components/chart-touch.js';
import { chipRow } from '../components/chips.js';
import { monthGrid, shiftMonth, formatMonthTitle, formatDayTitle, toDateKey, addDays, dotColor, dotLabel } from '../components/calendar.js';
import { recordRow, typeMeta } from '../components/list.js';
import { openSheet, openRecordSheet, openTypePicker, confirmDialog } from '../components/sheet.js';
import { toast } from '../components/toast.js';

const SECTIONS = [
  { key: 'body', label: '身体', icon: 'weight', color: '#3DBFA0', hint: '体重 · 体脂 · 腰围' },
  { key: 'food', label: '饮食', icon: 'food', color: '#E8607A', hint: '餐食 · 卡路里 · 蛋白质' },
  { key: 'training', label: '训练', icon: 'training', color: '#3DBFA0', hint: '训练 · 步数' },
  { key: 'health', label: '健康', icon: 'health', color: '#8B7BD8', hint: '副作用 · 化验 · 血压' },
  { key: 'water', label: '饮水', icon: 'water', color: '#4A9BE8', hint: '每日饮水' },
  { key: 'sleep', label: '睡眠', icon: 'sleep', color: '#8B7BD8', hint: '每晚睡眠时长' },
  { key: 'dose', label: '用药', icon: 'dose', color: '#E8A33D', hint: '剂量 · 体内水平估算' },
];

const LEGEND = ['dose', 'weight', 'food', 'water', 'training', 'side_effect'];

/* ------------------------------------------------------------------ *
 * local helpers
 * ------------------------------------------------------------------ */

function weekRange() {
  const week = state.week;
  if (week && typeof week.start === 'string' && typeof week.end === 'string') {
    return { start: week.start, end: week.end, label: week.label || '' };
  }
  const today = state.today || toDateKey(new Date());
  const date = new Date(today + 'T00:00:00');
  const offset = (date.getDay() + 6) % 7;
  const start = addDays(today, -offset);
  return { start: start, end: addDays(start, 6), label: '' };
}

function sumBy(records, type, pick) {
  let total = 0;
  for (const record of records) {
    if (record.type !== type) continue;
    total += Number(pick ? pick(record) : record.value) || 0;
  }
  return total;
}

function daysInRange(start, end) {
  const out = [];
  let cursor = start;
  let guard = 0;
  while (cursor <= end && guard < 400) {
    out.push(cursor);
    cursor = addDays(cursor, 1);
    guard += 1;
  }
  return out;
}

function weightInfo() {
  const chart = state.weight;
  const series = chart && Array.isArray(chart.series) ? chart.series : [];
  const values = series.map((p) => Number(p && (p.v !== undefined ? p.v : p.value))).filter((v) => Number.isFinite(v));
  if (values.length) {
    return {
      values: values,
      first: values[0],
      last: values[values.length - 1],
      goal: Number(chart && chart.goal) || Number(state.targets && state.targets.weight_goal) || null,
    };
  }
  return { values: [], first: null, last: null, goal: Number(state.targets && state.targets.weight_goal) || null };
}

/* ------------------------------------------------------------------ *
 * header
 * ------------------------------------------------------------------ */

function addButton() {
  return button({
    label: '添加记录',
    kind: 'primary',
    small: true,
    icon: 'plus',
    onClick: () => {
      const types = state.quickTypes && state.quickTypes.length
        ? state.quickTypes
        : [{ type: 'weight', label: '体重' }, { type: 'food', label: '餐食' }, { type: 'water', label: '饮水' }];
      openTypePicker({
        types: types,
        onPick: (item) => openRecordSheet({
          type: item.type,
          title: '添加' + (item.label || '记录'),
          onSubmit: (payload) => api.createRecord(payload).then(() => {
            toast('已记录', 'success');
            refreshAll();
          }),
        }),
      });
    },
  });
}

function header() {
  return el(
    'header',
    { class: 'page-head' },
    el('h1', { class: 'page-title', text: '数据' }),
    addButton()
  );
}

/* ------------------------------------------------------------------ *
 * week stats
 * ------------------------------------------------------------------ */

function weekHead(range) {
  return el(
    'div',
    { class: 'week-head' },
    el('div', { class: 'week-head-main' },
      el('h2', { class: 'week-title', text: '本周' }),
      el('span', { class: 'week-range', text: fmtWeekRange(range) })),
    el('button', {
      class: 'week-compare',
      type: 'button',
      text: '对比上周',
      onClick: () => toast('上周对比需要更多历史数据', 'info'),
    })
  );
}

function fmtWeekRange(range) {
  const start = new Date(range.start + 'T00:00:00');
  const end = new Date(range.end + 'T00:00:00');
  if (!Number.isFinite(start.getTime()) || !Number.isFinite(end.getTime())) return '';
  if (range.label) return range.label.replace(/^本周\s*·\s*/, '');
  return (start.getMonth() + 1) + '月' + start.getDate() + '日 – ' + (end.getMonth() + 1) + '月' + end.getDate() + '日';
}

function tile(label, value, unit, hint, tone) {
  return el(
    'div',
    { class: 'tile' },
    el('span', { class: 'tile-label', text: label }),
    el(
      'span',
      { class: 'tile-value' + (tone ? ' tone-text-' + tone : '') },
      el('span', { class: 'tile-number', text: value === null || value === undefined ? '—' : String(value) }),
      unit ? el('span', { class: 'tile-unit', text: unit }) : null
    ),
    el('span', { class: 'tile-hint', text: hint || '' })
  );
}

function proteinPerDay() {
  const week = state.week || {};
  const summary = state.summary || {};
  const averages = summary.averages || {};
  const candidates = [
    week.protein_per_day,
    averages.protein_per_day,
    summary.protein_per_day,
    summary.protein && (summary.protein.v !== undefined ? summary.protein.v : summary.protein),
  ];
  for (const value of candidates) {
    const n = Number(value);
    if (Number.isFinite(n)) return n;
  }
  return 0;
}

function proteinTarget() {
  const targets = (state.summary && state.summary.targets) || state.targets || {};
  return Number(targets.protein_g !== undefined ? targets.protein_g : targets.protein) || 90;
}

function weekTiles(weekRecords) {
  const weight = weightInfo();
  const week = state.week || {};
  const doses = week.doses && typeof week.doses === 'object'
    ? week.doses
    : { taken: weekRecords.filter((r) => r.type === 'dose').length, planned: 1 };
  const weekIndex = Number(week.week_index) || Math.max(1, Math.ceil((state.dayIndex || 1) / 7));
  const weekWeight = week.weight || {};

  const startValue = Number.isFinite(Number(weekWeight.first)) ? Number(weekWeight.first) : weight.first;
  const weightHint = weight.last === null
    ? '还没有体重记录'
    : startValue !== null && Number(weight.values.length) > 1
      ? signedNum(weight.last - startValue, 1) + ' kg 本周'
      : '首次记录';

  const taken = Number(doses.taken) || 0;
  const planned = Number(doses.planned) || 1;

  return el(
    'div',
    { class: 'tiles' },
    tile('体重', weight.last === null ? '—' : fixedNum(weight.last, 1), 'kg', weightHint, 'green'),
    tile('蛋白质', fmtNum(proteinPerDay()), 'g/天', '目标 ' + fmtNum(proteinTarget()) + ' g'),
    tile('剂量', taken + '/' + planned, '', '✓ 第 ' + weekIndex + ' 周', 'amber')
  );
}

function legendRow() {
  return el(
    'div',
    { class: 'legend-row' },
    LEGEND.map((kind) =>
      el(
        'span',
        { class: 'legend-item' },
        el('span', { class: 'legend-dot', style: { background: dotColor(kind) } }),
        el('span', { text: dotLabel(kind) })
      )
    )
  );
}

/* ------------------------------------------------------------------ *
 * calendar card
 * ------------------------------------------------------------------ */

function calendarCard() {
  const month = state.calendarMonth || (state.today || toDateKey(new Date())).slice(0, 7);
  const days = state.calendarDays || {};

  const head = el(
    'div',
    { class: 'cal-head' },
    el('button', {
      class: 'cal-nav',
      type: 'button',
      'aria-label': '上一个月',
      onClick: () => loadCalendar(shiftMonth(month, -1)),
    }, icon('back', { size: 18 })),
    el('span', { class: 'cal-month', text: formatMonthTitle(month) }),
    el('button', {
      class: 'cal-nav',
      type: 'button',
      'aria-label': '下一个月',
      onClick: () => loadCalendar(shiftMonth(month, 1)),
    }, icon('chevron', { size: 18 }))
  );

  const grid = monthGrid({
    month: month,
    days: days,
    selected: state.selectedDate,
    todayKey: state.today,
    maxDots: 3,
    onSelect: (key) => {
      loadDayRecords(key);
    },
  });

  return card({ class: 'card--cal' }, head, grid);
}

/* ------------------------------------------------------------------ *
 * day list
 * ------------------------------------------------------------------ */

function dayList() {
  const key = state.selectedDate || state.today;
  const rows = Array.isArray(state.dayRecords) ? state.dayRecords : [];
  const body = rows.length
    ? el(
        'div',
        { class: 'list' },
        rows.map((record) =>
          recordRow(record, {
            chevron: true,
            onSelect: (item) => openRecordSheet({
              record: item,
              onSubmit: (payload) => api.updateRecord(item.id, payload).then(() => {
                toast('已更新', 'success');
                refreshAll();
              }),
              onDelete: (target) => api.deleteRecord(target.id).then(() => {
                toast('已删除', 'success');
                refreshAll();
              }),
            }),
          })
        )
      )
    : emptyState({
        icon: 'calendar',
        title: '这一天还没有记录',
        hint: '可以在日历上换一天，或点右上角添加',
      });

  return card(
    { class: 'card--day' },
    cardHead({
      title: formatDayTitle(key),
      subtitle: rows.length + ' 条记录 · 点击编辑',
    }),
    body
  );
}

/* ------------------------------------------------------------------ *
 * 版块
 * ------------------------------------------------------------------ */

function sectionGlyph(key, weekRecords, range) {
  const days = daysInRange(range.start, range.end);
  if (key === 'body') {
    const values = weightInfo().values.slice(-7);
    return values.length > 1
      ? sparkline({ values: values, color: '#3DBFA0' })
      : barGlyph({ values: values.length ? values.map((v) => v - 100) : [0], color: '#3DBFA0' });
  }

  const summaryPerDay = (state.summary && state.summary.per_day) || null;
  const perDay = days.map((day) => {
    const todays = weekRecords.filter((r) => String(r.at || '').slice(0, 10) === day);
    if (key === 'food') {
      if (summaryPerDay && summaryPerDay[day] !== undefined) return Number(summaryPerDay[day]) || 0;
      return sumBy(todays, 'food');
    }
    if (key === 'water') return sumBy(todays, 'water') / 1000;
    if (key === 'training') return sumBy(todays, 'training');
    if (key === 'health') return todays.filter((r) => r.type === 'symptom' || r.type === 'side_effect').length;
    return todays.length;
  });

  if (key === 'water') {
    const total = perDay.reduce((a, b) => a + b, 0);
    const target = Number(state.targets.water) || 5;
    return ringGlyph({ pct: clampPct(total, target * days.length || target), color: '#4A9BE8' });
  }
  return barGlyph({ values: perDay, color: SECTIONS.find((s) => s.key === key).color });
}

function sectionsCard(weekRecords, range, onChanged) {
  const rows = SECTIONS.map((section) => {
    return listRow({
      icon: section.icon,
      toneColor: section.color,
      title: section.label,
      subtitle: section.hint,
      chevron: true,
      trailing: sectionGlyph(section.key, weekRecords, range),
      onClick: () => openSectionSheet(section, onChanged),
      class: 'row--section',
    });
  });

  return el('section', { class: 'section' }, sectionHead('版块'), card({ flush: true }, el('div', { class: 'list' }, rows)));
}

function reportRow() {
  return card(
    { class: 'card--report' },
    listRow({
      icon: 'file-text',
      toneColor: '#35C6E8',
      title: '医生报告',
      subtitle: '把这段时间的记录整理成一页摘要',
      chevron: true,
      onClick: () => toast('医生报告导出还没实现', 'info'),
    })
  );
}

/* ------------------------------------------------------------------ *
 * 版块详情页（sheet）
 * ------------------------------------------------------------------ */

const RANGE_OPTIONS = [
  { label: '7天', days: 7 },
  { label: '30天', days: 30 },
  { label: '90天', days: 90 },
];

const DEFAULT_RANGE_DAYS = 30;
const MAX_BARS = 30;
const CHART_HINT = '按住图表左右拖动可读出每个点的数值';

/** 指标 → 中文名 / 单位 / 小数位 / 图表颜色。 */
const METRICS = {
  weight: { label: '体重', unit: 'kg', digits: 1, color: '#3DBFA0' },
  body_fat: { label: '体脂率', unit: '%', digits: 1, color: '#8B7BD8' },
  waist: { label: '腰围', unit: 'cm', digits: 1, color: '#35C6E8' },
  muscle: { label: '肌肉量', unit: 'kg', digits: 1, color: '#3DBFA0' },
  food: { label: '餐食热量', unit: 'kcal', digits: 0, color: '#E8607A' },
  water: { label: '饮水量', unit: 'ml', digits: 0, color: '#4A9BE8' },
  training: { label: '训练时长', unit: '分钟', digits: 0, color: '#3DBFA0' },
  sleep: { label: '睡眠时长', unit: '小时', digits: 1, color: '#8B7BD8' },
  dose: { label: '剂量', unit: 'mg', digits: 2, color: '#E8A33D' },
  side_effect: { label: '副作用严重度', unit: '级', digits: 0, color: '#E8607A' },
  symptom: { label: '症状严重度', unit: '级', digits: 0, color: '#8B7BD8' },
  vital: { label: '体征', unit: '', digits: 1, color: '#4A9BE8' },
  measure: { label: '测量值', unit: '', digits: 1, color: '#35C6E8' },
  bp: { label: '血压', unit: 'mmHg', digits: 0, color: '#E8607A' },
  lab: { label: '化验值', unit: '', digits: 2, color: '#4A9BE8' },
};

/** 每个版块详情页拉取的记录类型、图表来源和统计口径。 */
const DETAILS = {
  body: {
    title: '身体',
    subtitle: '体重 · 体脂 · 腰围',
    icon: 'weight',
    types: ['weight', 'body_fat', 'waist', 'muscle'],
    metrics: ['weight', 'body_fat', 'waist', 'muscle'],
    chartSource: 'weight',
    statLead: 'latest',
    emptyTitle: '还没有身体记录',
    emptyHint: '称一次体重、量一次腰围都会出现在这里',
  },
  food: {
    title: '饮食',
    subtitle: '餐食 · 卡路里 · 蛋白质',
    icon: 'food',
    types: ['food'],
    metrics: ['food'],
    chartSource: 'days',
    statLead: 'count',
    summary: true,
    emptyTitle: '还没有饮食记录',
    emptyHint: '记一餐就会出现在这里，热量和蛋白质都会统计',
  },
  training: {
    title: '训练',
    subtitle: '训练 · 步数',
    icon: 'training',
    types: ['training', 'exercise'],
    metrics: ['training'],
    chartSource: 'bars',
    dayMetric: 'training',
    statLead: 'count',
    emptyTitle: '还没有训练记录',
    emptyHint: '走一段路、做一组训练都可以记下来',
  },
  health: {
    title: '健康',
    subtitle: '副作用 · 化验 · 血压',
    icon: 'health',
    types: ['side_effect', 'symptom', 'vital', 'measure', 'bp', 'blood_pressure'],
    metrics: ['side_effect', 'symptom'],
    chartSource: 'severity',
    statLead: 'count',
    emptyTitle: '还没有健康记录',
    emptyHint: '副作用、血压、化验结果都可以记下来',
  },
  water: {
    title: '饮水',
    subtitle: '每日饮水',
    icon: 'water',
    types: ['water'],
    metrics: ['water'],
    chartSource: 'bars',
    dayMetric: 'water',
    statLead: 'count',
    dayAverage: true,
    emptyTitle: '还没有饮水记录',
    emptyHint: '喝一杯水就记一次，按天汇总成柱状图',
  },
  sleep: {
    title: '睡眠',
    subtitle: '每晚睡眠时长',
    icon: 'sleep',
    types: ['sleep'],
    metrics: ['sleep'],
    chartSource: 'points',
    statLead: 'latest',
    emptyTitle: '还没有睡眠记录',
    emptyHint: '记下昨晚睡了多久',
  },
  dose: {
    title: '用药',
    subtitle: '剂量记录 · 体内水平估算',
    icon: 'dose',
    types: ['dose'],
    metrics: ['dose'],
    chartSource: 'level',
    statLead: 'latest',
    emptyTitle: '还没有用药记录',
    emptyHint: '记录一次注射就会出现在这里',
  },
};

const SEVERITY_BUCKETS = [
  { label: '1–2 级', color: '#3DBFA0' },
  { label: '3–4 级', color: '#E8A33D' },
  { label: '5–6 级', color: '#E8607A' },
  { label: '7–8 级', color: '#E8607A' },
  { label: '9–10 级', color: '#E8607A' },
];

function metricOf(key) {
  return METRICS[key] || { label: String(key || '记录'), unit: '', digits: 0, color: '#35C6E8' };
}

/** 数值 → '144.0'（不含单位） */
function metricNumber(key, value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return '—';
  const meta = metricOf(key);
  if (meta.digits > 0) return fixedNum(n, meta.digits);
  return fmtNum(Math.round(n));
}

/** 数值 → '144.0 kg'（始终带单位） */
function metricText(key, value) {
  const number = metricNumber(key, value);
  if (number === '—') return number;
  const unit = metricOf(key).unit;
  return unit ? number + ' ' + unit : number;
}

/** 记录右侧的数值文案(优先用记录自己的单位)。 */
function detailValueText(record) {
  const r = record || {};
  const n = Number(r.value);
  if (!Number.isFinite(n)) return r.subtitle ? String(r.subtitle) : '—';
  if (r.type === 'side_effect' || r.type === 'symptom') return fmtNum(n) + '/10';
  if (r.type === 'training' || r.type === 'exercise') return fmtNum(n) + ' 分钟';
  if (r.type === 'steps') return fmtNum(n) + ' 步';
  const meta = METRICS[r.type];
  if (meta) return metricText(r.type, n);
  return fmtNum(n) + (r.unit ? ' ' + r.unit : '');
}

/** 记录行的副标题（吃了什么 / 注射部位 / 备注）。 */
function rowSubtitle(record) {
  const r = record || {};
  const meta = r.meta || {};
  const parts = [];
  if (r.type === 'food') {
    if (meta.desc) parts.push(String(meta.desc));
    if (Number(meta.protein_g)) parts.push('蛋白 ' + fmtNum(meta.protein_g) + ' g');
  } else if (r.type === 'dose') {
    if (meta.site) parts.push(String(meta.site));
    if (meta.skipped) parts.push('已跳过');
  } else if (meta.desc) {
    parts.push(String(meta.desc));
  } else if (meta.label && meta.label !== r.title) {
    parts.push(String(meta.label));
  } else if (meta.note) {
    parts.push(String(meta.note));
  }
  const text = parts.filter(Boolean).join(' · ');
  return text || (r.subtitle ? String(r.subtitle) : '');
}

/** 行内时间：'今天 11:45' / '10月9日 08:30' */
function rowTime(record) {
  const r = record || {};
  const day = String(r.date || (r.at || '').slice(0, 10));
  const label = relativeDay(day) || (r.at ? shortDate(r.at) : day);
  const time = timeOf(r.at) || r.time || '';
  return [label, time].filter(Boolean).join(' ');
}

function detailToday() {
  return state.today || toDateKey(new Date());
}

/** 近 N 天的日期区间（含今天）。 */
function detailRange(days) {
  const span = Math.max(1, Math.round(Number(days) || DEFAULT_RANGE_DAYS));
  const end = detailToday();
  return { start: addDays(end, -(span - 1)), end: end, days: span };
}

function rangeLabel(days) {
  const found = RANGE_OPTIONS.find((option) => option.days === Number(days));
  return found ? found.label : DEFAULT_RANGE_DAYS + '天';
}

function shortRangeText(days) {
  const range = detailRange(days);
  return '近 ' + range.days + ' 天 · '
    + shortDate(range.start + 'T00:00:00') + '–' + shortDate(range.end + 'T00:00:00');
}

function chartDateLabel(date) {
  const d = date instanceof Date ? date : new Date(date);
  if (!Number.isFinite(d.getTime())) return '';
  return (d.getMonth() + 1) + '月' + d.getDate() + '日';
}

function summarizeValues(values) {
  const clean = (Array.isArray(values) ? values : [])
    .map((value) => Number(value))
    .filter((value) => Number.isFinite(value));
  if (!clean.length) return null;
  let total = 0;
  let max = clean[0];
  let min = clean[0];
  for (const value of clean) {
    total += value;
    if (value > max) max = value;
    if (value < min) min = value;
  }
  return { count: clean.length, total: total, avg: total / clean.length, max: max, min: min };
}

function hasValues(items, key) {
  return (Array.isArray(items) ? items : []).some(
    (record) => record && record.type === key && Number.isFinite(Number(record.value))
  );
}

/** 实际拿来统计的指标：优先版块主指标，没有数值时退到该版块的其它类型。 */
function pickMetric(detail, items) {
  for (const key of detail.metrics) {
    if (hasValues(items, key)) return key;
  }
  for (const key of detail.types) {
    if (hasValues(items, key)) return key;
  }
  return detail.metrics[0];
}

/** 记录 → 按时间升序的折线点。 */
function localSeries(items, key) {
  return (Array.isArray(items) ? items : [])
    .filter((record) => record && record.type === key && record.at && Number.isFinite(Number(record.value)))
    .map((record) => ({ t: record.at, v: Number(record.value) }))
    .sort((a, b) => new Date(a.t) - new Date(b.t));
}

/** 同一种记录按天求和（返回按日期升序）。 */
function dayTotals(items, key) {
  const map = new Map();
  for (const record of Array.isArray(items) ? items : []) {
    if (!record || record.type !== key) continue;
    const value = Number(record.value);
    const day = String(record.date || (record.at || '').slice(0, 10));
    if (!Number.isFinite(value) || !day) continue;
    map.set(day, (map.get(day) || 0) + value);
  }
  return Array.from(map.keys())
    .sort()
    .map((day) => ({ day: day, total: map.get(day) }));
}

function dayLabel(day) {
  const parts = String(day || '').split('-');
  if (parts.length < 3) return String(day || '');
  return Number(parts[1]) + '/' + Number(parts[2]);
}

/** 图表接口的 series → 触摸图表要求的 {t, v}。 */
function seriesOf(raw, key) {
  return (Array.isArray(raw) ? raw : [])
    .map((point) => {
      const value = point && (point[key] !== undefined ? point[key] : point.v);
      return { t: point && (point.t || point.at), v: Number(value) };
    })
    .filter((point) => point.t && Number.isFinite(point.v));
}

function severityBuckets(items) {
  const buckets = SEVERITY_BUCKETS.map((bucket) => ({ label: bucket.label, color: bucket.color, count: 0 }));
  for (const record of Array.isArray(items) ? items : []) {
    if (!record || (record.type !== 'side_effect' && record.type !== 'symptom')) continue;
    let severity = Number(record.value);
    if (!Number.isFinite(severity)) continue;
    if (severity > 10) severity = severity / 2;
    severity = Math.max(1, Math.min(10, severity));
    const index = Math.min(buckets.length - 1, Math.floor((severity - 1) / 2));
    buckets[index].count += 1;
  }
  return buckets;
}

function waterTarget() {
  const targets = state.targets || {};
  const ml = Number(targets.water_ml);
  if (Number.isFinite(ml) && ml > 0) return ml;
  const liters = Number(targets.water);
  if (Number.isFinite(liters) && liters > 0) return liters * 1000;
  return 2000;
}

function weightGoal() {
  const chart = state.weight || {};
  const fromChart = Number(chart.goal);
  if (Number.isFinite(fromChart) && fromChart > 0) return fromChart;
  const fromTargets = Number(state.targets && state.targets.weight_goal);
  if (Number.isFinite(fromTargets) && fromTargets > 0) return fromTargets;
  const fromProfile = Number(state.profile && state.profile.target_weight_kg);
  if (Number.isFinite(fromProfile) && fromProfile > 0) return fromProfile;
  return null;
}

function kvList(rows) {
  const list = (Array.isArray(rows) ? rows : []).filter(
    (row) => row && row.value !== undefined && row.value !== null && row.value !== ''
  );
  if (!list.length) return null;
  return el(
    'div',
    { class: 'kv-list' },
    list.map((row) =>
      el(
        'div',
        { class: 'kv-row' },
        el('span', { class: 'kv-key', text: row.key }),
        el('span', { class: 'kv-value', text: String(row.value) })
      )
    )
  );
}

/** 横向条形汇总（饮水 / 训练 / 严重度分布）。 */
function barRows(entries, footnote) {
  const all = Array.isArray(entries) ? entries : [];
  const rows = all.slice(-MAX_BARS);
  const wrap = el(
    'div',
    { class: 'detail-bars' },
    rows.map((entry) =>
      el(
        'div',
        { class: 'detail-bar-row' + (entry.wide ? ' detail-bar-row--wide' : '') },
        el('span', { class: 'detail-bar-label', text: entry.label }),
        el(
          'span',
          { class: 'detail-bar-track' },
          el('span', {
            class: 'detail-bar-fill',
            style: {
              width: Math.max(2, Math.round(Math.max(0, Math.min(1, entry.ratio || 0)) * 100)) + '%',
              background: entry.color,
            },
          })
        ),
        el('span', { class: 'detail-bar-value', text: entry.text })
      )
    )
  );
  const notes = [];
  if (all.length > rows.length) notes.push('只显示最近 ' + rows.length + ' 天，共 ' + all.length + ' 天有记录。');
  if (footnote) notes.push(footnote);
  if (notes.length) wrap.appendChild(el('p', { class: 'hint-line', text: notes.join(' ') }));
  return wrap;
}

/** 顶部四块统计：记录数（或最新）/ 平均 / 最高 / 最低。 */
function detailTiles(detail, items, metricKey, days) {
  const metric = metricOf(metricKey);
  const rows = (Array.isArray(items) ? items : []).filter((record) => record && record.type === metricKey);
  const stats = summarizeValues(rows.map((record) => record.value));
  const tiles = [];
  let maxRow = null;
  let minRow = null;
  for (const record of rows) {
    const value = Number(record.value);
    if (!Number.isFinite(value)) continue;
    if (!maxRow || value > Number(maxRow.value)) maxRow = record;
    if (!minRow || value < Number(minRow.value)) minRow = record;
  }

  if (detail.statLead === 'latest') {
    const latest = rows.length ? rows[0] : null;
    tiles.push(statTile({
      label: '最新',
      value: latest ? metricNumber(metricKey, latest.value) : '—',
      unit: metric.unit,
      hint: latest ? rowTime(latest) : '还没有记录',
    }));
  } else {
    tiles.push(statTile({
      label: '记录数',
      value: stats ? stats.count : 0,
      unit: '条',
      hint: '近 ' + days + ' 天',
    }));
  }
  tiles.push(statTile({
    label: '平均',
    value: stats ? metricNumber(metricKey, stats.avg) : '—',
    unit: metric.unit,
    hint: detail.dayAverage ? '每次记录' : '每一条',
  }));
  tiles.push(statTile({
    label: '最高',
    value: stats ? metricNumber(metricKey, stats.max) : '—',
    unit: metric.unit,
    hint: maxRow ? rowTime(maxRow) : '',
  }));
  tiles.push(statTile({
    label: '最低',
    value: stats ? metricNumber(metricKey, stats.min) : '—',
    unit: metric.unit,
    hint: minRow ? rowTime(minRow) : '',
  }));
  return tiles;
}

function errorText(err) {
  if (err && err.message) return String(err.message);
  return '加载失败，请检查网络后重试';
}

/** 不让单个请求失败拖垮整页：统一收敛成 {ok, value|error}。 */
function settle(promise) {
  return Promise.resolve(promise).then(
    (value) => ({ ok: true, value: value }),
    (error) => ({ ok: false, error: error })
  );
}

/**
 * 打开一个版块的详情页。
 * @param {{key:string,label:string,icon:string,color:string,hint:string}} section
 * @param {Function} [onChanged] 记录被删除后通知外面的列表刷新
 */
function openSectionSheet(section, onChanged) {
  const detail = DETAILS[section.key] || DETAILS.body;
  const ui = {
    days: DEFAULT_RANGE_DAYS,
    metric: detail.metrics[0],
    items: [],
    records: null,
    chart: null,
    summary: null,
    loading: true,
    actionError: '',
    seq: 0,
  };
  let chartView = null;
  let closed = false;

  const rangeHost = el('div', { class: 'detail-range' });
  const statsHost = el('div', { class: 'detail-stats' });
  const chartHead = el('div', { class: 'detail-block-head' });
  const chartHost = el('div', { class: 'detail-chart' });
  const noteHost = el('div', { class: 'detail-note' });
  const listHead = el('div', { class: 'detail-block-head' });
  const listHost = el('div', { class: 'detail-list' });

  const sheet = openSheet({
    title: detail.title + ' · 详情',
    subtitle: detail.subtitle,
    body: [rangeHost, statsHost, chartHead, chartHost, noteHost, listHead, listHost],
    onClose: () => {
      closed = true;
      ui.seq += 1;
      disposeChart();
    },
  });

  /* ---------------- 图表生命周期 ---------------- */

  function disposeChart() {
    if (chartView && typeof chartView.destroy === 'function') {
      try {
        chartView.destroy();
      } catch (err) {
        /* 组件内部已经清理过，这里忽略重复销毁 */
      }
    }
    chartView = null;
  }

  function mountChart(options) {
    disposeChart();
    chartView = touchLineChart(options);
    chartHost.appendChild(chartView.node);
  }

  /* ---------------- 数据 ---------------- */

  function load() {
    const seq = (ui.seq += 1);
    ui.loading = true;
    ui.actionError = '';
    paint();
    sheet.setError('');

    const bounds = detailRange(ui.days);
    const wantsChart = detail.chartSource === 'weight' || detail.chartSource === 'level';
    const jobs = [
      settle(api.records({
        types: detail.types.join(','),
        start: bounds.start,
        end: bounds.end,
        limit: 500,
        order: 'desc',
      }, { silent: true })),
    ];
    if (wantsChart) {
      jobs.push(settle(detail.chartSource === 'weight'
        ? api.weightChart(ui.days, { silent: true })
        : api.levelChart(90, { silent: true })));
    }
    if (detail.summary) {
      jobs.push(settle(api.summary(ui.days <= 7 ? 'week' : 'month', { silent: true })));
    }

    Promise.all(jobs).then((results) => {
      if (closed || seq !== ui.seq) return;
      ui.records = results[0];
      let index = 1;
      if (wantsChart) {
        ui.chart = results[index];
        index += 1;
      }
      if (detail.summary) {
        ui.summary = results[index];
        index += 1;
      }
      ui.items = ui.records.ok && ui.records.value && Array.isArray(ui.records.value.items)
        ? ui.records.value.items
        : [];
      ui.metric = pickMetric(detail, ui.items);
      ui.loading = false;
      paint();
    });
  }

  /* ---------------- 渲染 ---------------- */

  function paint() {
    if (closed) return;
    paintRange();
    paintStats();
    paintChart();
    paintNote();
    paintList();
  }

  function paintRange() {
    clear(rangeHost);
    rangeHost.appendChild(chipRow(RANGE_OPTIONS.map((option) => option.label), {
      active: rangeLabel(ui.days),
      onSelect: (label) => {
        const option = RANGE_OPTIONS.find((item) => item.label === label);
        if (!option || option.days === ui.days) return;
        ui.days = option.days;
        load();
      },
    }));
    rangeHost.appendChild(el('p', { class: 'hint-line', text: shortRangeText(ui.days) }));
  }

  function paintStats() {
    clear(statsHost);
    if (!ui.records) {
      statsHost.appendChild(skeleton(1));
      return;
    }
    if (!ui.records.ok) {
      statsHost.appendChild(errorState({
        title: '统计数据加载失败',
        message: errorText(ui.records.error),
        onRetry: load,
      }));
      return;
    }
    statsHost.appendChild(el('div', { class: 'tiles tiles--4' }, detailTiles(detail, ui.items, ui.metric, ui.days)));
    if (detail.metrics.length > 1) {
      statsHost.appendChild(el('p', {
        class: 'hint-line',
        text: '统计指标：' + metricOf(ui.metric).label + ' · ' + shortRangeText(ui.days),
      }));
    }
    if (ui.loading) statsHost.appendChild(el('p', { class: 'hint-line', text: '正在加载…' }));
  }

  function blockHead(title, sub) {
    clear(chartHead);
    chartHead.appendChild(el('h3', { class: 'detail-block-title', text: title }));
    if (sub) chartHead.appendChild(el('span', { class: 'detail-block-sub', text: sub }));
  }

  function chartEmpty(title, hint) {
    chartHost.appendChild(emptyState({
      icon: detail.icon,
      title: title || detail.emptyTitle,
      hint: hint || detail.emptyHint,
    }));
  }

  /** 条形图实际汇总的类型：主类型没有数据时退到版块里的其它类型。 */
  function activeDayMetric() {
    if (hasValues(ui.items, detail.dayMetric)) return detail.dayMetric;
    for (const key of detail.types) {
      if (hasValues(ui.items, key)) return key;
    }
    return detail.dayMetric;
  }

  function paintBars() {
    const key = activeDayMetric();
    const water = key === 'water';
    blockHead(water ? '每日饮水' : '每日训练', shortRangeText(ui.days));
    const entries = dayTotals(ui.items, key);
    if (!entries.length) {
      chartEmpty();
      return;
    }
    const target = water ? waterTarget() : 0;
    const max = target > 0 ? target : entries.reduce((m, entry) => Math.max(m, entry.total), 0) || 1;
    chartHost.appendChild(barRows(
      entries.map((entry) => ({
        label: dayLabel(entry.day),
        text: metricText(key, entry.total) + (target > 0 ? ' / ' + fmtNum(target) : ''),
        ratio: max > 0 ? entry.total / max : 0,
        color: metricOf(key).color,
      })),
      target > 0 ? '柱长按每天 ' + fmtNum(target) + ' ' + metricOf(key).unit + ' 的目标缩放。' : ''
    ));
  }

  function paintSeverity() {
    blockHead('严重程度分布', shortRangeText(ui.days));
    const buckets = severityBuckets(ui.items);
    const total = buckets.reduce((sum, bucket) => sum + bucket.count, 0);
    if (!total) {
      chartEmpty('还没有副作用记录', '记录恶心、便秘等感受后，会按严重程度（1–10 级）分组显示');
      return;
    }
    const max = buckets.reduce((m, bucket) => Math.max(m, bucket.count), 0) || 1;
    chartHost.appendChild(barRows(buckets.map((bucket) => ({
      label: bucket.label,
      text: bucket.count + ' 次',
      ratio: bucket.count / max,
      color: bucket.color,
      wide: true,
    }))));
    chartHost.appendChild(el('p', {
      class: 'hint-line',
      text: '按记录时的严重程度（1–10 级）分组，共 ' + total + ' 条。',
    }));
  }

  function paintChart() {
    disposeChart();
    clear(chartHead);
    clear(chartHost);

    if (!ui.records) {
      chartHost.appendChild(skeleton(3));
      return;
    }

    // 记录本身加载失败时，上面的统计块已经给出错误和重试入口，这里不再重复。
    if (!ui.records.ok) return;

    if (detail.chartSource === 'bars') {
      paintBars();
      return;
    }

    if (detail.chartSource === 'severity') {
      paintSeverity();
      return;
    }

    if (detail.chartSource === 'weight') {
      blockHead('体重趋势', shortRangeText(ui.days));
      if (ui.chart && !ui.chart.ok) {
        chartHost.appendChild(errorState({
          title: '趋势图加载失败',
          message: errorText(ui.chart.error),
          onRetry: load,
        }));
        return;
      }
      if (ui.metric === 'weight') {
        const payload = (ui.chart && ui.chart.value) || {};
        const series = seriesOf(payload.series, 'v');
        if (!series.length) {
          chartEmpty();
          return;
        }
        const goal = Number(payload.goal);
        mountChart({
          series: series,
          height: 240,
          color: '#3DBFA0',
          valueFormat: (v) => fixedNum(v, 1) + ' kg',
          labelFormat: chartDateLabel,
          goal: Number.isFinite(goal) && goal > 0 ? goal : null,
          baseline: null,
          hint: CHART_HINT,
        });
        return;
      }
      const local = localSeries(ui.items, ui.metric);
      if (!local.length) {
        chartEmpty();
        return;
      }
      mountChart({
        series: local,
        height: 240,
        color: metricOf(ui.metric).color,
        valueFormat: (v) => metricText(ui.metric, v),
        labelFormat: chartDateLabel,
        hint: CHART_HINT,
      });
      return;
    }

    if (detail.chartSource === 'level') {
      blockHead('体内水平估算', '基于群体药代动力学');
      if (ui.chart && !ui.chart.ok) {
        chartHost.appendChild(errorState({
          title: '估算曲线加载失败',
          message: errorText(ui.chart.error),
          onRetry: load,
        }));
        return;
      }
      const level = (ui.chart && ui.chart.value) || {};
      const series = seriesOf(level.series, 'mg');
      if (!series.length) {
        chartEmpty();
        return;
      }
      mountChart({
        series: series,
        height: 240,
        color: '#E8A33D',
        valueFormat: (v) => v.toFixed(2) + ' mg',
        labelFormat: chartDateLabel,
        hint: '按住图表左右拖动可读出每个点的估算水平',
      });
      return;
    }

    // 'days'（饮食按天）/ 'points'（睡眠每次）
    const key = detail.metrics[0];
    const isDaily = detail.chartSource === 'days';
    blockHead(isDaily ? '每日热量' : '每晚睡眠', shortRangeText(ui.days));
    const series = isDaily
      ? dayTotals(ui.items, key).map((entry) => ({ t: entry.day + 'T12:00:00', v: entry.total }))
      : localSeries(ui.items, key);
    if (!series.length) {
      chartEmpty();
      return;
    }
    mountChart({
      series: series,
      height: 240,
      color: metricOf(key).color,
      valueFormat: (v) => metricText(key, v),
      labelFormat: chartDateLabel,
      hint: CHART_HINT,
    });
    if (isDaily) {
      chartHost.appendChild(el('p', {
        class: 'hint-line',
        text: '只画有记录的日子，共 ' + series.length + ' 天。',
      }));
    }
  }

  function noteRows() {
    if (detail.chartSource === 'level') {
      const level = (ui.chart && ui.chart.ok && ui.chart.value) || {};
      const baseline = Number(level.baseline_mg);
      const max = Number(level.max_mg);
      const current = Number(level.current_mg);
      const halfLife = Number(level.half_life_hours);
      return [
        { key: '当前谷值（估算）', value: Number.isFinite(baseline) ? fmtNum(baseline, 2) + ' mg' : '—' },
        { key: '稳态上限（估算）', value: Number.isFinite(max) ? fmtNum(max, 2) + ' mg' : '—' },
        { key: '当前水平（估算）', value: Number.isFinite(current) ? fmtNum(current, 2) + ' mg' : '—' },
        { key: '趋势', value: level.trend ? String(level.trend) : '—' },
        { key: '半衰期', value: Number.isFinite(halfLife) ? fmtNum(halfLife / 24, 1) + ' 天' : '—' },
      ];
    }

    if (detail.summary && ui.summary && ui.summary.ok) {
      const payload = ui.summary.value || {};
      const totals = payload.totals || {};
      const averages = payload.averages || {};
      const targets = payload.targets || {};
      const label = payload.period === 'week' ? '本周' : '本月';
      return [
        { key: label + '合计', value: fmtNum(totals.kcal) + ' kcal' },
        { key: label + '蛋白质', value: fmtNum(totals.protein_g) + ' g' },
        {
          key: '日均热量',
          value: fmtNum(averages.kcal_per_day) + ' kcal'
            + (targets.calories ? ' / 目标 ' + fmtNum(targets.calories) : ''),
        },
        {
          key: '日均蛋白',
          value: fmtNum(averages.protein_per_day) + ' g'
            + (targets.protein_g ? ' / 目标 ' + fmtNum(targets.protein_g) : ''),
        },
        {
          key: '碳水 / 脂肪 / 纤维',
          value: fmtNum(totals.carb_g) + ' / ' + fmtNum(totals.fat_g) + ' / ' + fmtNum(totals.fiber_g) + ' g',
        },
      ];
    }

    const key = ui.metric;
    const unit = metricOf(key).unit;
    const points = localSeries(ui.items, key);
    const stats = summarizeValues(points.map((point) => point.v));

    if (section.key === 'body') {
      if (!points.length) return [];
      const first = points[0];
      const last = points[points.length - 1];
      const rows = [
        { key: '起始', value: metricText(key, first.v) + ' · ' + chartDateLabel(first.t) },
        { key: '现在', value: metricText(key, last.v) + ' · ' + chartDateLabel(last.t) },
        {
          key: '变化',
          value: points.length > 1
            ? signedNum(last.v - first.v, 1) + (unit ? ' ' + unit : '') + '（共 ' + points.length + ' 次）'
            : '只有一次记录',
        },
      ];
      if (key === 'weight') {
        const goal = weightGoal();
        rows.push({ key: '目标', value: Number.isFinite(goal) && goal ? fmtNum(goal) + ' kg' : '还没有设置目标' });
      }
      return rows;
    }

    const entries = dayTotals(ui.items, key);
    const rows = [];
    if (entries.length) {
      const total = entries.reduce((sum, entry) => sum + entry.total, 0);
      rows.push({ key: '合计', value: metricText(key, total) });
      rows.push({ key: '日均', value: metricText(key, total / entries.length) + ' / 天' });
      rows.push({ key: '有记录', value: entries.length + ' 天' });
    }
    if (stats) rows.push({ key: '记录数', value: stats.count + ' 条' });
    if (key === 'water') rows.push({ key: '目标', value: fmtNum(waterTarget()) + ' ml/天' });
    if (key === 'sleep' && points.length) {
      const lastPoint = points[points.length - 1];
      rows.push({ key: '最近一次', value: metricText(key, lastPoint.v) + ' · ' + chartDateLabel(lastPoint.t) });
    }
    return rows;
  }

  function noteLines() {
    const lines = [];
    if (detail.chartSource === 'level') {
      const level = (ui.chart && ui.chart.ok && ui.chart.value) || {};
      lines.push('估算（非血药浓度）：' + (level.disclaimer || '基于群体药代动力学的推算，并非医疗测量。'));
    }
    if (detail.summary && ui.summary && !ui.summary.ok) {
      lines.push('汇总数据加载失败：' + errorText(ui.summary.error) + '（下面的合计来自本次记录）');
    }
    if (section.key === 'health') {
      lines.push('其它健康记录（化验、血压、体征）不作严重度统计，但会出现在下面的列表里。');
    }
    if (section.key === 'sleep') {
      lines.push('睡眠时长建议保持 7–9 小时。');
    }
    return lines;
  }

  function paintNote() {
    clear(noteHost);
    if (!ui.records || !ui.records.ok) return;
    const rows = noteRows();
    const list = kvList(rows);
    if (list) noteHost.appendChild(list);
    for (const line of noteLines()) {
      noteHost.appendChild(el('p', { class: 'hint-line', text: line }));
    }
  }

  function removeRecord(record) {
    confirmDialog({
      title: '删除这条记录？',
      message: (record.title || '记录') + ' · ' + detailValueText(record) + ' · ' + rowTime(record) + '，删除后无法恢复。',
      confirmLabel: '删除',
    }).then((confirmed) => {
      if (!confirmed || closed) return;
      api.deleteRecord(record.id, { silent: true })
        .then(() => {
          if (closed) return;
          toast('已删除', 'success');
          if (typeof onChanged === 'function') onChanged();
          load();
        })
        .catch((err) => {
          if (closed) return;
          ui.actionError = errorText(err);
          sheet.setError(ui.actionError);
          paint();
        });
    });
  }

  function detailRow(record) {
    const meta = typeMeta(record.type);
    return listRow({
      icon: meta.icon,
      toneColor: meta.color,
      title: record.title || meta.label,
      subtitle: rowSubtitle(record),
      value: detailValueText(record),
      meta: rowTime(record),
      action: { label: '删除', onClick: () => removeRecord(record) },
      class: 'row--record',
    });
  }

  function paintList() {
    clear(listHead);
    clear(listHost);
    const count = ui.items.length;
    const status = !ui.records
      ? '加载中…'
      : !ui.records.ok
        ? '加载失败'
        : ui.loading
          ? '正在加载…'
          : count
            ? '共 ' + count + ' 条 · 最新在前'
            : '暂无';

    listHead.appendChild(el('h3', { class: 'detail-block-title', text: '记录' }));
    listHead.appendChild(el('span', { class: 'detail-block-sub', text: status }));

    if (!ui.records) {
      listHost.appendChild(skeleton(3));
      return;
    }
    if (!ui.records.ok) {
      listHost.appendChild(el('p', {
        class: 'hint-line',
        text: '记录加载失败：' + errorText(ui.records.error) + '。请在上方点「重试」。',
      }));
      return;
    }
    if (ui.actionError) {
      listHost.appendChild(errorState({
        title: '删除失败',
        message: ui.actionError,
        actionLabel: '知道了',
        onRetry: () => {
          ui.actionError = '';
          paint();
        },
      }));
    }
    if (!count) {
      listHost.appendChild(emptyState({
        icon: detail.icon,
        title: '还没有记录',
        hint: detail.emptyHint,
      }));
      return;
    }
    listHost.appendChild(el('div', { class: 'list' }, ui.items.map(detailRow)));
  }

  load();
  return sheet;
}

/* ------------------------------------------------------------------ *
 * view
 * ------------------------------------------------------------------ */

export function render(container) {
  const root = el('div', { class: 'view view--data' });
  const range = weekRange();
  let weekRecords = [];
  let destroyed = false;

  function paint() {
    if (destroyed) return;
    clear(root);
    root.appendChild(header());
    if (!state.ready) {
      root.appendChild(state.error
        ? errorState({ message: state.error, onRetry: () => reloadBootstrap() })
        : skeleton(5));
      return;
    }
    root.appendChild(weekHead(range));
    root.appendChild(weekTiles(weekRecords));
    root.appendChild(legendRow());
    root.appendChild(calendarCard());
    root.appendChild(dayList());
    root.appendChild(sectionsCard(weekRecords, range, refreshAll));
    root.appendChild(reportRow());
  }

  function loadWeek() {
    return api
      .records({ start: range.start, end: range.end }, { silent: true })
      .then((payload) => {
        weekRecords = (payload && payload.items) || [];
        paint();
      })
      .catch(() => {
        weekRecords = [];
        paint();
      });
  }

  function refreshAll() {
    loadSummary('week');
    loadDayRecords(state.selectedDate || state.today);
    loadWeek();
    loadWeightChart(90);
    reloadBootstrap();
  }

  paint();
  container.appendChild(root);

  loadSummary('week');
  loadCalendar(state.calendarMonth || (state.today || toDateKey(new Date())).slice(0, 7));
  loadDayRecords(state.selectedDate || state.today);
  loadWeek();
  if (!state.weight) loadWeightChart(90);

  const disconnect = connect(
    ['ready', 'error', 'summary', 'calendarMonth', 'calendarDays', 'selectedDate', 'dayRecords', 'weight', 'today'],
    paint
  );

  return () => {
    destroyed = true;
    disconnect();
    clear(root);
  };
}

export default { render };
