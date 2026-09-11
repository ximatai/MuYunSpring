import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick, ref } from 'vue';
import { describe, expect, it } from 'vitest';
import UiTokenInput from '@/vue-ui-antdv/components/UiTokenInput.vue';

function controlledFixture(
  initial: string,
  tokens: () => Array<{ start: number; end: number; source: string; label: string }>,
) {
  const value = ref(initial);
  return mount(
    defineComponent({
      setup() {
        return () =>
          h(UiTokenInput, {
            value: value.value,
            tokens: tokens(),
            'onUpdate:value': (next: string) => (value.value = next),
          });
      },
    }),
    { attachTo: document.body },
  );
}

describe('UiTokenInput', () => {
  it('uses source offsets even when the visual field label has a different length', async () => {
    const wrapper = controlledFixture('{amount} + 1', () => [
      { start: 0, end: 8, source: '{amount}', label: '金额（含税）' },
    ]);
    await nextTick();
    const editor = wrapper.get('[role="textbox"]');
    expect(editor.text()).toContain('金额（含税）');
    const tokenInput = wrapper.findComponent(UiTokenInput);
    tokenInput.vm.focusSelection(8);
    expect(tokenInput.vm.selection()).toEqual({ start: 8, end: 8 });
    await editor.trigger('keydown', { key: 'Backspace' });
    await nextTick();
    expect(wrapper.findComponent(UiTokenInput).props('value')).toBe(' + 1');
    wrapper.unmount();
  });

  it('keeps disabled input immutable and preserves raw plain-text paste', async () => {
    const wrapper = controlledFixture('{amount}', () => [
      { start: 0, end: 8, source: '{amount}', label: '金额' },
    ]);
    await nextTick();
    const tokenInput = wrapper.findComponent(UiTokenInput);
    tokenInput.vm.focusSelection(8);
    await wrapper.get('[role="textbox"]').trigger('paste', {
      clipboardData: { getData: () => ' * 2' },
    });
    await nextTick();
    expect(tokenInput.props('value')).toBe('{amount} * 2');
    wrapper.unmount();

    const disabled = mount(UiTokenInput, { props: { value: '{amount}', disabled: true } });
    await nextTick();
    await disabled.get('[role="textbox"]').trigger('keydown', { key: 'Backspace' });
    expect(disabled.emitted('update:value')).toBeUndefined();
    disabled.unmount();
  });
});

it('moves a visual atom as its original source and copies source rather than its label', async () => {
  const wrapper = controlledFixture('{amount} + 1', () => [
    { start: 0, end: 8, source: '{amount}', label: '金额' },
  ]);
  await nextTick();
  const tokenInput = wrapper.findComponent(UiTokenInput);
  const editor = wrapper.get('[role="textbox"]');
  const copied = { value: '', setData: (_type: string, value: string) => (copied.value = value) };
  tokenInput.vm.focusSelection(0, 8);
  await editor.trigger('copy', { clipboardData: copied });
  expect(copied.value).toBe('{amount}');

  const transfer = { setData: () => undefined };
  const atom = wrapper.get('[data-source="{amount}"]');
  await atom.trigger('dragstart', { dataTransfer: transfer });
  expect(atom.classes()).toContain('ui-token-input__token--dragging');
  await editor.trigger('dragend');
  expect(atom.classes()).not.toContain('ui-token-input__token--dragging');
  tokenInput.vm.focusSelection(12);
  await editor.trigger('drop', { clientX: 0, clientY: 0 });
  await nextTick();
  expect(tokenInput.props('value')).toBe('{amount} + 1');

  await atom.trigger('dragstart', { dataTransfer: transfer });
  tokenInput.vm.focusSelection(0);
  const textAfterAtom = editor.element.childNodes[1] as Text;
  const caret = document.createRange();
  caret.setStart(textAfterAtom, textAfterAtom.data.length);
  caret.collapse(true);
  const descriptor = Object.getOwnPropertyDescriptor(document, 'caretRangeFromPoint');
  Object.defineProperty(document, 'caretRangeFromPoint', { configurable: true, value: () => caret });
  try {
    await editor.trigger('drop', { clientX: 64, clientY: 18 });
  } finally {
    if (descriptor) Object.defineProperty(document, 'caretRangeFromPoint', descriptor);
    else Object.defineProperty(document, 'caretRangeFromPoint', { configurable: true, value: undefined });
  }
  await nextTick();
  expect(tokenInput.props('value')).toBe(' + 1{amount}');
  wrapper.unmount();
});

