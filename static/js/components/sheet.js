/**
 * components/sheet.js — bottom sheets, confirm dialog and the record form.
 *
 * Everything is created with DOM nodes; validation errors are rendered inline
 * (never as alerts) and destructive actions always ask for confirmation.
 */

import { el } from '../dom.js';
import { icon } from '../icons.js';
import { button } from './card.js';
import { typeMeta, tint } from './list.js';
import { state } from '../store.js';

let openCount = 0;

function lockScroll(lock) {
  openCount = Math.max(0, openCount + (lock ? 1 : -1));
  document.body.classList.toggle('is-locked', openCount > 0);
}

/**
 * Open a bottom sheet.
 * @param {{title:string, subtitle?:string, body?:Node|Node[], actions?:Node[],
 *          onClose?:Function, dismissible?:boolean, labelledBy?:string}} opts
 * @returns {{root:HTMLElement, close:Function, body:HTMLElement, foot:HTMLElement, setError:Function}}
 */
export function openSheet(opts) {
  const o = opts || {};
  const titleId = 'sheet-title-' + Math.random().toString(36).slice(2, 8);
  const body = el('div', { class: 'sheet-body' });
  if (o.body) {
    if (Array.isArray(o.body)) for (const node of o.body) body.appendChild(node);
    else body.appendChild(o.body);
  }
  const errorLine = el('p', { class: 'sheet-error', role: 'alert', hidden: true });
  const foot = el('div', { class: 'sheet-foot' });
  if (o.actions) for (const node of o.actions) foot.appendChild(node);

  const closeBtn = el('button', {
    class: 'sheet-close',
    type: 'button',
    'aria-label': '关闭',
    onClick: () => close(),
  }, icon('close', { size: 18 }));

  const panel = el(
    'div',
    {
      class: 'sheet',
      role: 'dialog',
      'aria-modal': 'true',
      'aria-labelledby': titleId,
    },
    el('div', { class: 'sheet-handle' }),
    el(
      'header',
      { class: 'sheet-head' },
      el(
        'div',
        { class: 'sheet-head-text' },
        el('h2', { class: 'sheet-title', id: titleId, text: o.title || '' }),
        o.subtitle ? el('p', { class: 'sheet-sub', text: o.subtitle }) : null
      ),
      closeBtn
    ),
    errorLine,
    body,
    foot.childNodes.length ? foot : null
  );

  const backdrop = el('div', { class: 'sheet-backdrop' });
  const root = el('div', { class: 'sheet-root' }, backdrop, panel);
  const previousFocus = document.activeElement;

  function onKey(event) {
    if (event.key === 'Escape' && o.dismissible !== false) {
      event.preventDefault();
      close();
      return;
    }
    if (event.key === 'Tab') {
      const focusable = panel.querySelectorAll(
        'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
      );
      if (!focusable.length) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }
  }

  let closed = false;
  function close() {
    if (closed) return;
    closed = true;
    document.removeEventListener('keydown', onKey, true);
    lockScroll(false);
    root.classList.add('sheet-root--closing');
    window.setTimeout(() => {
      if (root.parentNode) root.parentNode.removeChild(root);
      if (previousFocus && typeof previousFocus.focus === 'function') previousFocus.focus();
      if (o.onClose) o.onClose();
    }, 160);
  }

  backdrop.addEventListener('click', () => {
    if (o.dismissible !== false) close();
  });
  document.addEventListener('keydown', onKey, true);

  document.body.appendChild(root);
  lockScroll(true);
  window.requestAnimationFrame(() => {
    root.classList.add('is-open');
    const firstField = panel.querySelector('input, select, textarea, button:not(.sheet-close)');
    if (firstField && typeof firstField.focus === 'function') firstField.focus();
  });

  return {
    root: root,
    body: body,
    foot: foot,
    close: close,
    setError(message) {
      errorLine.textContent = message || '';
      errorLine.hidden = !message;
    },
  };
}

/**
 * Yes/no dialog. Resolves true when confirmed.
 * @param {{title:string, message?:string, confirmLabel?:string,
 *          cancelLabel?:string, danger?:boolean}} opts
 * @returns {Promise<boolean>}
 */
export function confirmDialog(opts) {
  const o = opts || {};
  return new Promise((resolve) => {
    let answered = false;
    const sheet = openSheet({
      title: o.title || '确认操作',
      subtitle: o.message,
      dismissible: true,
      onClose: () => {
        if (!answered) {
          answered = true;
          resolve(false);
        }
      },
      actions: [
        button({
          label: o.cancelLabel || '取消',
          kind: 'ghost',
          block: true,
          onClick: () => sheet.close(),
        }),
        button({
          label: o.confirmLabel || '确认',
          kind: o.danger === false ? 'primary' : 'danger',
          block: true,
          onClick: () => {
            answered = true;
            resolve(true);
            sheet.close();
          },
        }),
      ],
    });
  });
}

