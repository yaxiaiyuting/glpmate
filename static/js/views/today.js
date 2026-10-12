/**
 * views/today.js — the home screen (今天).
 */

import { el, clear } from '../dom.js';
import { icon } from '../icons.js';
import { api } from '../api.js';
import {
  state, connect, set, fmtNum, fixedNum, signedNum, weekdayName, timeOf, shortDate, relativeDay,
  loadBootstrap, loadWeightChart, reloadBootstrap,
} from '../store.js';
import {
  card, cardHead, sectionHead, listRow, button, pillButton, infoNote, emptyState, errorState, skeleton,
} from '../components/card.js';
import { lineChart } from '../components/chart.js';
import { chipRow } from '../components/chips.js';
import { macroRow, thinBar } from '../components/progress.js';
import { quickRow, typeMeta } from '../components/list.js';
import { openRecordSheet, openTypePicker } from '../components/sheet.js';
import { toast } from '../components/toast.js';
import { navigate } from '../router.js';

const MACROS = [
  { key: 'calories', label: '卡路里', color: '#E8A33D' },
  { key: 'protein', label: '蛋白质', color: '#3DBFA0' },
  { key: 'carb', label: '碳水', color: '#E8607A' },
  { key: 'fat', label: '脂肪', color: '#8B7BD8' },
  { key: 'fiber', label: '纤维', color: '#4A9BE8' },
];

const FEELING_FALLBACK = ['我很好', '恶心', '便秘', '疲劳', '头痛', '腹胀'];

/* ------------------------------------------------------------------ *
 * small helpers
 * ------------------------------------------------------------------ */

const STAT_ALIASES = { carb: 'carbs', carbs: 'carb' };

function statOf(key) {
  const stats = state.stats || {};
  const s = stats[key] || stats[STAT_ALIASES[key]];
  if (!s || typeof s !== 'object') return { v: 0, t: 0 };
  const value = s.v !== undefined ? s.v : s.value;
  const target = s.t !== undefined ? s.t : s.target;
  return { v: Number(value) || 0, t: Number(target) || 0 };
}

function lastWeight() {
  const chart = state.weight || state.weightBoot;
  const series = chart && Array.isArray(chart.series) ? chart.series : [];
  const points = series
    .map((p) => ({ t: p && p.t, v: Number(p && (p.v !== undefined ? p.v : p.value)) }))
    .filter((p) => Number.isFinite(p.v));
  if (points.length) {
    const last = points[points.length - 1];
    const prev = points.length > 1 ? points[points.length - 2] : null;
    const bootDelta = Number(chart && chart.delta);
    return {
      value: last.v,
      delta: prev ? last.v - prev.v : Number.isFinite(bootDelta) ? bootDelta : 0,
      goal: Number(chart && chart.goal) || Number(state.targets && state.targets.weight_goal) || null,
      points: points,
    };
  }
  const rows = (state.recent || []).filter((r) => r.type === 'weight');
  if (rows.length) {
    const value = Number(rows[0].value);
    return { value: value, delta: 0, goal: Number(state.targets && state.targets.weight_goal) || null, points: [] };
  }
  return { value: null, delta: 0, goal: Number(state.targets && state.targets.weight_goal) || null, points: [] };
}

/** Worst side-effect severity recorded today, out of 10. */
function sideEffectScore() {
  const reported = state.stats && state.stats.side_effects;
  if (Array.isArray(reported) && reported.length) {
    const worst = reported.reduce((max, item) => {
      const value = item && typeof item === 'object'
        ? Number(item.severity !== undefined ? item.severity : item.value)
        : Number(item);
      return Math.max(max, Number.isFinite(value) ? value : 0);
    }, 0);
    return Math.max(0, Math.min(10, worst > 10 ? worst / 2 : worst));
  }
  const rows = (state.recent || []).filter((r) => r.type === 'symptom' || r.type === 'side_effect');
  if (!rows.length) return null;
  const worst = rows.reduce((max, r) => Math.max(max, Number(r.value) || 0), 0);
  if (!worst) return null;
  return Math.max(0, Math.min(10, worst > 10 ? worst / 2 : worst));
}

