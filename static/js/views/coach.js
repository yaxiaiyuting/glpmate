/**
 * views/coach.js — the AI coach chat screen (助手).
 *
 * The quota banner replaces the commercial app's paywall: it shows the
 * configurable daily message quota and links to the settings form
 * ("管理额度") instead of an upsell.
 */

import { el, clear } from '../dom.js';
import { icon } from '../icons.js';
import { api } from '../api.js';
import { state, connect, set, timeOf, loadCoach, loadBootstrap } from '../store.js';
import { card, button, emptyState, errorState, skeleton } from '../components/card.js';
import { thinBar } from '../components/progress.js';
import { renderMarkdown } from '../markdown.js';
import { createComposer } from '../components/composer.js';
import { toast } from '../components/toast.js';
import { navigate } from '../router.js';

/** Number of tracked data sources advertised in the header. */
const DATA_SOURCES = 13;

const LIKE_STATE = { liked: {}, disliked: {} };

/* ------------------------------------------------------------------ *
 * header
 * ------------------------------------------------------------------ */

function avatar(role) {
  const name = (state.profile && (state.profile.display_name || state.profile.name)) || '我';
  return el(
    'span',
    { class: 'avatar avatar--' + (role === 'user' ? 'user' : 'coach'), 'aria-hidden': 'true' },
    role === 'user' ? el('span', { text: name.charAt(0) }) : icon('sparkle', { size: 18 })
  );
}

function header() {
  return el(
    'header',
    { class: 'coach-head' },
    avatar('assistant'),
    el(
      'div',
      { class: 'coach-head-text' },
      el('h1', { class: 'coach-title', text: '教练' }),
      el('p', { class: 'coach-sub', text: '免费 · 通过书写提问和记录' })
    ),
    el('button', {
      class: 'icon-btn',
      type: 'button',
      'aria-label': '更多选项',
      onClick: () => toast('更多选项还没实现', 'info'),
    }, icon('menu', { size: 20 }))
  );
}

function sourcesLine() {
  return el(
    'div',
    { class: 'sources-line' },
    el('span', { class: 'sources-text', text: DATA_SOURCES + ' 个数据源已激活' }),
    el('button', {
      class: 'sources-info',
      type: 'button',
      'aria-label': '数据源说明',
      onClick: () => toast('教练可以读取体重、餐食、饮水、剂量、副作用等记录', 'info'),
    }, icon('info', { size: 15 }))
  );
}

/* ------------------------------------------------------------------ *
 * quota
 * ------------------------------------------------------------------ */

function quotaCard() {
  const used = Number(state.coachQuota.used) || 0;
  const limit = Number(state.coachQuota.limit) || 0;
  const remaining = state.coachQuota.remaining !== undefined
    ? Number(state.coachQuota.remaining)
    : Math.max(0, limit - used);
  const exhausted = state.coachLocked || (limit > 0 && used >= limit);

  const manage = button({
    label: '管理额度',
    kind: exhausted ? 'primary' : 'ghost',
    small: true,
    onClick: () => navigate('/settings', { focus: 'quota' }),
  });

  if (exhausted) {
    return el(
      'div',
      { class: 'lock-banner', role: 'status' },
      el('span', { class: 'lock-icon', text: '🔒' }),
      el(
        'div',
        { class: 'lock-text' },
        el('p', { class: 'lock-title', text: '你今日的教练额度已用完' }),
        el('p', { class: 'lock-sub', text: limit ? '已使用 ' + used + ' / ' + limit + ' 条' : '额度已用完' })
      ),
      manage
    );
  }

  return card(
    { class: 'card--quota' },
    el(
      'div',
      { class: 'quota-head' },
      el(
        'div',
        null,
        el('p', { class: 'quota-title', text: '今日教练额度' }),
        el('p', {
          class: 'quota-sub',
          text: limit ? used + ' / ' + limit + ' 条消息 · 还剩 ' + Math.max(0, remaining) + ' 条' : '未设置额度',
        })
      ),
      manage
    ),
    thinBar({
      value: used,
      target: limit || 1,
      color: used >= limit ? '#E8A33D' : '#35C6E8',
      label: '今日额度使用情况',
    })
  );
}

