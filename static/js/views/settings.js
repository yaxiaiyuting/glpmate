/**
 * views/settings.js — settings screen (设置).
 *
 * Includes the self-hosted replacement for the subscription row: an inline
 * "模型与额度" form (base URL / API key / model / vision / quota / temperature).
 */

import { el, clear } from '../dom.js';
import { icon } from '../icons.js';
import { api } from '../api.js';
import { state, connect, set, loadSettings, loadBootstrap, reloadBootstrap, unwrapSettings, fmtNum } from '../store.js';
import { card, cardHead, sectionHead, listRow, button, errorState, skeleton, emptyState, infoNote } from '../components/card.js';
import { field, readFields, openSheet, confirmDialog } from '../components/sheet.js';
import { toast } from '../components/toast.js';

const APP_VERSION = '1.0.0';
const LICENSE = 'GPL-3.0';

/* ------------------------------------------------------------------ *
 * profile
 * ------------------------------------------------------------------ */

function initial() {
  const name = (state.profile && (state.profile.display_name || state.profile.name)) || '我';
  return String(name).charAt(0);
}

function profileCard() {
  const profile = state.profile || {};
  const name = profile.display_name || profile.name || '未设置昵称';
  const email = profile.email || profile.account || '本地账号';
  const joined = profile.joined ? ' · ' + profile.joined : ' · 本周加入';

  return card(
    { class: 'card--profile' },
    el(
      'button',
      {
        class: 'profile-btn',
        type: 'button',
        onClick: () => editProfile(),
      },
      el('span', { class: 'avatar avatar--lg', 'aria-hidden': 'true' }, el('span', { text: initial() })),
      el(
        'span',
        { class: 'profile-text' },
        el('span', { class: 'profile-name', text: name }),
        el('span', { class: 'profile-sub', text: email + joined })
      ),
      el('span', { class: 'row-chev' }, icon('chevron', { size: 18 }))
    )
  );
}

function editProfile() {
  const nameField = field({
    name: 'display_name',
    label: '昵称',
    type: 'text',
    value: (state.profile && state.profile.display_name) || '',
    required: true,
    placeholder: '例如：姜天佑',
  });

  const sheet = openSheet({
    title: '个人资料',
    subtitle: '昵称会显示在首页问候里',
    body: [nameField],
    actions: [
      button({
        label: '保存',
        kind: 'primary',
        block: true,
        onClick: () => {
          const result = readFields([nameField]);
          if (result.firstInvalid) {
            result.firstInvalid.focus();
            return;
          }
          api
            .putSettings({ display_name: result.values.display_name, profile: { display_name: result.values.display_name } })
            .then((payload) => {
              const norm = unwrapSettings(payload);
              set({
                settings: norm.settings && Object.keys(norm.settings).length ? norm.settings : state.settings,
                profile: Object.assign({}, state.profile, norm.profile || {}, { display_name: result.values.display_name }),
              });
              toast('已保存', 'success');
              sheet.close();
            })
            .catch((err) => sheet.setError(err && err.message ? err.message : '保存失败'));
        },
      }),
    ],
  });
}

/* ------------------------------------------------------------------ *
 * 治疗 group — 可编辑面板
 * ------------------------------------------------------------------ */

const WEEKDAY_OPTIONS = [
  { value: '0', label: '周一' },
  { value: '1', label: '周二' },
  { value: '2', label: '周三' },
  { value: '3', label: '周四' },
  { value: '4', label: '周五' },
  { value: '5', label: '周六' },
  { value: '6', label: '周日' },
];

/** 后端给的置信度是 high / medium / low，界面上说人话。 */
const CONFIDENCE_CN = { high: '高', medium: '中', low: '低' };

/** 数值兜底：NaN / 空值 / undefined 都退回 fallback。 */
function toNum(value, fallback) {
  const n = typeof value === 'number' ? value : parseFloat(value);
  return Number.isFinite(n) ? n : fallback;
}

/** 取第一个能用的数字：0 是合法剂量，不能用 `||` 兜底。 */
function firstNum(...values) {
  for (const value of values) {
    const n = toNum(value, null);
    if (n !== null) return n;
  }
  return null;
}

/** '08:00:00' / '8:00' -> '08:00'；识别不出来时返回 fallback。 */
function hhmm(value, fallback) {
  const raw = value === null || value === undefined ? '' : String(value).trim();
  const m = /^(\d{1,2}):(\d{2})/.exec(raw);
  if (!m) return fallback === undefined ? '' : fallback;
  return (m[1].length < 2 ? '0' + m[1] : m[1]) + ':' + m[2];
}

/** 给药间隔文案：1 天必须说「每天」，不能说「每 1 天」。 */
function intervalText(days) {
  const n = toNum(days, 7);
  return n <= 1 ? '每天' : '每 ' + fmtNum(n) + ' 天';
}

/** 只读键值行（infoSheet 与几个编辑面板共用）。 */
function kvRow(key, value) {
  return el(
    'div',
    { class: 'kv-row' },
    el('span', { class: 'kv-key', text: key }),
    el('span', { class: 'kv-value', text: value })
  );
}

/** 目标体重存在 profile.target_weight_kg，targets 里可能带旧字段。 */
function currentGoalWeight() {
  const targets = state.targets || {};
  const profile = state.profile || {};
  return firstNum(targets.weight_goal, targets.target_weight_kg, profile.target_weight_kg, 80);
}

/** 饮水目标统一换算成升；后端 targets.water_ml 可能由 profile.water_target_l 覆盖。 */
function currentWaterLiters() {
  const targets = state.targets || {};
  const profile = state.profile || {};
  const ml = toNum(targets.water_ml, null);
  if (ml !== null && ml > 0) return Math.round(ml / 10) / 100;
  return firstNum(profile.water_target_l, 2);
}