it('shows an insertion caret while a visual token is dragged over the formula', async () => {
  const wrapper = controlledFixture('{amount} + 1', () => [
    { start: 0, end: 8, source: '{amount}', label: '金额' },
  ]);
  await nextTick();
  const root = wrapper.get('.ui-token-input');
  const editor = wrapper.get('[role="textbox"]');
  const atom = wrapper.get('[data-source="{amount}"]');
  const textAfterAtom = editor.element.childNodes[1] as Text;
  const caret = document.createRange();
  caret.setStart(textAfterAtom, textAfterAtom.data.length);
  caret.collapse(true);
  const descriptor = Object.getOwnPropertyDescriptor(document, 'caretRangeFromPoint');
  Object.defineProperty(document, 'caretRangeFromPoint', { configurable: true, value: () => caret });
  try {
    await atom.trigger('dragstart', { dataTransfer: { setData: () => undefined } });
    await editor.trigger('dragover', { clientX: 64, clientY: 18, dataTransfer: {} });
    expect(root.classes()).toContain('ui-token-input--drop-active');
    expect(root.find('.ui-token-input__drop-caret').exists()).toBe(true);
    await editor.trigger('dragleave', { relatedTarget: document.body });
    expect(root.classes()).not.toContain('ui-token-input--drop-active');
  } finally {
    if (descriptor) Object.defineProperty(document, 'caretRangeFromPoint', descriptor);
    else Object.defineProperty(document, 'caretRangeFromPoint', { configurable: true, value: undefined });
    wrapper.unmount();
  }
});

it('does not redraw in-progress IME input and emits its final raw text', async () => {
  const wrapper = controlledFixture('', () => []);
  await nextTick();
  const editor = wrapper.get('[role="textbox"]');
  await editor.trigger('compositionstart');
  editor.element.textContent = '数量';
  await editor.trigger('input');
  expect(wrapper.findComponent(UiTokenInput).props('value')).toBe('');
  await editor.trigger('compositionend');
  await nextTick();
  expect(wrapper.findComponent(UiTokenInput).props('value')).toBe('数量');
  wrapper.unmount();
});

it('restores the previous raw source with the native undo shortcut', async () => {
  const wrapper = controlledFixture('1', () => []);
  await nextTick();
  const tokenInput = wrapper.findComponent(UiTokenInput);
  tokenInput.vm.focusSelection(1);
  await wrapper.get('[role="textbox"]').trigger('paste', {
    clipboardData: { getData: () => ' + 2' },
  });
  await nextTick();
  expect(tokenInput.props('value')).toBe('1 + 2');
  await wrapper.get('[role="textbox"]').trigger('keydown', { key: 'z', ctrlKey: true });
  await nextTick();
  expect(tokenInput.props('value')).toBe('1');
  wrapper.unmount();
});

it('cuts source text, preserves the last selection outside the editor, and pastes multiline source at it', async () => {
  const wrapper = controlledFixture('{amount} + 1', () => [
    { start: 0, end: 8, source: '{amount}', label: '金额' },
  ]);
  await nextTick();
  const tokenInput = wrapper.findComponent(UiTokenInput);
  const editor = wrapper.get('[role="textbox"]');
  const clipboard = { value: '', setData: (_type: string, value: string) => (clipboard.value = value) };
  tokenInput.vm.focusSelection(0, 8);
  await editor.trigger('cut', { clipboardData: clipboard });
  await nextTick();
  expect(clipboard.value).toBe('{amount}');
  expect(tokenInput.props('value')).toBe(' + 1');

  tokenInput.vm.focusSelection(1);
  document.getSelection()?.removeAllRanges();
  expect(tokenInput.vm.selection()).toEqual({ start: 1, end: 1 });
  await editor.trigger('paste', { clipboardData: { getData: () => '甲\n乙' } });
  await nextTick();
  expect(tokenInput.props('value')).toBe(' 甲\n乙+ 1');
  wrapper.unmount();
});

it('keeps host-applied source changes in this editor history for undo and redo', async () => {
  const value = ref('');
  const wrapper = mount(UiTokenInput, {
    attachTo: document.body,
    props: { value: value.value, 'onUpdate:value': (next: string) => (value.value = next) },
  });
  await nextTick();
  value.value = '{amount}';
  await wrapper.setProps({ value: value.value });
  const editor = wrapper.get('[role="textbox"]');
  await editor.trigger('keydown', { key: 'z', ctrlKey: true });
  await nextTick();
  expect(value.value).toBe('');
  await editor.trigger('keydown', { key: 'y', ctrlKey: true });
  await nextTick();
  expect(value.value).toBe('{amount}');
  wrapper.unmount();
});

it('uses the same history for browser undo input events and keeps its boundary a no-op', async () => {
  const wrapper = controlledFixture('1', () => []);
  const tokenInput = wrapper.findComponent(UiTokenInput);
  const editor = wrapper.get('[role="textbox"]');
  try {
    tokenInput.vm.focusSelection(1);
    await editor.trigger('paste', { clipboardData: { getData: () => ' + 2' } });
    await nextTick();
    await editor.trigger('beforeinput', { inputType: 'historyUndo' });
    await nextTick();
    expect(tokenInput.props('value')).toBe('1');
    await editor.trigger('beforeinput', { inputType: 'historyUndo' });
    await nextTick();
    expect(tokenInput.props('value')).toBe('1');
    await editor.trigger('beforeinput', { inputType: 'historyRedo' });
    await nextTick();
    expect(tokenInput.props('value')).toBe('1 + 2');
  } finally {
    wrapper.unmount();
  }
});