/* ------------------------------------------------------------------ *
 * form fields
 * ------------------------------------------------------------------ */

/**
 * @param {{name:string, label?:string, type?:string, value?:any, unit?:string,
 *          required?:boolean, min?:number, max?:number, step?:number,
 *          placeholder?:string, hint?:string, options?:Array<string|{value:string,label:string}>,
 *          autocomplete?:string, inputmode?:string}} spec
 */
export function field(spec) {
  const s = spec || {};
  const id = 'f-' + (s.name || Math.random().toString(36).slice(2, 8));
  const errorId = id + '-err';
  const type = s.type || 'text';

  let input;
  if (type === 'select') {
    input = el('select', { class: 'input', id: id, name: s.name, 'aria-describedby': errorId });
    for (const option of s.options || []) {
      const value = typeof option === 'string' ? option : option.value;
      const label = typeof option === 'string' ? option : option.label;
      input.appendChild(
        el('option', { value: value, text: label, selected: String(s.value) === String(value) ? true : null })
      );
    }
  } else if (type === 'textarea') {
    input = el('textarea', {
      class: 'input input--area',
      id: id,
      name: s.name,
      rows: s.rows || 3,
      placeholder: s.placeholder || '',
      'aria-describedby': errorId,
    });
    input.value = s.value === undefined || s.value === null ? '' : String(s.value);
  } else {
    input = el('input', {
      class: 'input',
      id: id,
      name: s.name,
      type: type,
      value: s.value === undefined || s.value === null ? '' : String(s.value),
      placeholder: s.placeholder || '',
      min: s.min === undefined ? null : String(s.min),
      max: s.max === undefined ? null : String(s.max),
      step: s.step === undefined ? null : String(s.step),
      inputmode: s.inputmode || (type === 'number' ? 'decimal' : null),
      autocomplete: s.autocomplete || 'off',
      'aria-describedby': errorId,
    });
  }
  if (s.required) input.setAttribute('required', '');

  const error = el('p', { class: 'field-error', id: errorId, role: 'alert', hidden: true });
  const wrap = el(
    'div',
    { class: 'field field--' + type },
    el(
      'label',
      { class: 'field-label', for: id },
      el('span', { text: s.label || s.name }),
      s.unit ? el('span', { class: 'field-unit', text: s.unit }) : null
    ),
    input,
    s.hint ? el('p', { class: 'field-hint', text: s.hint }) : null,
    error
  );

  wrap.input = input;
  wrap.setError = (message) => {
    error.textContent = message || '';
    error.hidden = !message;
    input.classList.toggle('input--invalid', !!message);
    input.setAttribute('aria-invalid', message ? 'true' : 'false');
  };
  return wrap;
}

/** Read + validate a list of field wrappers. */
export function readFields(fields) {
  const values = {};
  let firstInvalid = null;
  for (const wrap of fields) {
    const input = wrap.input;
    const name = input.name;
    let raw = input.value;
    if (typeof raw === 'string') raw = raw.trim();
    wrap.setError('');

    if (input.hasAttribute('required') && !raw) {
      wrap.setError('这一项不能为空');
      if (!firstInvalid) firstInvalid = input;
      continue;
    }
    if (input.type === 'number' && raw !== '') {
      const num = Number(raw);
      if (!Number.isFinite(num)) {
        wrap.setError('请输入数字');
        if (!firstInvalid) firstInvalid = input;
        continue;
      }
      const min = input.getAttribute('min');
      const max = input.getAttribute('max');
      if (min !== null && num < Number(min)) {
        wrap.setError('不能小于 ' + min);
        if (!firstInvalid) firstInvalid = input;
        continue;
      }
      if (max !== null && num > Number(max)) {
        wrap.setError('不能大于 ' + max);
        if (!firstInvalid) firstInvalid = input;
        continue;
      }
      values[name] = num;
      continue;
    }
    values[name] = raw === '' ? null : raw;
  }
  return { values: values, firstInvalid: firstInvalid };
}

/* ------------------------------------------------------------------ *
 * record sheet
 * ------------------------------------------------------------------ */

const MEALS = [
  { value: 'breakfast', label: '早餐' },
  { value: 'lunch', label: '午餐' },
  { value: 'dinner', label: '晚餐' },
  { value: 'snack', label: '加餐' },
];