function levelPoints(level) {
  const series = level && Array.isArray(level.series) ? level.series : [];
  return series
    .map((p) => ({ t: p && p.t, v: Number(p && (p.mg !== undefined ? p.mg : p.v !== undefined ? p.v : p.pct)) }))
    .filter((p) => Number.isFinite(p.v));
}

function greetingHead() {
  const name = (state.profile && (state.profile.display_name || state.profile.name)) || '朋友';
  return el(
    'header',
    { class: 'greet' },
    el('p', {
      class: 'greet-sub',
      text: weekdayName(state.weekday) + ' · 第 ' + (state.dayIndex || 1) + ' 天',
    }),
    el('h1', { class: 'greet-title', text: '你好，' + name })
  );
}

/* ------------------------------------------------------------------ *
 * 下次剂量
 * ------------------------------------------------------------------ */

function weekdayFromDate(value) {
  if (typeof value !== 'string' || value.length < 10) return '';
  const date = new Date(value.slice(0, 10) + 'T00:00:00');
  if (!Number.isFinite(date.getTime())) return '';
  return weekdayName((date.getDay() + 6) % 7);
}

function doseCard() {
  const dose = state.nextDose || {};
  const profile = state.profile || {};
  const drug = dose.drug || dose.med_name || profile.med_name || '穆峰达';
  const mg = dose.dose_mg !== undefined ? dose.dose_mg : dose.value !== undefined ? dose.value : profile.dose_mg;
  const dayCount = dose.days_left !== undefined ? dose.days_left : dose.remaining_days;
  const daysLeft = dayCount !== undefined && dayCount !== null
    ? Number(dayCount)
    : dose.due_at || dose.at
      ? Math.max(0, Math.ceil((new Date(String(dose.due_at || dose.at)).replace(' ', 'T')).getTime() - Date.now()) / 86400000)
      : null;
  const when = dose.weekday_cn
    || weekdayFromDate(dose.date || dose.due_at || dose.at)
    || weekdayName(dose.weekday)
    || (dose.at ? shortDate(dose.at) : '');
  const timeText = dose.time || '';
  const whenText = [when, timeText].filter(Boolean).join(' ');
  const headline = [whenText, drug + (mg !== undefined && mg !== null ? ' ' + fmtNum(mg) + ' mg' : '')]
    .filter(Boolean)
    .join(' · ');
  const takenToday = dose.taken_today !== undefined
    ? !!dose.taken_today
    : !!(state.stats && state.stats.dose_taken);
  const dueToday = dose.is_due_today !== undefined ? !!dose.is_due_today : takenToday;
  const todayTime = dose.today_time || timeText;
  const dueLine = takenToday
    ? '今日 ' + (todayTime || '') + ' 已服用'
    : dueToday
      ? (dose.overdue ? '已超过计划时间 ' + (todayTime || '') : '今日 ' + (todayTime || '') + ' 服用')
      : (dose.date ? shortDate(dose.date) + ' ' + (whenText ? whenText + ' ' : '') + '服药' : '今日 ' + (todayTime || '') + ' 服用');

  return card(
    { class: 'card--dose' },
    el(
      'div',
      { class: 'dose-top' },
      el('span', { class: 'dose-badge' }, icon('dose', { size: 22 })),
      el(
        'div',
        { class: 'dose-main' },
        el('p', { class: 'dose-kicker', text: '下次剂量' }),
        el('p', { class: 'dose-when', text: headline || '还没有安排下一次剂量' })
      ),
      el(
        'div',
        { class: 'dose-left' },
        el('span', { class: 'dose-left-label', text: '剩余' }),
        el('span', {
          class: 'dose-left-value',
          text: daysLeft === null ? '—' : fmtNum(daysLeft) + ' 天',
        })
      )
    ),
    el(
      'div',
      { class: 'dose-bottom' },
      el(
        'div',
        { class: 'dose-today' },
        el('span', { class: 'dose-today-icon' }, icon(takenToday ? 'check' : 'clock', { size: 16 })),
        el('span', { class: 'dose-today-text', text: dueLine })
      ),
      pillButton({
        label: '我的剂量',
        onClick: () => openRecordSheet({
          type: 'dose',
          title: '记录剂量',
          quickAdds: null,
          profile: state.profile,
          onSubmit: (payload) => createRecord(payload, '剂量已记录'),
        }),
      })
    )
  );
}