/* ------------------------------------------------------------------ *
 * messages
 * ------------------------------------------------------------------ */

function messageActions(message) {
  const text = String(message.text || '');
  const copy = () => {
    const payload = text;
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(payload).then(
        () => toast('已复制', 'success'),
        () => toast('复制失败，请手动选择文本', 'error')
      );
      return;
    }
    toast('这个浏览器不支持自动复制', 'error');
  };

  const speak = () => {
    if (typeof speechSynthesis === 'undefined' || typeof SpeechSynthesisUtterance === 'undefined') {
      toast('这个浏览器不支持朗读', 'error');
      return;
    }
    try {
      const utterance = new SpeechSynthesisUtterance(text);
      utterance.lang = 'zh-CN';
      speechSynthesis.speak(utterance);
    } catch (err) {
      toast('朗读失败', 'error');
    }
  };

  const share = () => {
    if (navigator.share) {
      navigator.share({ title: '教练回复', text: text }).catch(() => {});
      return;
    }
    copy();
  };

  return el(
    'div',
    { class: 'msg-actions' },
    el('button', { class: 'msg-action', type: 'button', 'aria-label': '朗读', onClick: speak }, icon('mic', { size: 16 })),
    el('button', {
      class: 'msg-action' + (LIKE_STATE.liked[message.id] ? ' is-on' : ''),
      type: 'button',
      'aria-label': '赞',
      onClick: (event) => {
        LIKE_STATE.liked[message.id] = !LIKE_STATE.liked[message.id];
        event.currentTarget.classList.toggle('is-on', !!LIKE_STATE.liked[message.id]);
        toast(LIKE_STATE.liked[message.id] ? '已标记为有帮助' : '已取消', 'success');
      },
    }, icon('thumb-up', { size: 16 })),
    el('button', {
      class: 'msg-action' + (LIKE_STATE.disliked[message.id] ? ' is-on' : ''),
      type: 'button',
      'aria-label': '踩',
      onClick: (event) => {
        LIKE_STATE.disliked[message.id] = !LIKE_STATE.disliked[message.id];
        event.currentTarget.classList.toggle('is-on', !!LIKE_STATE.disliked[message.id]);
        toast(LIKE_STATE.disliked[message.id] ? '已记录反馈' : '已取消', 'success');
      },
    }, icon('thumb-down', { size: 16 })),
    el('button', { class: 'msg-action', type: 'button', 'aria-label': '复制', onClick: copy }, icon('copy', { size: 16 })),
    el('button', { class: 'msg-action', type: 'button', 'aria-label': '分享', onClick: share }, icon('share', { size: 16 }))
  );
}

function confirmCard(message) {
  const info = message.card || {};
  const added = info.status === 'added';
  const discarded = info.status === 'discarded';

  const confirmBtn = el(
    'span',
    { class: 'card-confirm' + (added ? ' is-added' : '') },
    icon('check', { size: 14, stroke: 2.2 }),
    el('span', { text: added ? '已添加' : '添加' })
  );

  const saveBtn = button({
    label: '保存',
    kind: 'primary',
    small: true,
    disabled: added || discarded,
    onClick: () => {
      api
        .coachConfirm(info.card_id, 'save')
        .then((result) => {
          toast('已保存 ' + ((result && result.record_ids && result.record_ids.length) || 0) + ' 条记录', 'success');
          loadCoach();
          loadBootstrap(true).catch(() => {});
        })
        .catch(() => {});
    },
  });

  const discardBtn = button({
    label: '忽略',
    kind: 'ghost',
    small: true,
    disabled: added || discarded,
    onClick: () => {
      api
        .coachConfirm(info.card_id, 'discard')
        .then(() => {
          toast('已忽略这条估算', 'success');
          loadCoach();
        })
        .catch(() => {});
    },
  });

  return el(
    'div',
    { class: 'confirm-card' + (added ? ' confirm-card--added' : '') },
    el(
      'div',
      { class: 'confirm-head' },
      el('span', { class: 'confirm-kind', text: '确认卡片' }),
      added ? confirmBtn : null
    ),
    el('p', { class: 'confirm-title', text: info.title || '待确认的记录' }),
    info.subtitle ? el('p', { class: 'confirm-sub', text: info.subtitle }) : null,
    el('div', { class: 'confirm-actions' }, added ? null : confirmBtn, saveBtn, discardBtn)
  );
}