const SYMPTOMS = ['恶心', '便秘', '腹泻', '疲劳', '头痛', '腹胀', '反酸', '注射部位反应'];

const MEASURE_TYPES = {
  bp: { label: '血压', unit: 'mmHg', placeholder: '例如：120/80' },
  glucose: { label: '血糖', unit: 'mmol/L', placeholder: '例如：5.4' },
  hba1c: { label: '糖化血红蛋白', unit: '%', placeholder: '例如：5.4' },
  lipid: { label: '血脂', unit: 'mmol/L', placeholder: '例如：4.2' },
  liver: { label: '肝功能', unit: 'U/L', placeholder: '例如：ALT 24' },
  ketone: { label: '酮体', unit: 'mmol/L', placeholder: '例如：0.3' },
  vital: { label: '体征', unit: '', placeholder: '' },
  measure: { label: '测量', unit: '', placeholder: '' },
};

function toNum(value) {
  const n = typeof value === 'number' ? value : parseFloat(value);
  return Number.isFinite(n) ? n : null;
}

/**
 * Fields for a record type.  `ctx.profile` supplies defaults such as the
 * current medication name.
 */
export function recordFields(type, record, ctx) {
  const r = record || {};
  const meta = r.meta || {};
  const profile = (ctx && ctx.profile) || {};
  const at = typeof r.at === 'string' && r.at.length >= 16 ? r.at.slice(0, 16) : '';
  const common = [
    field({ name: 'at', label: '时间', type: 'datetime-local', value: at }),
    field({ name: 'note', label: '备注', type: 'text', value: meta.note || '', placeholder: '可选' }),
  ];

  switch (type) {
    case 'weight':
      return [
        field({ name: 'value', label: '体重', type: 'number', unit: 'kg', required: true, min: 20, max: 400, step: 0.1, value: r.value }),
        ...common,
      ];
    case 'waist':
      return [
        field({ name: 'value', label: '腰围', type: 'number', unit: 'cm', required: true, min: 30, max: 300, step: 0.5, value: r.value }),
        ...common,
      ];
    case 'body_fat':
      return [
        field({ name: 'value', label: '体脂率', type: 'number', unit: '%', required: true, min: 1, max: 75, step: 0.1, value: r.value }),
        ...common,
      ];
    case 'muscle':
      return [
        field({ name: 'value', label: '肌肉量', type: 'number', unit: 'kg', required: true, min: 1, max: 200, step: 0.1, value: r.value }),
        ...common,
      ];
    case 'water':
      return [
        field({ name: 'value', label: '饮水量', type: 'number', unit: 'ml', required: true, min: 10, max: 5000, step: 10, value: r.value || 250 }),
        ...common,
      ];
    case 'food':
      return [
        field({ name: 'desc', label: '吃了什么', type: 'text', required: true, value: meta.desc || r.title || '', placeholder: '例如：红烧鲫鱼 + 米饭' }),
        field({ name: 'slot', label: '餐次', type: 'select', options: MEALS, value: meta.slot || meta.meal || 'dinner' }),
        field({ name: 'value', label: '卡路里', type: 'number', unit: 'kcal', min: 0, max: 5000, step: 1, value: r.value || meta.kcal }),
        field({ name: 'protein_g', label: '蛋白质', type: 'number', unit: 'g', min: 0, max: 500, step: 0.1, value: meta.protein_g }),
        field({ name: 'carb_g', label: '碳水', type: 'number', unit: 'g', min: 0, max: 800, step: 0.1, value: meta.carb_g }),
        field({ name: 'fat_g', label: '脂肪', type: 'number', unit: 'g', min: 0, max: 500, step: 0.1, value: meta.fat_g }),
        field({ name: 'fiber_g', label: '纤维', type: 'number', unit: 'g', min: 0, max: 200, step: 0.1, value: meta.fiber_g }),
        ...common,
      ];
    case 'training':
      return [
        field({ name: 'desc', label: '训练内容', type: 'text', required: true, value: meta.desc || r.title || '', placeholder: '例如：快走' }),
        field({ name: 'value', label: '时长', type: 'number', unit: '分钟', required: true, min: 1, max: 600, step: 1, value: r.value }),
        ...common,
      ];
    case 'sleep':
      return [
        field({ name: 'value', label: '睡眠时长', type: 'number', unit: '小时', required: true, min: 0, max: 24, step: 0.1, value: r.value }),
        ...common,
      ];
    case 'side_effect':
      return [
        field({ name: 'label', label: '感受', type: 'select', options: SYMPTOMS.concat(['我很好', '食欲不振']), value: meta.label || r.title || '恶心' }),
        field({ name: 'value', label: '严重程度', type: 'number', unit: '级 (1-10)', min: 1, max: 10, step: 1, value: meta.severity || r.value || 2 }),
        ...common,
      ];
    case 'symptom':
      return [
        field({ name: 'label', label: '症状', type: 'text', required: true, value: meta.label || r.title || '', placeholder: '例如：注射部位红肿' }),
        ...common,
      ];
    case 'dose':
      return [
        field({ name: 'med_name', label: '药物', type: 'text', required: true, value: meta.med_name || profile.med_name || '', placeholder: '例如：替尔泊肽' }),
        field({ name: 'value', label: '剂量', type: 'number', unit: 'mg', required: true, min: 0.1, max: 30, step: 0.1, value: r.value || meta.dose_mg || profile.dose_mg }),
        field({
          name: 'site', label: '注射部位', type: 'select', value: meta.site || '腹部左侧',
          options: ['腹部左侧', '腹部右侧', '大腿左侧', '大腿右侧', '上臂'],
        }),
        ...common,
      ];
    case 'lab':
      return [
        field({ name: 'label', label: '化验项目', type: 'text', required: true, value: meta.label || r.title || '', placeholder: '例如：糖化血红蛋白' }),
        field({ name: 'value', label: '结果', type: 'text', value: r.subtitle || meta.desc || '', placeholder: '例如：5.4 %' }),
        ...common,
      ];
    default: {
      const spec = MEASURE_TYPES[type] || { label: typeMeta(type).label, unit: '', placeholder: '' };
      return [
        field({ name: 'value', label: spec.label, type: 'text', unit: spec.unit, value: r.value, placeholder: spec.placeholder }),
        ...common,
      ];
    }
  }
}