/** 提醒行的副标题：直接反映 schedule，保存后能立刻看到变化。 */
function reminderSubtitle() {
  const settings = state.settings || {};
  if (settings.reminders === false) return '已关闭';
  const items = (Array.isArray(state.schedule) ? state.schedule : []).filter((item) => item && (item.key || item.label));
  if (!items.length) return '给药日提醒 · 饮水提醒';
  const on = items.filter((item) => item.enabled !== false);
  if (!on.length) return '已全部关闭 · 点开可以重新开启';
  const water = on.find((item) => item.key === 'water') || null;
  const window = water && water.start && water.end ? water.start + '–' + water.end + ' 饮水' : '';
  return ['已开启 ' + on.length + '/' + items.length + ' 项', window].filter(Boolean).join(' · ');
}

/**
 * 范围校验（比 readFields 的「不能小于 N」更好读）。
 * 空值交给 readFields 报「这一项不能为空」。
 */
function checkRange(wrap, label, min, max, unit) {
  const raw = String(wrap.input.value || '').trim();
  if (raw === '') return true;
  const value = Number(raw);
  if (!Number.isFinite(value)) {
    wrap.setError('请输入数字');
    return false;
  }
  if (value < min || value > max) {
    wrap.setError(label + '要在 ' + min + '–' + max + (unit || '') + ' 之间');
    return false;
  }
  return true;
}

/**
 * 三个治疗表单共用的保存流程：写后端 → 更新 store → toast → 关面板 → 重新拉取。
 * 失败时把后端的中文错误留在面板里，不关闭面板。
 */
function saveTreatment(payload, sheet, saveBtn) {
  sheet.setError('');
  saveBtn.disabled = true;
  api
    .putSettings(payload, { silent: true })
    .then((updated) => {
      const norm = unwrapSettings(updated);
      const patch = {};
      if (norm.profile) patch.profile = Object.assign({}, state.profile, norm.profile);
      if (norm.targets) patch.targets = Object.assign({}, state.targets, norm.targets);
      if (Array.isArray(payload.schedule)) patch.schedule = payload.schedule;
      set(patch);
      toast('已保存', 'success');
      sheet.close();
      // PUT 的返回里没有 schedule / next_dose，再拉一次让副标题和首页立刻跟上。
      loadSettings();
      reloadBootstrap();
    })
    .catch((err) => {
      sheet.setError(err && err.message ? err.message : '保存失败，请稍后再试');
    })
    .then(() => {
      saveBtn.disabled = false;
    });
}

/**
 * 药物与剂量：药物 / 剂量 / 注射时间 / 给药日 / 治疗开始日。
 *
 * 半衰期、达峰时间、给药间隔由后端的药物参数表决定（换药时自动套用），
 * 这里只读展示、不提供手改——参数编错了曲线还是很好看，但会误导人。
 */
