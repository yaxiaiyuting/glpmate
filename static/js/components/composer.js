/**
 * components/composer.js — the message composer that floats above the tab bar.
 */

import { el } from '../dom.js';
import { icon } from '../icons.js';

/**
 * @param {{placeholder?:string, onSend?:Function, onPhoto?:Function,
 *          onError?:Function, maxLength?:number}} opts
 * @returns {{el:HTMLElement, setBusy:Function, clear:Function, setValue:Function,
 *            getValue:Function, focus:Function, destroy:Function}}
 */
export function createComposer(opts) {
  const o = opts || {};
  const maxLength = Number.isFinite(o.maxLength) ? o.maxLength : 2000;

  const input = el('input', {
    class: 'composer-input',
    type: 'text',
    placeholder: o.placeholder || '写下你想问的…',
    'aria-label': '给教练发消息',
    autocomplete: 'off',
    maxlength: String(maxLength),
    onInput: () => syncSend(),
  });

  const fileInput = el('input', {
    class: 'visually-hidden',
    type: 'file',
    accept: 'image/*',
    tabindex: '-1',
    'aria-hidden': 'true',
    onChange: (event) => {
      const file = event.target.files && event.target.files[0];
      event.target.value = '';
      if (!file) return;
      if (!/^image\//.test(file.type)) {
        if (o.onError) o.onError('只能上传图片文件');
        return;
      }
      if (file.size > 8 * 1024 * 1024) {
        if (o.onError) o.onError('图片太大，请压缩到 8 MB 以内');
        return;
      }
      if (o.onPhoto) o.onPhoto(file);
    },
  });

  const photoBtn = el(
    'button',
    {
      class: 'composer-photo',
      type: 'button',
      'aria-label': '拍照或选择照片',
      onClick: () => fileInput.click(),
    },
    icon('camera', { size: 19 })
  );

  const sendBtn = el(
    'button',
    { class: 'composer-send', type: 'submit', 'aria-label': '发送', disabled: true },
    icon('send', { size: 18, stroke: 2.1 })
  );

  function syncSend() {
    const hasText = input.value.trim().length > 0;
    sendBtn.disabled = !hasText || input.disabled;
  }

  const form = el(
    'form',
    {
      class: 'composer',
      onSubmit: (event) => {
        event.preventDefault();
        const text = input.value.trim();
        if (!text) return;
        if (o.onSend) o.onSend(text);
      },
    },
    fileInput,
    photoBtn,
    input,
    sendBtn
  );

  return {
    el: form,
    setBusy(busy) {
      input.disabled = !!busy;
      photoBtn.disabled = !!busy;
      form.classList.toggle('composer--busy', !!busy);
      syncSend();
    },
    clear() {
      input.value = '';
      syncSend();
    },
    setValue(value) {
      input.value = value === null || value === undefined ? '' : String(value);
      syncSend();
    },
    getValue() {
      return input.value.trim();
    },
    focus() {
      input.focus();
    },
    destroy() {
      if (form.parentNode) form.parentNode.removeChild(form);
    },
  };
}

export default createComposer;