/* ------------------------------------------------------------------ *
 * 体内药物水平 / 体重
 * ------------------------------------------------------------------ */

function segmented() {
  const options = [
    { key: 'drug', label: '药物水平' },
    { key: 'weight', label: '体重' },
  ];
  return el(
    'div',
    { class: 'segmented', role: 'tablist', 'aria-label': '选择图表' },
    options.map((option) =>
      el('button', {
        class: 'segmented-btn' + (state.levelRange === option.key ? ' is-active' : ''),
        type: 'button',
        role: 'tab',
        'aria-selected': state.levelRange === option.key ? 'true' : 'false',
        text: option.label,
        onClick: () => {
          if (state.levelRange === option.key) return;
          set({ levelRange: option.key });
          if (option.key === 'weight' && !state.weight) loadWeightChart(90);
        },
      })
    )
  );
}

function chartLegend(left, linkLabel, onLink) {
  return el(
    'div',
    { class: 'chart-legend' },
    el('span', { class: 'chart-legend-left', text: left }),
    el('button', {
      class: 'chart-legend-link',
      type: 'button',
      text: linkLabel,
      onClick: onLink,
    })
  );
}

function drugChartBody() {
  const level = state.level || {};
  const points = levelPoints(level);
  const peak = Number(level.pct_peak);
  const currentMg = level.current_mg !== undefined ? level.current_mg : (points.length ? points[points.length - 1].v : null);
  const nowPct = Number(level.now_pct);

  const hero = el(
    'div',
    { class: 'hero' },
    el(
      'div',
      { class: 'hero-main' },
      el('span', { class: 'hero-number', text: Number.isFinite(peak) ? fmtNum(peak) : '—' }),
      el('span', { class: 'hero-unit', text: '%' })
    ),
    el(
      'div',
      { class: 'hero-side' },
      el('span', { class: 'hero-label', text: '峰值' }),
      el('span', {
        class: 'hero-sub',
        text: Number.isFinite(nowPct)
          ? '当前 ' + fmtNum(currentMg) + ' mg · ' + fmtNum(nowPct) + '%'
          : '基于群体药代动力学',
      })
    )
  );

  if (!points.length) {
    return el('div', null, hero, emptyState({ icon: 'lab', title: '还没有药物水平数据', hint: '记录一次剂量后就会显示曲线' }));
  }

  const last = points[points.length - 1];
  const guideTimes = (Array.isArray(level.annotations) ? level.annotations : [])
    .map((a) => (a && a.t ? a.t : null))
    .filter(Boolean);

  const chart = lineChart({
    series: points,
    color: '#35C6E8',
    width: 360,
    height: 180,
    xTicks: 4,
    yTicks: 3,
    guides: guideTimes,
    gridLines: Number.isFinite(peak) && currentMg
      ? [{ v: Number(currentMg), label: fmtNum(currentMg) + ' mg' }]
      : [],
    valueFormat: (v) => fmtNum(v, 2),
    tickFormat: (t) => shortDate(t),
    markers: [{ t: last.t, lines: [fmtNum(currentMg) + ' mg', (Number.isFinite(nowPct) ? nowPct : '') + '% · 现在'] }],
    ariaLabel: '体内药物水平估算曲线',
  });

  return el(
    'div',
    null,
    hero,
    el('div', { class: 'chart-host' }, chart),
    chartLegend((level && level.trend) || '正在积累', '详情 →', () => navigate('/data', { month: state.calendarMonth }))
  );
}