function drugSheet() {
  const profile = state.profile || {};
  const loading = el('p', { class: 'hint-line', text: '正在读取药物参数表…' });
  const listNote = el('p', { class: 'hint-line' });
  const intervalHint = el('p', { class: 'hint-line' });

  const drugField = field({
    name: 'drug_id',
    label: '药物',
    type: 'select',
    options: [],
    hint: '换药后剂量会重置为该药的起始剂量，说明书参数由后端自动套用。',
  });
  const doseField = field({
    name: 'dose_mg',
    label: '剂量',
    type: 'select',
    options: [],
    hint: '可选项来自该药的说明书规格，不能随便填。',
  });
  const infoList = el('div', { class: 'kv-list kv-list--boxed' });
  const timeField = field({
    name: 'dose_time',
    label: '注射时间',
    type: 'time',
    value: hhmm(profile.dose_time, '12:00'),
    hint: '首页「下次剂量」按这个时间显示。',
  });
  const weekdayField = field({
    name: 'dose_weekday',
    label: '给药日',
    type: 'select',
    options: WEEKDAY_OPTIONS,
    value: String(toNum(profile.dose_weekday, 0)),
    hint: '每 7 天一次的药，选一个固定的星期几。',
  });
  const startField = field({
    name: 'treatment_start',
    label: '治疗开始日',
    type: 'date',
    value: typeof profile.treatment_start === 'string' ? profile.treatment_start.slice(0, 10) : '',
    hint: '留空表示用第一条剂量记录的日期。',
  });

  const fields = [drugField, doseField, timeField, weekdayField, startField];
  let drugs = [];

  const form = el(
    'div',
    { class: 'form' },
    drugField,
    doseField,
    infoList,
    timeField,
    weekdayField,
    startField,
    intervalHint,
    el('p', {
      class: 'hint-line',
      text: '半衰期、达峰时间、给药间隔与置信度取自厂商说明书参数表，由后端按药物套用，不能在这里手改。',
    }),
    loading,
    listNote
  );

  const retry = button({ label: '重新读取药物列表', kind: 'ghost', small: true, onClick: () => load() });
  retry.hidden = true;

  const save = button({
    label: '保存',
    kind: 'primary',
    block: true,
    disabled: true,
    onClick: () => submit(),
  });

  const sheet = openSheet({
    title: '药物与剂量',
    subtitle: '用药方案保存在你自己的服务器上',
    body: [
      form,
      el(
        'div',
        { class: 'list' },
        listRow({
          icon: 'file-text',
          toneColor: '#35C6E8',
          title: '疗效参考（厂商说明书）',
          subtitle: '注册试验的人群平均结果 · 带原文出处',
          chevron: true,
          onClick: () => efficacySheet(),
        })
      ),
      el('div', { class: 'form-actions' }, retry),
      el('p', { class: 'hint-line', text: '这里只是记录用药方案，不构成医疗建议；调整剂量前请先问医生。' }),
    ],
    actions: [save],
  });

  function drugById(id) {
    return drugs.find((drug) => String(drug.value) === String(id)) || null;
  }

  /** 读不到参数表时的兜底：只保留当前药物，绝不编造可选项。 */
  function fallbackDrug() {
    const dose = toNum(profile.dose_mg, null);
    return {
      value: String(profile.drug_id || 'current'),
      label: (profile.med_name || '当前药物') + '（当前设置）',
      interval_days: toNum(profile.dose_interval_days, 7),
      dose_steps_mg: dose === null ? [] : [dose],
      half_life_hours: profile.half_life_hours,
      tmax_hours: profile.tmax_hours,
      brands: [],
      confidence: null,
    };
  }

  /** 药物变了就重建剂量选项、只读参数块和星期选择器。 */
  function syncDrug(drug, preferred, keepCustom) {
    const days = toNum(drug && drug.interval_days, toNum(profile.dose_interval_days, 7));
    const steps = [];
    for (const step of (drug && Array.isArray(drug.dose_steps_mg) ? drug.dose_steps_mg : [])) {
      const n = toNum(step, null);
      if (n !== null && steps.indexOf(n) === -1) steps.push(n);
    }
    const want = toNum(preferred, null);
    // 用户库里已有的自定义剂量要保留，不能静默改数。
    const custom = keepCustom && want !== null && steps.indexOf(want) === -1;
    const options = custom ? steps.concat([want]) : steps;

    clear(doseField.input);
    for (const mg of options) {
      doseField.input.appendChild(
        el('option', {
          value: String(mg),
          text: fmtNum(mg) + ' mg' + (custom && mg === want ? '（当前剂量）' : ''),
        })
      );
    }
    const chosen = want !== null && options.indexOf(want) !== -1 ? want : (options.length ? options[0] : null);
    doseField.input.value = chosen === null ? '' : String(chosen);
    doseField.hidden = !options.length;

    const half = drug ? toNum(drug.half_life_hours, null) : null;
    const tmax = drug ? toNum(drug.tmax_hours, null) : null;
    clear(infoList);
    infoList.appendChild(kvRow('给药间隔', intervalText(days)));
    infoList.appendChild(kvRow('半衰期', half === null ? '—' : fmtNum(half) + ' 小时'));
    infoList.appendChild(kvRow('达峰时间', tmax === null ? '—' : fmtNum(tmax) + ' 小时'));
    infoList.appendChild(kvRow('数据置信度', drug && CONFIDENCE_CN[drug.confidence] ? CONFIDENCE_CN[drug.confidence] : '—'));
    if (drug && Array.isArray(drug.brands) && drug.brands.length) {
      infoList.appendChild(kvRow('常见商品名', drug.brands.join(' / ')));
    }

    // 每天给药的药（例如利拉鲁肽，间隔 1 天）不显示星期选择器。
    weekdayField.hidden = !(days >= 7);
    intervalHint.textContent = days >= 7
      ? '给药间隔 ' + intervalText(days) + '：选好星期几，首页会自动算出下次给药时间。'
      : '给药间隔 ' + intervalText(days) + '（每天给药），因此不需要选择星期几。';
  }

  function fillDrugOptions(list) {
    drugs = list;
    clear(drugField.input);
    for (const drug of drugs) {
      drugField.input.appendChild(el('option', { value: String(drug.value), text: drug.label || String(drug.value) }));
    }
    const active = drugById(profile.drug_id) || drugs[0] || null;
    if (active) drugField.input.value = String(active.value);
    syncDrug(active, profile.dose_mg, true);
  }

  function submit() {
    const active = drugById(drugField.input.value);
    if (!active) {
      sheet.setError('请先选择药物');
      return;
    }
    const result = readFields(fields.filter((wrap) => !wrap.hidden));
    if (result.firstInvalid) {
      result.firstInvalid.focus();
      sheet.setError('请检查标红的输入项');
      return;
    }
    const dose = toNum(result.values.dose_mg, null);
    if (dose === null) {
      doseField.setError('请选择剂量');
      doseField.input.focus();
      sheet.setError('请检查标红的输入项');
      return;
    }
    const time = hhmm(result.values.dose_time, '');
    if (!time) {
      timeField.setError('请填写注射时间');
      timeField.input.focus();
      sheet.setError('请检查标红的输入项');
      return;
    }
    const days = toNum(active.interval_days, toNum(profile.dose_interval_days, 7));
    const patch = {
      drug_id: String(active.value),
      dose_mg: dose,
      dose_time: time,
      treatment_start: result.values.treatment_start
        ? String(result.values.treatment_start).slice(0, 10)
        : null,
    };
    // 每周给药的药才需要星期几；每天给药时不传这个字段。
    if (days >= 7) patch.dose_weekday = toNum(result.values.dose_weekday, 0);
    saveTreatment({ profile: patch }, sheet, save);
  }

  function load() {
    loading.hidden = false;
    retry.hidden = true;
    listNote.textContent = '';
    api
      .drugs({ silent: true })
      .then((payload) => {
        const list = (payload && Array.isArray(payload.options) ? payload.options : [])
          .filter((drug) => drug && drug.value);
        if (!list.length) throw new Error('后端没有返回药物列表');
        fillDrugOptions(list);
        save.disabled = false;
      })
      .catch((err) => {
        fillDrugOptions([fallbackDrug()]);
        listNote.textContent = '药物列表读取失败（' + ((err && err.message) || '未知错误')
          + '）：只能修改当前药物的剂量与时间。';
        retry.hidden = false;
        save.disabled = false;
      })
      .then(() => {
        loading.hidden = true;
      });
  }

  drugField.input.addEventListener('change', () => {
    // 换药后后端会把剂量重置为该药的起始剂量，这里同步成新药的第一步。
    syncDrug(drugById(drugField.input.value), null, false);
  });

  load();
  return sheet;
}

/**
 * 提醒：编辑 schedule 数组（每项一个启用开关 + 开始/结束时间）。
 *
 * 诚实提示：浏览器通知还没有接入后端，这里存的时间只在应用内显示。
 */