/** Convert submitted values into the payload the API expects. */
export function recordPayload(type, values) {
  const v = values || {};
  const meta = {};
  if (v.note) meta.note = v.note;
  if (v.desc) meta.desc = v.desc;
  let value = v.value;
  let unit = '';

  switch (type) {
    case 'food': {
      meta.slot = v.slot || v.meal || 'dinner';
      meta.kcal = toNum(v.value) || 0;
      meta.protein_g = toNum(v.protein_g) || 0;
      meta.carb_g = toNum(v.carb_g) || 0;
      meta.fat_g = toNum(v.fat_g) || 0;
      meta.fiber_g = toNum(v.fiber_g) || 0;
      value = meta.kcal;
      unit = 'kcal';
      break;
    }
    case 'water': {
      meta.amount_ml = toNum(v.value) || 0;
      value = meta.amount_ml;
      unit = 'ml';
      break;
    }
    case 'dose': {
      meta.dose_mg = toNum(v.value) || 0;
      if (v.med_name) meta.med_name = v.med_name;
      if (v.site) meta.site = v.site;
      value = meta.dose_mg;
      unit = 'mg';
      break;
    }
    case 'side_effect': {
      meta.label = v.label || v.title || '不适';
      meta.severity = Math.max(1, Math.min(10, Math.round(toNum(v.value) || 1)));
      value = meta.severity;
      unit = '级';
      break;
    }
    case 'symptom': {
      if (v.label) meta.label = v.label;
      if (v.desc) meta.desc = v.desc;
      break;
    }
    case 'weight':
      unit = 'kg';
      break;
    case 'waist':
      unit = 'cm';
      break;
    case 'body_fat':
      unit = '%';
      break;
    case 'muscle':
      unit = 'kg';
      break;
    case 'training':
      unit = 'min';
      break;
    case 'sleep':
      unit = 'h';
      break;
    case 'lab':
      if (v.label) meta.label = v.label;
      unit = '';
      break;
    default: {
      if (v.label) meta.label = v.label;
      const spec = MEASURE_TYPES[type];
      if (spec && spec.unit) unit = spec.unit;
      break;
    }
  }

  for (const key of Object.keys(meta)) {
    if (meta[key] === undefined || meta[key] === null || meta[key] === '') delete meta[key];
  }
  if (typeof value === 'string' && value.trim() === '') value = null;

  const payload = {
    type: type === 'symptom' && !meta.label ? 'note' : type,
    value: value === undefined ? null : value,
    unit: unit,
    meta: meta,
  };
  if (v.label && !meta.label && type !== 'food') payload.meta.label = v.label;
  if (v.at) payload.at = v.at.length === 16 ? v.at + ':00' : v.at;
  return payload;
}