function weightChartBody() {
  const info = lastWeight();
  const hero = el(
    'div',
    { class: 'hero' },
    el(
      'div',
      { class: 'hero-main' },
      el('span', { class: 'hero-number', text: info.value === null ? '—' : fixedNum(info.value, 1) }),
      el('span', { class: 'hero-unit', text: 'kg' })
    ),
    el(
      'div',
      { class: 'hero-side' },
      el('span', { class: 'hero-label', text: '最新体重' }),
      el('span', {
        class: 'hero-sub',
        text: info.goal ? '目标 ' + fmtNum(info.goal) + ' kg' : '还没有设置目标',
      })
    )
  );

  if (!info.points.length) {
    return el('div', null, hero, emptyState({
      icon: 'weight',
      title: '还没有体重记录',
      hint: '称一次体重就会出现在这里',
      actionLabel: '记录体重',
      onAction: () => openRecordSheet({ type: 'weight', onSubmit: (payload) => createRecord(payload, '体重已记录') }),
    }));
  }

  const last = info.points[info.points.length - 1];
  const chart = lineChart({
    series: info.points,
    color: '#3DBFA0',
    goal: info.goal,
    goalLabel: info.goal ? fmtNum(info.goal) + ' kg' : '',
    width: 360,
    height: 180,
    xTicks: 4,
    yTicks: 3,
    valueFormat: (v) => fmtNum(v, 1),
    tickFormat: (t) => shortDate(t),
    markers: [{ t: last.t, lines: [fixedNum(last.v, 1) + ' kg', '现在'] }],
    ariaLabel: '体重变化曲线',
  });

  const delta = info.delta;
  const toGoal = info.goal !== null && info.value !== null ? info.value - info.goal : null;
  const deltaText = [
    signedNum(delta, 1) + ' kg · 较上次',
    toGoal !== null && toGoal > 0 ? '距目标 ' + fixedNum(toGoal, 1) + ' kg' : '',
  ].filter(Boolean).join(' · ');
  return el(
    'div',
    null,
    hero,
    el('div', { class: 'chart-host' }, chart),
    chartLegend(deltaText, '详情 →', () => navigate('/data'))
  );
}

function levelCard() {
  const isDrug = state.levelRange !== 'weight';
  return card(
    { class: 'card--chart' },
    el('div', { class: 'card-head card-head--center' }, segmented()),
    el('div', { class: 'level-body' }, isDrug ? drugChartBody() : weightChartBody())
  );
}

/* ------------------------------------------------------------------ *
 * 今日你的记录
 * ------------------------------------------------------------------ */

function feelingCard() {
  const options = (state.feelings && state.feelings.length ? state.feelings : FEELING_FALLBACK);
  return card(
    null,
    cardHead({ title: '你感觉如何?', subtitle: '点一下就能记下来，不用打字' }),
    chipRow(options, {
      tone: 'soft',
      onSelect: (label) => {
        const good = label.indexOf('很好') !== -1;
        createRecord(
          {
            type: 'side_effect',
            value: good ? 1 : 3,
            unit: '级',
            meta: { label: label, severity: good ? 1 : 3, note: '今日感受' },
          },
          '已记录「' + label + '」'
        ).then(() => reloadBootstrap());
      },
    }),
    el('p', { class: 'hint-line', text: '记录感受能帮助你看清副作用和剂量的关系。' })
  );
}

function mealWindow() {
  const entry = (state.schedule || []).find((item) => item && item.key === 'dinner');
  if (!entry || entry.enabled === false) return '';
  if (entry.start && entry.end) return entry.start + '–' + entry.end + ' 窗口';
  return '';
}