function reminderSheet() {
  const host = el('div', { class: 'form' });
  let rows = [];
  let entries = [];

  const save = button({
    label: '保存',
    kind: 'primary',
    block: true,
    disabled: true,
    onClick: () => submit(),
  });

  const sheet = openSheet({
    title: '提醒',
    subtitle: '每日提醒时段',
    body: [
      host,
      el('p', {
        class: 'hint-line',
        text: '浏览器通知还没接入后端：这里保存的时间只在应用内显示，不会真的推送到手机。',
      }),
    ],
    actions: [save],
  });

  function readEntries() {
    return (Array.isArray(state.schedule) ? state.schedule : [])
      .filter((item) => item && typeof item === 'object')
      .map((item) => Object.assign({}, item));
  }

  function itemNode(entry, index) {
    const toggle = el('input', {
      type: 'checkbox',
      class: 'switch-input',
      checked: entry.enabled !== false,
    });
    const startField = field({ name: 'start_' + index, label: '开始', type: 'time', value: hhmm(entry.start) });
    const endField = field({ name: 'end_' + index, label: '结束', type: 'time', value: hhmm(entry.end) });
    const item = el(
      'div',
      { class: 'schedule-item' + (entry.enabled === false ? ' schedule-item--off' : '') },
      el(
        'div',
        { class: 'schedule-item-head' },
        el('span', { class: 'schedule-item-name', text: entry.label || entry.key || '提醒 ' + (index + 1) }),
        el(
          'label',
          { class: 'switch' },
          toggle,
          el('span', { class: 'switch-track' }, el('span', { class: 'switch-thumb' })),
          el('span', { class: 'switch-text', text: '启用' })
        )
      ),
      el('div', { class: 'field-grid' }, startField, endField)
    );

    const applyDisabled = () => {
      const on = !!toggle.checked;
      item.classList.toggle('schedule-item--off', !on);
      startField.input.disabled = !on;
      endField.input.disabled = !on;
    };
    toggle.addEventListener('change', applyDisabled);
    applyDisabled();

    return { entry, toggle, startField, endField, item };
  }

  function render() {
    clear(host);
    rows = [];
    if (!entries.length) {
      save.disabled = true;
      host.appendChild(
        emptyState({
          icon: 'bell',
          title: '还没有读到提醒项',
          hint: '设置还没同步下来，重新读取一次再编辑。',
          actionLabel: '重新读取',
          onAction: () => {
            loadSettings().then(() => {
              entries = readEntries();
              render();
            });
          },
        })
      );
      return;
    }
    entries.forEach((entry, index) => {
      const row = itemNode(entry, index);
      rows.push(row);
      host.appendChild(row.item);
    });
    save.disabled = false;
  }

  function submit() {
    const timeFields = [];
    for (const row of rows) timeFields.push(row.startField, row.endField);
    const result = readFields(timeFields);
    let firstBad = result.firstInvalid;
    const next = [];

    for (const row of rows) {
      row.startField.setError('');
      row.endField.setError('');
      const enabled = !!row.toggle.checked;
      const start = result.values[row.startField.input.name] || '';
      const end = result.values[row.endField.input.name] || '';
      if (enabled && !start) {
        row.startField.setError('启用后要填写开始时间');
        firstBad = firstBad || row.startField.input;
      }
      if (enabled && !end) {
        row.endField.setError('启用后要填写结束时间');
        firstBad = firstBad || row.endField.input;
      }
      if (enabled && start && end && start >= end) {
        row.endField.setError('结束时间要晚于开始时间');
        firstBad = firstBad || row.endField.input;
      }
      next.push(Object.assign({}, row.entry, { enabled: enabled, start: start, end: end }));
    }

    if (firstBad) {
      firstBad.focus();
      sheet.setError('请检查标红的时间项');
      return;
    }
    saveTreatment({ schedule: next }, sheet, save);
  }

  entries = readEntries();
  render();
  return sheet;
}

/** 目标：目标体重 + 每日营养目标。数值范围在本地校验，绝不把胡填的数字发出去。 */
function targetSheet() {
  const targets = state.targets || {};

  const weightField = field({
    name: 'target_weight_kg',
    label: '目标体重',
    type: 'number',
    unit: 'kg',
    required: true,
    min: 20,
    max: 400,
    step: 0.1,
    value: currentGoalWeight(),
    hint: '范围 20–400 kg',
  });
  const caloriesField = field({
    name: 'calories',
    label: '卡路里',
    type: 'number',
    unit: 'kcal',
    required: true,
    min: 600,
    max: 6000,
    step: 10,
    value: firstNum(targets.calories, 1600),
    hint: '范围 600–6000 kcal',
  });
  const proteinField = field({
    name: 'protein_g',
    label: '蛋白质',
    type: 'number',
    unit: 'g',
    required: true,
    min: 20,
    max: 400,
    step: 1,
    value: firstNum(targets.protein_g, targets.protein, 90),
    hint: '范围 20–400 g',
  });
  const carbField = field({
    name: 'carbs_g',
    label: '碳水',
    type: 'number',
    unit: 'g',
    required: true,
    min: 20,
    max: 800,
    step: 1,
    value: firstNum(targets.carbs_g, targets.carb, 180),
    hint: '范围 20–800 g',
  });
  const fatField = field({
    name: 'fat_g',
    label: '脂肪',
    type: 'number',
    unit: 'g',
    required: true,
    min: 10,
    max: 300,
    step: 1,
    value: firstNum(targets.fat_g, targets.fat, 55),
    hint: '范围 10–300 g',
  });
  const fiberField = field({
    name: 'fiber_g',
    label: '纤维',
    type: 'number',
    unit: 'g',
    required: true,
    min: 5,
    max: 100,
    step: 1,
    value: firstNum(targets.fiber_g, targets.fiber, 25),
    hint: '范围 5–100 g',
  });
  const waterField = field({
    name: 'water_l',
    label: '饮水',
    type: 'number',
    unit: '升',
    required: true,
    min: 0.5,
    max: 10,
    step: 0.1,
    value: currentWaterLiters(),
    hint: '范围 0.5–10 升，保存时会换算成毫升',
  });

  const fields = [weightField, caloriesField, proteinField, carbField, fatField, fiberField, waterField];
  const checks = [
    [weightField, '目标体重', 20, 400, ' kg'],
    [caloriesField, '卡路里', 600, 6000, ' kcal'],
    [proteinField, '蛋白质', 20, 400, ' g'],
    [carbField, '碳水', 20, 800, ' g'],
    [fatField, '脂肪', 10, 300, ' g'],
    [fiberField, '纤维', 5, 100, ' g'],
    [waterField, '饮水', 0.5, 10, ' 升'],
  ];

  const save = button({
    label: '保存',
    kind: 'primary',
    block: true,
    onClick: () => submit(),
  });

  const sheet = openSheet({
    title: '目标',
    subtitle: '改完会在首页进度和图表里立刻生效',
    body: [
      el('div', { class: 'form' }, fields),
      el('p', {
        class: 'hint-line',
        text: '目标体重存在个人档案里；饮水按「升」填写，保存时换算成毫升。',
      }),
    ],
    actions: [save],
  });

  function submit() {
    const result = readFields(fields);
    let firstBad = result.firstInvalid;
    for (const spec of checks) {
      if (!checkRange(spec[0], spec[1], spec[2], spec[3], spec[4])) {
        firstBad = firstBad || spec[0].input;
      }
    }
    if (firstBad) {
      firstBad.focus();
      sheet.setError('有数值超出允许范围，请检查标红的项目');
      return;
    }
    const values = result.values;
    saveTreatment(
      {
        profile: {
          target_weight_kg: values.target_weight_kg,
          water_target_l: values.water_l,
        },
        targets: {
          calories: values.calories,
          protein_g: values.protein_g,
          carbs_g: values.carbs_g,
          fat_g: values.fat_g,
          fiber_g: values.fiber_g,
          water_ml: Math.round(values.water_l * 1000),
        },
      },
      sheet,
      save
    );
  }

  return sheet;
}