function bubble(message) {
  const role = message.role === 'user' ? 'user' : 'assistant';
  const body = el('div', { class: 'bubble bubble--' + role });
  if (role === 'assistant') {
    body.appendChild(el('div', { class: 'bubble-text md' }, renderMarkdown(message.text || '')));
    if (message.card) body.appendChild(confirmCard(message));
    body.appendChild(messageActions(message));
  } else {
    body.appendChild(el('p', { class: 'bubble-text', text: message.text || '' }));
  }

  return el(
    'div',
    { class: 'msg msg--' + role, dataset: { id: String(message.id === undefined ? '' : message.id) } },
    avatar(role),
    el(
      'div',
      { class: 'msg-body' },
      body,
      el('span', { class: 'msg-time', text: timeOf(message.at) })
    )
  );
}

function pendingBubble() {
  return el(
    'div',
    { class: 'msg msg--assistant msg--pending' },
    avatar('assistant'),
    el(
      'div',
      { class: 'msg-body' },
      el(
        'div',
        { class: 'bubble bubble--assistant' },
        el('div', { class: 'dots', 'aria-label': '正在输入' },
          el('span', { class: 'dot' }), el('span', { class: 'dot' }), el('span', { class: 'dot' }))
      )
    )
  );
}

function transcript() {
  const messages = Array.isArray(state.coachMessages) ? state.coachMessages : [];
  const wrap = el('div', { class: 'transcript' });

  if (!state.ready && !messages.length) {
    wrap.appendChild(state.error
      ? errorState({ message: state.error, onRetry: () => loadBootstrap(true).catch(() => {}) })
      : skeleton(4));
    return wrap;
  }
  if (!messages.length) {
    wrap.appendChild(emptyState({
      icon: 'sparkle',
      title: '还没有对话',
      hint: '试着写一句「今天午餐吃了红烧鲫鱼」，教练会帮你估算并记录。',
    }));
    return wrap;
  }
  for (const message of messages) wrap.appendChild(bubble(message));
  if (state.coachSending) wrap.appendChild(pendingBubble());
  return wrap;
}

function quotaExplainer() {
  const used = Number(state.coachQuota.used) || 0;
  const limit = Number(state.coachQuota.limit) || 0;
  return el(
    'div',
    { class: 'msg msg--assistant' },
    avatar('assistant'),
    el(
      'div',
      { class: 'msg-body' },
      el(
        'div',
        { class: 'bubble bubble--assistant' },
        el('div', { class: 'bubble-text md' }, renderMarkdown(
          '今天的 ' + (limit || '配置的') + ' 条教练消息已经用完了（已使用 ' + used + ' 条）。\n\n'
          + '这是自托管版本，额度可以在「设置 → 模型与额度」里随时调整，不涉及任何付费。'
        ))
      )
    )
  );
}

function whatYouCanDo() {
  return el(
    'div',
    { class: 'stack' },
    el('p', { class: 'muted-label', text: '你仍然可以做什么' }),
    card(
      { class: 'card--running' },
      el('span', { class: 'running-icon' }, icon('check', { size: 18, stroke: 2.4 })),
      el(
        'div',
        { class: 'running-text' },
        el('p', { class: 'running-title', text: '你的追踪仍在运行' }),
        el('p', { class: 'running-sub', text: '记录、提醒和图表不受额度影响。' }),
      )
    ),
    button({
      label: '管理额度',
      kind: 'primary',
      block: true,
      onClick: () => navigate('/settings', { focus: 'quota' }),
    })
  );
}

/* ------------------------------------------------------------------ *
 * view
 * ------------------------------------------------------------------ */