function dinnerCard() {
  const meals = (state.recent || []).filter((r) => r.type === 'food');
  const logged = state.stats && state.stats.meals_logged !== undefined
    ? Number(state.stats.meals_logged)
    : meals.length;
  const count = Number.isFinite(logged) ? logged : meals.length;
  const items = MACROS.map((macro) => {
    const s = statOf(macro.key);
    return { key: macro.key, label: macro.label, value: s.v, target: s.t, color: macro.color };
  });

  return card(
    null,
    cardHead({
      title: '晚餐',
      subtitle: ['晚餐', mealWindow() || '窗口开启'].join(' ') + ' · 今日已记录 ' + count + ' 餐',
    }),
    macroRow(items),
    button({
      label: '添加餐食',
      kind: 'primary',
      block: true,
      icon: 'plus',
      onClick: () => openRecordSheet({
        type: 'food',
        title: '添加餐食',
        onSubmit: (payload) => createRecord(payload, '餐食已记录').then(() => reloadBootstrap()),
      }),
    }),
    el(
      'button',
      {
        class: 'tip-row',
        type: 'button',
        onClick: () => navigate('/coach'),
      },
      el('span', { class: 'tip-emoji', text: '💡' }),
      el('span', { class: 'tip-text', text: '不确定吃什么？我们一起想办法' }),
      icon('arrow-right', { size: 16 })
    )
  );
}

function waterCard() {
  const water = statOf('water');
  const liters = Number.isFinite(water.v) ? water.v : 0;
  const target = Number.isFinite(water.t) && water.t > 0 ? water.t : 5;
  return card(
    null,
    cardHead({
      title: '饮水',
      subtitle: fixedNum(liters, 1) + ' / ' + fixedNum(target, 1) + ' 升 · 缓解恶心和便秘的第一道防线',
    }),
    thinBar({ value: liters, target: target, color: '#4A9BE8', label: '饮水进度' }),
    el(
      'div',
      { class: 'water-actions' },
      button({
        label: '+250 ml',
        kind: 'pill',
        icon: 'water',
        onClick: () => createRecord(
          { type: 'water', value: 250, unit: 'ml', meta: {} },
          '已记录 250 ml'
        ).then(() => reloadBootstrap()),
      }),
      button({
        label: '自定义',
        kind: 'ghost',
        small: true,
        onClick: () => openRecordSheet({
          type: 'water',
          title: '记录饮水',
          quickAdds: [250, 500, 750],
          onSubmit: (payload) => createRecord(payload, '饮水已记录').then(() => reloadBootstrap()),
        }),
      })
    )
  );
}

function halfCards() {
  const info = lastWeight();
  const delta = info.delta;
  const score = sideEffectScore();

  const weightTile = card(
    { class: 'card--half' },
    el(
      'button',
      {
        class: 'half-btn',
        type: 'button',
        onClick: () => openRecordSheet({
          type: 'weight',
          title: '记录体重',
          onSubmit: (payload) => createRecord(payload, '体重已记录').then(() => reloadBootstrap()),
        }),
      },
      el('span', { class: 'half-label', text: '体重' }),
      el(
        'span',
        { class: 'half-value' },
        el('span', { class: 'half-number', text: info.value === null ? '—' : fixedNum(info.value, 1) }),
        el('span', { class: 'half-unit', text: 'kg' })
      ),
      el('span', {
        class: 'half-hint',
        text: (info.goal ? '目标 ' + fmtNum(info.goal) + ' kg · ' : '') + signedNum(delta, 1) + ' kg',
      })
    )
  );

  const healthTile = card(
    { class: 'card--half' },
    el(
      'div',
      { class: 'half-btn' },
      el('span', { class: 'half-label', text: '健康' }),
      el(
        'span',
        { class: 'half-value' },
        el('span', { class: 'half-number', text: score === null ? '—' : fmtNum(score) }),
        el('span', { class: 'half-unit', text: '/10' })
      ),
      el('span', {
        class: 'half-hint',
        text: score === null ? '暂无副作用记录' : '今日副作用评分',
      })
    )
  );

  return el('div', { class: 'half-grid' }, weightTile, healthTile);
}