function treatmentRows() {
  const profile = state.profile || {};
  const dose = state.nextDose || {};
  const targets = state.targets || {};

  const doseMg = firstNum(dose.dose_mg, profile.dose_mg, null);
  const doseDays = toNum(dose.interval_days, toNum(profile.dose_interval_days, 7));

  const rows = [
    listRow({
      icon: 'dose',
      toneColor: '#E8A33D',
      title: '药物与剂量',
      subtitle: [
        dose.drug || dose.med_name || profile.med_name || '穆峰达',
        doseMg === null ? '' : fmtNum(doseMg) + ' mg',
        intervalText(doseDays),
      ].filter(Boolean).join(' · '),
      chevron: true,
      onClick: () => drugSheet(),
    }),
    listRow({
      icon: 'bell',
      toneColor: '#35C6E8',
      title: '提醒',
      subtitle: reminderSubtitle(),
      chevron: true,
      onClick: () => reminderSheet(),
    }),
    listRow({
      icon: 'weight',
      toneColor: '#3DBFA0',
      title: '目标',
      subtitle: '目标体重 ' + fmtNum(currentGoalWeight()) + ' kg · 每日 '
        + fmtNum(firstNum(targets.calories, 1600)) + ' kcal · 饮水 '
        + fmtNum(currentWaterLiters()) + ' 升',
      chevron: true,
      onClick: () => targetSheet(),
    }),
    listRow({
      icon: 'health',
      toneColor: '#8B7BD8',
      title: '健康数据',
      subtitle: '体重 · 体脂 · 血压 · 化验',
      chevron: true,
      onClick: () => infoSheet({
        title: '健康数据',
        rows: [
          ['已启用类型', String((state.quickTypes || []).length || 15) + ' 种'],
          ['存储位置', '本地 SQLite'],
          ['对外发送', '仅在你提问时发送给配置的模型服务'],
        ],
        note: '所有记录都保存在你自己的服务器上，不会被上传到第三方。',
      }),
    }),
    listRow({
      icon: 'data',
      toneColor: '#35C6E8',
      title: '疗效参考（厂商说明书）',
      subtitle: '注册试验的人群平均结果 · 带原文出处',
      chevron: true,
      onClick: () => efficacySheet(),
    }),
  ];

  return el('section', { class: 'section' }, sectionHead('治疗'), card({ flush: true }, el('div', { class: 'list' }, rows)));
}

function infoSheet(opts) {
  const o = opts || {};
  const body = el('div', { class: 'kv-list' });
  for (const [key, value] of o.rows || []) {
    body.appendChild(kvRow(key, value));
  }
  if (o.note) body.appendChild(el('p', { class: 'hint-line', text: o.note }));
  return openSheet({ title: o.title || '', body: [body] });
}

/* ------------------------------------------------------------------ *
 * rating card
 * ------------------------------------------------------------------ */

function ratingCard() {
  const stars = el('div', { class: 'stars', role: 'img', 'aria-label': '五星好评' });
  for (let i = 0; i < 5; i += 1) {
    stars.appendChild(el('span', { class: 'star' }, icon('star-filled', { size: 18 })));
  }
  return card(
    { class: 'card--rate' },
    el('p', { class: 'rate-title', text: '喜欢 GLP Mate Lite 吗?' }),
    el('p', { class: 'rate-sub', text: '这是一份自托管的开源实现，你的反馈会直接变成下一个版本。' }),
    stars,
    button({
      label: '告诉我们',
      kind: 'ghost',
      block: true,
      onClick: () => toast('谢谢！把想法写在教练里也能被记录', 'success'),
    })
  );
}

/* ------------------------------------------------------------------ *
 * AI settings (inline form)
 * ------------------------------------------------------------------ */

let aiExpanded = false;