/**
 * Open the create/edit form for a record type.
 * @param {{type?:string, record?:object, title?:string, onSubmit:Function,
 *          onDelete?:Function, quickAdds?:number[]}} opts
 */
export function openRecordSheet(opts) {
  const o = opts || {};
  const record = o.record || null;
  const type = o.type || (record && record.type) || 'note';
  const meta = typeMeta(type);
  const fields = recordFields(type, record, { profile: o.profile || state.profile || {} });
  const form = el('form', { class: 'form', novalidate: true });
  for (const wrap of fields) form.appendChild(wrap);

  const quickWrap = el('div', { class: 'quick-adds' });
  if (Array.isArray(o.quickAdds) && o.quickAdds.length) {
    for (const amount of o.quickAdds) {
      quickWrap.appendChild(
        button({
          label: '+' + amount,
          kind: 'ghost',
          small: true,
          onClick: () => {
            const target = fields.find((f) => f.input && f.input.name === 'value');
            if (!target) return;
            const current = Number(target.input.value) || 0;
            target.input.value = String(current + amount);
          },
        })
      );
    }
  }

  const submit = button({
    label: o.submitLabel || (record ? '保存修改' : '保存记录'),
    kind: 'primary',
    block: true,
    onClick: () => {
      const result = readFields(fields);
      if (result.firstInvalid) {
        result.firstInvalid.focus();
        return;
      }
      submit.disabled = true;
      Promise.resolve(o.onSubmit(recordPayload(type, result.values), result.values))
        .then(() => sheet.close())
        .catch((err) => {
          sheet.setError(err && err.message ? err.message : '保存失败，请重试');
        })
        .then(() => {
          submit.disabled = false;
        });
    },
  });

  const actions = [submit];
  if (record && typeof o.onDelete === 'function') {
    actions.unshift(
      button({
        label: '删除这条记录',
        kind: 'danger',
        block: true,
        onClick: () => {
          confirmDialog({
            title: '删除这条记录？',
            message: '删除后无法恢复。',
            confirmLabel: '删除',
          }).then((ok) => {
            if (!ok) return;
            Promise.resolve(o.onDelete(record))
              .then(() => sheet.close())
              .catch((err) => sheet.setError(err && err.message ? err.message : '删除失败'));
          });
        },
      })
    );
  }

  const sheet = openSheet({
    title: o.title || (record ? '修正记录' : '添加' + meta.label),
    subtitle: o.subtitle,
    body: [quickWrap.childNodes.length ? quickWrap : null, form].filter(Boolean),
    actions: actions,
  });
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    submit.click();
  });
  return sheet;
}

/**
 * Searchable list of every record type ("15 种类型，可搜索").
 * @param {{types:Array<{type:string,label:string,icon?:string,color?:string}>,
 *          onPick:Function, title?:string}} opts
 */
export function openTypePicker(opts) {
  const o = opts || {};
  const types = Array.isArray(o.types) ? o.types : [];
  const listNode = el('div', { class: 'list' });

  const renderList = (query) => {
    listNode.textContent = '';
    const q = String(query || '').trim();
    const filtered = types.filter((t) => !q || String(t.label || '').indexOf(q) !== -1 || String(t.type || '').indexOf(q) !== -1);
    if (!filtered.length) {
      listNode.appendChild(el('p', { class: 'empty-hint', text: '没有匹配的类型' }));
      return;
    }
    for (const item of filtered) {
      const meta = typeMeta(item.type || item.key);
      const color = item.color || meta.color;
      const node = el(
        'button',
        {
          class: 'row row--btn',
          type: 'button',
          onClick: () => {
            sheet.close();
            o.onPick(item);
          },
        },
        el('span', { class: 'row-icon', style: { '--tone': color, background: tint(color, 0.14) } }, icon(item.icon || meta.icon, { size: 18 })),
        el('span', { class: 'row-main' }, el('span', { class: 'row-text' }, el('span', { class: 'row-title', text: item.label || meta.label }))),
        el('span', { class: 'row-chev' }, icon('chevron', { size: 16 }))
      );
      listNode.appendChild(node);
    }
  };

  const searchInput = el('input', {
    class: 'input',
    type: 'search',
    placeholder: '搜索记录类型',
    'aria-label': '搜索记录类型',
    onInput: (event) => renderList(event.target.value),
  });
  renderList('');

  const sheet = openSheet({
    title: o.title || '快速记录',
    subtitle: types.length + ' 种类型，可搜索',
    body: [el('div', { class: 'field' }, searchInput), listNode],
  });
  return sheet;
}

export default { openSheet, confirmDialog, field, readFields, openRecordSheet, openTypePicker, recordFields, recordPayload };