function completedSection() {
  const rows = Array.isArray(state.completed) ? state.completed : [];
  const body = rows.length
    ? el(
        'div',
        { class: 'list' },
        rows.map((record) => {
          const meta = typeMeta(record.type);
          const at = timeOf(record.at);
          return listRow({
            icon: meta.icon,
            toneColor: meta.color,
            title: record.title || meta.label,
            subtitle: record.subtitle || '',
            value: record.value !== undefined && record.value !== null
              ? fmtNum(record.value) + (record.unit ? ' ' + record.unit : '')
              : '',
            meta: at || relativeDay(String(record.at || '').slice(0, 10)),
            valueTone: 'muted',
            action: {
              label: '修正',
              onClick: () => openRecordSheet({
                record: record,
                onSubmit: (payload) => updateRecord(record.id, payload, '已更新'),
                onDelete: (item) => deleteRecord(item.id, '已删除'),
              }),
            },
          });
        })
      )
    : emptyState({
        icon: 'check',
        title: '还没有记录，点右下角开始',
        hint: '体重、餐食、饮水、剂量都可以在这里记录',
      });

  return el('section', { class: 'section' }, sectionHead('已完成', el('span', { class: 'section-count', text: rows.length + ' 条' })), body);
}

function quickSection() {
  const types = Array.isArray(state.quickTypes) && state.quickTypes.length
    ? state.quickTypes
    : [
        { type: 'body_fat', label: '体脂' },
        { type: 'lab', label: '化验' },
        { type: 'blood_pressure', label: '血压' },
        { type: 'training', label: '训练' },
        { type: 'sleep', label: '睡眠' },
      ];

  const pick = (item) => {
    openRecordSheet({
      type: item.type,
      title: '记录' + (item.label || ''),
      onSubmit: (payload) => createRecord(payload, '已记录').then(() => reloadBootstrap()),
    });
  };

  return el(
    'section',
    { class: 'section' },
    sectionHead('快速记录'),
    card(
      null,
      quickRow(types, {
        total: types.length || 15,
        onPick: pick,
        onSearch: () => openTypePicker({ types: types, onPick: pick }),
      })
    )
  );
}

/* ------------------------------------------------------------------ *
 * record mutations shared by this view
 * ------------------------------------------------------------------ */

function createRecord(payload, message) {
  return api
    .createRecord(payload)
    .then((record) => {
      if (message) toast(message, 'success');
      return record;
    })
    .catch((err) => {
      throw err;
    });
}

function updateRecord(id, payload, message) {
  return api.updateRecord(id, payload).then((record) => {
    if (message) toast(message, 'success');
    reloadBootstrap();
    return record;
  });
}

function deleteRecord(id, message) {
  return api.deleteRecord(id).then((result) => {
    if (message) toast(message, 'success');
    reloadBootstrap();
    return result;
  });
}

/* ------------------------------------------------------------------ *
 * view
 * ------------------------------------------------------------------ */

export function render(container) {
  const root = el('div', { class: 'view view--today' });

  function paint() {
    clear(root);
    if (!state.ready) {
      root.appendChild(greetingHead());
      root.appendChild(state.error
        ? errorState({ message: state.error, onRetry: () => reloadBootstrap() })
        : skeleton(4));
      return;
    }
    root.appendChild(greetingHead());
    root.appendChild(doseCard());
    root.appendChild(levelCard());
    const disclaimer = (state.level && state.level.disclaimer)
      || '基于群体药代动力学的估算，并非医疗测量。';
    root.appendChild(el('div', { class: 'note-wrap' }, infoNote(disclaimer, {
      label: '来源',
      onClick: () => toast('药代参数取自厂商说明书的公开数据（点"来源"可看逐条出处）', 'info'),
    })));
    root.appendChild(el('section', { class: 'section' }, sectionHead('今日你的记录')));
    root.appendChild(feelingCard());
    root.appendChild(dinnerCard());
    // 顺序对齐参考设计：饮水 → 已完成记录 → 体重/健康两张半宽卡 → 快速记录
    root.appendChild(waterCard());
    root.appendChild(completedSection());
    root.appendChild(halfCards());
    root.appendChild(quickSection());
  }

  paint();
  container.appendChild(root);

  if (!state.ready) {
    loadBootstrap().catch(() => {});
  }
  if (!state.weight) loadWeightChart(90);

  const disconnect = connect(['ready', 'error', 'stats', 'level', 'levelRange', 'weight', 'completed', 'recent', 'nextDose', 'quickTypes', 'dayIndex', 'weekday'], paint);

  return () => {
    disconnect();
    clear(root);
  };
}

export default { render };