function aiForm() {
  const settings = state.settings || {};
  const keyField = field({
    name: 'ai_api_key',
    label: 'API Key',
    type: 'password',
    value: '',
    autocomplete: 'new-password',
    placeholder: settings.ai_api_key || (settings.ai_api_key_set ? '已保存（留空表示不修改）' : 'sk-…'),
    hint: '密钥只保存在你自己的 SQLite 数据库里，不会离开这台服务器。当前：'
      + (settings.ai_api_key_set ? settings.ai_api_key || '已设置' : '未设置'),
  });
  const baseField = field({
    name: 'ai_base_url',
    label: 'Base URL',
    type: 'text',
    value: settings.ai_base_url || '',
    placeholder: 'https://api.deepseek.com',
  });
  const modelField = field({
    name: 'ai_model',
    label: '模型',
    type: 'text',
    value: settings.ai_model || '',
    placeholder: 'deepseek-chat',
  });
  const visionBaseField = field({
    name: 'vision_base_url',
    label: '视觉 Base URL',
    type: 'text',
    value: settings.vision_base_url || '',
    placeholder: '留空则使用同一个服务',
  });
  const visionModelField = field({
    name: 'vision_model',
    label: '视觉模型',
    type: 'text',
    value: settings.vision_model || '',
    placeholder: '例如：deepseek-vl',
    hint: '用于照片估算卡路里；留空则关闭照片识别。',
  });
  const quotaField = field({
    name: 'daily_quota',
    label: '每日消息额度',
    type: 'number',
    unit: '条',
    min: 1,
    max: 1000,
    step: 1,
    value: settings.daily_quota === undefined ? 10 : settings.daily_quota,
    hint: '达到额度后教练会暂停回答，记录功能不受影响。',
  });
  const tempField = field({
    name: 'temperature',
    label: 'Temperature',
    type: 'number',
    min: 0,
    max: 2,
    step: 0.1,
    value: settings.temperature === undefined ? 0.6 : settings.temperature,
  });

  const fields = [baseField, keyField, modelField, visionBaseField, visionModelField, quotaField, tempField];
  const testResult = el('p', { class: 'test-result', role: 'status', hidden: true });

  const validateUrl = (wrap, allowEmpty) => {
    const value = wrap.input.value.trim();
    if (!value) {
      if (allowEmpty) return true;
      wrap.setError('请填写服务地址');
      return false;
    }
    if (!/^https?:\/\/[^\s]+$/i.test(value)) {
      wrap.setError('地址需要以 http:// 或 https:// 开头');
      return false;
    }
    return true;
  };

  const save = button({
    label: '保存设置',
    kind: 'primary',
    block: true,
    onClick: () => {
      const result = readFields(fields);
      baseField.setError('');
      visionBaseField.setError('');
      const baseOk = validateUrl(baseField, false);
      const visionOk = validateUrl(visionBaseField, true);
      if (result.firstInvalid || !baseOk || !visionOk) {
        if (result.firstInvalid) result.firstInvalid.focus();
        return;
      }
      const payload = {
        ai_base_url: result.values.ai_base_url,
        ai_model: result.values.ai_model,
        vision_base_url: result.values.vision_base_url || '',
        vision_model: result.values.vision_model || '',
        daily_quota: result.values.daily_quota,
        temperature: result.values.temperature,
      };
      if (result.values.ai_api_key) payload.ai_api_key = result.values.ai_api_key;

      save.disabled = true;
      api
        .putSettings(payload)
        .then((updated) => {
          const norm = unwrapSettings(updated);
          set({
            settings: Object.assign({}, state.settings, norm.settings, payload),
            profile: norm.profile ? Object.assign({}, state.profile, norm.profile) : state.profile,
            targets: norm.targets ? Object.assign({}, state.targets, norm.targets) : state.targets,
            coachQuota: Object.assign({}, state.coachQuota, { limit: payload.daily_quota }),
          });
          keyField.input.value = '';
          toast('设置已保存', 'success');
        })
        .catch((err) => toast(err && err.message ? err.message : '保存失败', 'error'))
        .then(() => {
          save.disabled = false;
        });
    },
  });

  const test = button({
    label: '测试连接',
    kind: 'ghost',
    block: true,
    onClick: () => {
      test.disabled = true;
      testResult.hidden = false;
      testResult.textContent = '正在测试…';
      testResult.className = 'test-result';
      api
        .testSettings()
        .then((payload) => {
          if (payload && payload.ok) {
            testResult.textContent = '连接正常 · ' + (payload.model || '') + ' · ' + (payload.latency_ms || 0) + ' ms';
            testResult.className = 'test-result test-result--ok';
          } else {
            testResult.textContent = '测试失败：' + ((payload && payload.error) || '未知错误');
            testResult.className = 'test-result test-result--bad';
          }
        })
        .catch((err) => {
          testResult.textContent = '测试失败：' + (err && err.message ? err.message : '未知错误');
          testResult.className = 'test-result test-result--bad';
        })
        .then(() => {
          test.disabled = false;
        });
    },
  });

  return card(
    { class: 'card--ai', id: 'ai-settings' },
    cardHead({
      title: '模型与额度',
      subtitle: '自托管版本：填你自己的模型服务，不涉及订阅',
      icon: 'sparkle',
    }),
    el('div', { class: 'form' }, fields),
    el('div', { class: 'form-actions' }, save, test),
    testResult
  );
}

/* ------------------------------------------------------------------ *
 * data / app group
 * ------------------------------------------------------------------ */

function exportData() {
  api
    .exportAll()
    .then((payload) => {
      const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const link = el('a', {
        href: url,
        download: 'glpmate-lite-' + (state.today || 'export') + '.json',
        class: 'visually-hidden',
      });
      document.body.appendChild(link);
      link.click();
      window.setTimeout(() => {
        URL.revokeObjectURL(url);
        if (link.parentNode) link.parentNode.removeChild(link);
      }, 1000);
      toast('已导出 JSON 备份', 'success');
    })
    .catch(() => {});
}

function importData() {
  const input = el('input', {
    class: 'visually-hidden',
    type: 'file',
    accept: 'application/json,.json',
    'aria-hidden': 'true',
    tabindex: '-1',
    onChange: (event) => {
      const file = event.target.files && event.target.files[0];
      event.target.value = '';
      if (!file) return;
      const reader = new FileReader();
      reader.onerror = () => toast('读取文件失败', 'error');
      reader.onload = () => {
        let parsed = null;
        try {
          parsed = JSON.parse(String(reader.result || ''));
        } catch (err) {
          toast('不是有效的 JSON 文件', 'error');
          return;
        }
        confirmDialog({
          title: '导入这份备份？',
          message: '导入会把这些记录追加到现有数据里，不会覆盖。',
          confirmLabel: '开始导入',
          danger: false,
        }).then((ok) => {
          if (!ok) return;
          api
            .importAll(parsed)
            .then((result) => {
              const count = result && result.imported ? result.imported.records : 0;
              toast('已导入 ' + (count || 0) + ' 条记录', 'success');
              loadBootstrap(true).catch(() => {});
              loadSettings();
            })
            .catch(() => {});
        });
      };
      reader.readAsText(file);
    },
  });
  document.body.appendChild(input);
  input.click();
  window.setTimeout(() => {
    if (input.parentNode) input.parentNode.removeChild(input);
  }, 1000);
}