export function render(container) {
  const root = el('div', { class: 'view view--coach' });
  const transcriptHost = el('div', { class: 'transcript-host' });

  const composer = createComposer({
    placeholder: '写下你吃了什么，或想问的问题…',
    onError: (message) => toast(message, 'error'),
    onSend: (text) => send(text),
    onPhoto: (file) => sendPhoto(file),
  });

  function paint() {
    clear(root);
    root.appendChild(header());
    root.appendChild(sourcesLine());
    root.appendChild(quotaCard());
    if (state.error) {
      // history may still be cached: show a non-blocking failure banner
      root.appendChild(errorState({
        message: state.error,
        onRetry: () => loadBootstrap(true).catch(() => {}),
      }));
    }
    clear(transcriptHost);
    transcriptHost.appendChild(transcript());
    root.appendChild(transcriptHost);
    if (state.coachLocked) {
      root.appendChild(quotaExplainer());
      root.appendChild(whatYouCanDo());
    }
  }

  function afterPaint() {
    const active = document.activeElement;
    if (active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA')) return;
    window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' });
  }

  function refresh() {
    paint();
    afterPaint();
  }

  function send(text) {
    if (state.coachSending) return;
    set({ coachSending: true });
    composer.setBusy(true);
    composer.clear();
    api
      .sendCoachMessage(text, { silent: true })
      .then((payload) => {
        const messages = state.coachMessages.slice();
        if (payload && payload.user) messages.push(payload.user);
        if (payload && payload.assistant) messages.push(payload.assistant);
        set({
          coachMessages: messages,
          coachSending: false,
          coachLocked: false,
          coachQuota: (payload && payload.quota) || state.coachQuota,
        });
      })
      .catch((err) => {
        const payload = (err && err.payload) || {};
        const quotaExceeded = !!err && (err.code === 'quota_exceeded' || payload.error === 'quota_exceeded');
        set({ coachSending: false, coachLocked: !!quotaExceeded });
        if (quotaExceeded) {
          const quota = payload.quota || payload;
          toast(payload.message || '今日额度已用完', 'info');
          if (quota && quota.limit !== undefined) {
            set({ coachQuota: { used: Number(quota.used) || 0, limit: Number(quota.limit) || 0 } });
          }
        } else {
          toast(err && err.message ? err.message : '发送失败，请重试', 'error');
        }
      })
      .then(() => {
        composer.setBusy(false);
        refresh();
      });
  }

  function sendPhoto(file) {
    set({ coachSending: true });
    composer.setBusy(true);
    refresh();
    api
      .coachPhoto(file, { silent: true })
      .then((payload) => {
        const messages = state.coachMessages.slice();
        if (payload && payload.message) messages.push(payload.message);
        else {
          const estimate = (payload && payload.estimate) || {};
          const items = Array.isArray(estimate.items) ? estimate.items : [];
          const summary = items.map((i) => '**' + (i.name || '食物') + '** ' + (i.qty || '') + ' · ' + (i.kcal || 0) + ' kcal').join('\n');
          messages.push({
            id: 'photo-' + Date.now(),
            role: 'assistant',
            at: new Date().toISOString().slice(0, 19),
            text: '我从照片里看到：\n\n' + (summary || '没有识别出明确的食物。') + '\n\n' + (estimate.note || ''),
            card: payload && payload.card ? payload.card : null,
          });
        }
        set({ coachMessages: messages, coachSending: false });
        toast('照片已分析', 'success');
      })
      .catch((err) => {
        set({ coachSending: false });
        toast(err && err.message ? err.message : '照片上传失败', 'error');
      })
      .then(() => {
        composer.setBusy(false);
        refresh();
      });
  }

  paint();
  container.appendChild(root);
  container.appendChild(composer.el);
  if (!state.coachMessages.length) loadCoach().then(refresh);
  afterPaint();

  const disconnect = connect(['coachMessages', 'coachQuota', 'coachSending', 'coachLocked', 'ready', 'error'], refresh);

  return () => {
    disconnect();
    composer.destroy();
    clear(root);
  };
}

export default { render };