function deleteAll() {
  confirmDialog({
    title: '删除全部数据？',
    message: '会逐条删除服务器上的所有记录，无法撤销。建议先导出备份。',
    confirmLabel: '我确认删除',
  }).then((ok) => {
    if (!ok) return;
    toast('正在删除…', 'info');
    api
      .wipe({ silent: true })
      .then((result) => {
        if (result && result.ok) {
          toast('已删除全部记录', 'success');
          loadBootstrap(true).catch(() => {});
          return null;
        }
        return deleteRecordByRecord();
      })
      .catch(() => deleteRecordByRecord());
  });
}

/** Fallback for backends that do not expose POST /api/wipe. */
function deleteRecordByRecord() {
  return api
      .records({ start: '1970-01-01', end: '2999-12-31' }, { silent: true })
      .then((payload) => {
        const items = (payload && payload.items) || [];
        if (!items.length) {
          toast('没有可删除的记录', 'info');
          return null;
        }
        return items.reduce(
          (chain, record) => chain.then(() => api.deleteRecord(record.id, { silent: true }).catch(() => null)),
          Promise.resolve()
        ).then(() => items.length);
      })
      .then((count) => {
        if (count) {
          toast('已删除 ' + count + ' 条记录', 'success');
          loadBootstrap(true).catch(() => {});
        }
      })
      .catch((err) => toast(err && err.message ? err.message : '删除失败', 'error'));
}

function languageSheet() {
  return openSheet({
    title: '语言',
    subtitle: '界面语言',
    body: [
      el(
        'div',
        { class: 'list' },
        listRow({
          title: '简体中文',
          subtitle: '当前语言',
          trailing: el('span', { class: 'row-check' }, icon('check', { size: 16 })),
        }),
        listRow({
          title: 'English',
          subtitle: '暂不支持',
          class: 'row--disabled',
        })
      ),
    ],
  });
}

function version() {
  return (state.appMeta && state.appMeta.version) || APP_VERSION;
}

function license() {
  return (state.appMeta && state.appMeta.license) || LICENSE;
}

/**
 * 注册试验疗效参考。
 *
 * 这里只展示"说明书/注册试验里人群平均是多少"，不参与任何计算、不做预测。
 * 未核验的数据后端会下发 null，界面直接显示"—"，绝不猜一个数字填上。
 */
function efficacySheet() {
  const body = el('div', { class: 'efficacy-body' });
  body.appendChild(el('p', { class: 'hint-line', text: '正在读取说明书数据…' }));
  const sheet = openSheet({ title: '疗效参考（说明书数据）', subtitle: '人群平均结果 · 不是对你的预测', body: [body] });

  api.efficacy()
    .then((data) => {
      clear(body);
      if (data && data.disclaimer) {
        body.appendChild(el('div', { class: 'note-wrap' }, infoNote(data.disclaimer)));
      }
      const indications = (data && data.indications) || [];
      if (!indications.length) {
        body.appendChild(emptyState({ title: '暂无已核验数据', text: '这款药的数据还没逐条核对，宁可不显示。' }));
        return;
      }
      for (const indication of indications) {
        body.appendChild(el('h3', { class: 'efficacy-title', text: indication.label || '' }));
        const meta = [
          indication.trial ? '试验 ' + indication.trial : '',
          indication.duration_weeks ? indication.duration_weeks + ' 周' : '',
          indication.population || '',
        ].filter(Boolean).join(' · ');
        body.appendChild(el('p', { class: 'efficacy-meta', text: meta }));

        const rows = [
          ['', '体重变化', '≥5% 减重', '≥10%', '≥15%', '≥20%'],
        ];
        for (const arm of indication.arms || []) {
          const pct = (v) => (v === null || v === undefined ? '—' : fmtNum(v, 1) + '%');
          rows.push([
            arm.arm + (arm.n ? '（n=' + arm.n + '）' : ''),
            pct(arm.weight_change_pct),
            pct(arm.ge5_pct),
            pct(arm.ge10_pct),
            pct(arm.ge15_pct),
            pct(arm.ge20_pct),
          ]);
        }
        const table = el('table', { class: 'efficacy-table' });
        rows.forEach((cells, index) => {
          const tr = el('tr', { class: index === 0 ? 'efficacy-head' : '' });
          cells.forEach((text, cellIndex) => {
            const tag = index === 0 || cellIndex === 0 ? 'th' : 'td';
            tr.appendChild(el(tag, { text: String(text) }));
          });
          table.appendChild(tr);
        });
        body.appendChild(el('div', { class: 'table-wrap' }, table));
        if (!indication.verified) {
          body.appendChild(el('p', { class: 'hint-line', text: '⚠︎ 该条目尚未完成核验，数字一律留空。' }));
        }
        for (const src of indication.sources || []) {
          body.appendChild(el('p', {
            class: 'efficacy-src',
            text: '来源（' + (src.section || '说明书') + '）：' + (src.quote || src.claim || ''),
          }));
        }
      }

      const gi = (data && data.gi_side_effects) || {};
      if ((gi.arms || []).length) {
        body.appendChild(el('h3', { class: 'efficacy-title', text: '常见胃肠道不良反应（发生率 %）' }));
        if (gi.trial) body.appendChild(el('p', { class: 'efficacy-meta', text: gi.trial }));
        const rows = [['', '恶心', '腹泻', '呕吐', '便秘', '任意 GI', '因 GI 停药']];
        for (const arm of gi.arms) {
          const pct = (v) => (v === null || v === undefined ? '—' : fmtNum(v, 1) + '%');
          rows.push([arm.arm, pct(arm.nausea_pct), pct(arm.diarrhoea_pct), pct(arm.vomiting_pct),
            pct(arm.constipation_pct), pct(arm.any_gi_pct), pct(arm.discontinued_due_to_gi_pct)]);
        }
        const table = el('table', { class: 'efficacy-table' });
        rows.forEach((cells, index) => {
          const tr = el('tr', { class: index === 0 ? 'efficacy-head' : '' });
          cells.forEach((text, cellIndex) => {
            tr.appendChild(el(index === 0 || cellIndex === 0 ? 'th' : 'td', { text: String(text) }));
          });
          table.appendChild(tr);
        });
        body.appendChild(el('div', { class: 'table-wrap' }, table));
        for (const src of gi.sources || []) {
          body.appendChild(el('p', {
            class: 'efficacy-src',
            text: '来源（' + (src.section || '说明书') + '）：' + (src.quote || src.claim || ''),
          }));
        }
      }

      if (data && data.cross_trial_caveat) {
        body.appendChild(el('p', { class: 'hint-line', text: data.cross_trial_caveat }));
      }
      if (data && data.verified_on) {
        body.appendChild(el('p', { class: 'efficacy-meta', text: '数据核验日期：' + data.verified_on }));
      }
    })
    .catch((err) => {
      clear(body);
      body.appendChild(errorState({
        message: (err && err.message) || '读取失败',
        onRetry: () => { sheet.close(); efficacySheet(); },
      }));
    });
  return sheet;
}

function aboutSheet() {
  return openSheet({
    title: '关于 GLP Mate Lite',
    subtitle: '自托管的 GLP-1 记录工具',
    body: [
      el(
        'div',
        { class: 'kv-list' },
        el('div', { class: 'kv-row' }, el('span', { class: 'kv-key', text: '版本' }), el('span', { class: 'kv-value', text: version() })),
        el('div', { class: 'kv-row' }, el('span', { class: 'kv-key', text: '许可证' }), el('span', { class: 'kv-value', text: license() })),
        el('div', { class: 'kv-row' }, el('span', { class: 'kv-key', text: '数据存储' }), el('span', { class: 'kv-value', text: '本地 SQLite' })),
        el('div', { class: 'kv-row' }, el('span', { class: 'kv-key', text: '前端' }), el('span', { class: 'kv-value', text: '原生 ES 模块 · 无构建步骤' }))
      ),
      el('p', { class: 'hint-line', text: '本应用提供的是记录与估算工具，不构成医疗建议。' }),
    ],
  });
}

function appRows() {
  const rows = [
    listRow({
      icon: 'sparkle',
      toneColor: '#35C6E8',
      title: '模型与额度',
      subtitle: state.settings && state.settings.ai_model
        ? state.settings.ai_model + ' · 每日 ' + fmtNum(state.settings.daily_quota || 10) + ' 条'
        : '配置模型服务与每日额度',
      chevron: true,
      onClick: () => {
        aiExpanded = true;
        paintSettings();
        const node = document.getElementById('ai-settings');
        if (node && node.scrollIntoView) node.scrollIntoView({ behavior: 'smooth', block: 'start' });
      },
    }),
    listRow({
      icon: 'download',
      toneColor: '#3DBFA0',
      title: '数据导出',
      subtitle: '下载完整 JSON 备份',
      chevron: true,
      onClick: exportData,
    }),
    listRow({
      icon: 'upload',
      toneColor: '#4A9BE8',
      title: '数据导入',
      subtitle: '从 JSON 备份恢复记录',
      chevron: true,
      onClick: importData,
    }),
    listRow({
      icon: 'trash',
      toneColor: '#E8607A',
      title: '删除全部数据',
      subtitle: '不可撤销，会逐条删除记录',
      chevron: true,
      onClick: deleteAll,
    }),
    listRow({
      icon: 'info',
      toneColor: '#9AA5AD',
      title: '语言',
      subtitle: '简体中文',
      chevron: true,
      onClick: languageSheet,
    }),
    listRow({
      icon: 'file-text',
      toneColor: '#9AA5AD',
      title: '关于',
      subtitle: license() + ' · v' + version(),
      chevron: true,
      onClick: aboutSheet,
    }),
  ];
  return el('section', { class: 'section' }, sectionHead('应用'), card({ flush: true }, el('div', { class: 'list' }, rows)));
}

/* ------------------------------------------------------------------ *
 * view
 * ------------------------------------------------------------------ */

let repaint = () => {};

function paintSettings() {
  repaint();
}

export function render(container, ctx) {
  const root = el('div', { class: 'view view--settings' });

  function paint() {
    clear(root);
    root.appendChild(el('header', { class: 'page-head' }, el('h1', { class: 'page-title', text: '设置' })));
    if (!state.ready) {
      root.appendChild(state.error
        ? errorState({ message: state.error, onRetry: () => reloadBootstrap() })
        : skeleton(5));
      return;
    }
    root.appendChild(profileCard());
    root.appendChild(treatmentRows());
    root.appendChild(ratingCard());
    root.appendChild(appRows());
    if (aiExpanded) root.appendChild(aiForm());
    root.appendChild(
      el('p', { class: 'app-foot', text: 'GLP Mate Lite · ' + license() + ' · v' + version() + ' · 数据保存在你自己的服务器上' })
    );
  }

  repaint = paint;
  paint();
  container.appendChild(root);
  if (!state.settings || !state.settings.ai_model) loadSettings();

  const focus = ctx && ctx.params ? ctx.params.focus : '';
  if (focus === 'quota') {
    aiExpanded = true;
    paint();
    const node = root.querySelector('#ai-settings');
    if (node && node.scrollIntoView) node.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  const disconnect = connect(['ready', 'error', 'settings', 'profile', 'targets', 'quickTypes', 'nextDose', 'coachQuota'], paint);

  return () => {
    disconnect();
    repaint = () => {};
    clear(root);
  };
}

export default { render };
