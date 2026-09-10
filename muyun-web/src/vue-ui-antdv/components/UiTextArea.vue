<script setup lang="ts">
import { computed, ref } from 'vue';
import { Input as AInput } from 'ant-design-vue';
import { createUiTreeInstanceId, useUiDropTarget } from '../useUiTreeDrag';
import type { UiDragSource, UiDropTarget, UiTreeDropEvent } from '../types';

defineOptions({ name: 'UiTextArea', inheritAttrs: false });

const props = withDefaults(
  defineProps<{
    value?: string;
    placeholder?: string;
    disabled?: boolean;
    rows?: number;
    maxlength?: number;
    /** Enables adapter-owned, selection-aware drops without exposing the underlying textarea DOM. */
    acceptDrop?: (source: UiDragSource) => boolean;
  }>(),
  {
    value: '',
    placeholder: undefined,
    disabled: false,
    rows: 4,
    maxlength: undefined,
    acceptDrop: undefined,
  },
);

const emit = defineEmits<{
  'update:value': [value: string];
  selection: [selection: { start: number; end: number }];
  drop: [event: { source: UiDragSource; selection: { start: number; end: number }; nativeEvent?: Event }];
}>();

const textarea = ref<HTMLTextAreaElement>();
const textareaControl = ref<{ focus?: () => void }>();
const dropRoot = ref<HTMLElement>();
const instanceId = createUiTreeInstanceId();
const dropSelection = ref<{ start: number; end: number }>();

function rememberTextarea(event: Event) {
  if (!(event.target instanceof HTMLTextAreaElement)) return;
  textarea.value = event.target;
  emit('selection', selection());
}

function createTextareaMirror(element: HTMLTextAreaElement) {
  const style = getComputedStyle(element);
  const rect = element.getBoundingClientRect();
  const mirror = document.createElement('div');
  // Keep one measurable character for an empty expression. Collapsed DOM ranges
  // have no reliable rect in Chromium at line boundaries.
  const text = document.createTextNode(props.value || '\u200b');
  const borderLeft = Number.parseFloat(style.borderLeftWidth) || 0;
  const borderTop = Number.parseFloat(style.borderTopWidth) || 0;
  Object.assign(mirror.style, {
    position: 'fixed',
    left: `${rect.left + borderLeft}px`,
    top: `${rect.top + borderTop}px`,
    // clientWidth excludes the scrollbar and is the actual line-wrap width of a textarea.
    width: `${element.clientWidth}px`,
    boxSizing: 'border-box',
    padding: style.padding,
    border: '0',
    font: style.font,
    letterSpacing: style.letterSpacing,
    lineHeight: style.lineHeight,
    textIndent: style.textIndent,
    textTransform: style.textTransform,
    whiteSpace: 'pre-wrap',
    overflowWrap: 'break-word',
    wordBreak: 'break-word',
    tabSize: style.tabSize,
    visibility: 'hidden',
    pointerEvents: 'none',
    zIndex: '-1',
  });
  mirror.append(text);
  document.body.append(mirror);
  return { mirror, text };
}

function mirrorCaretRect(text: Text, index: number) {
  const range = document.createRange();
  const length = props.value.length;
  if (length === 0) {
    range.setStart(text, 0);
    range.setEnd(text, 1);
    return range.getBoundingClientRect();
  }
  const position = Math.min(Math.max(0, index), length);
  if (position < length) {
    range.setStart(text, position);
    range.setEnd(text, position + 1);
    return range.getBoundingClientRect();
  }
  range.setStart(text, length - 1);
  range.setEnd(text, length);
  const previous = range.getBoundingClientRect();
  return new DOMRect(previous.right, previous.top, 0, previous.height);
}

function caretRectAt(element: HTMLTextAreaElement, index: number) {
  const { mirror, text } = createTextareaMirror(element);
  const visible = mirrorCaretRect(text, index);
  mirror.remove();
  return new DOMRect(
    visible.left - element.scrollLeft,
    visible.top - element.scrollTop,
    visible.width,
    visible.height,
  );
}

function selectionAtPoint(x: number, y: number) {
  const element = textarea.value;
  if (!element) return selection();
  const { mirror, text } = createTextareaMirror(element);
  let closest = 0;
  let distance = Number.POSITIVE_INFINITY;
  for (let index = 0; index <= props.value.length; index += 1) {
    const visible = mirrorCaretRect(text, index);
    const rect = new DOMRect(
      visible.left - element.scrollLeft,
      visible.top - element.scrollTop,
      visible.width,
      visible.height,
    );
    const deltaX = rect.left - x;
    const deltaY = rect.top + rect.height / 2 - y;
    const next = deltaX * deltaX + deltaY * deltaY * 4;
    if (next < distance) {
      closest = index;
      distance = next;
    }
  }
  mirror.remove();
  return { start: closest, end: closest };
}

const dropCaretStyle = computed(() => {
  const current = dropSelection.value;
  const element = textarea.value;
  if (!current || !element) return undefined;
  const rect = element.getBoundingClientRect();
  const caret = caretRectAt(element, current.start);
  const style = getComputedStyle(element);
  return {
    left: `${caret.left - rect.left}px`,
    top: `${caret.top - rect.top}px`,
    height: `${Number.parseFloat(style.lineHeight) || Number.parseFloat(style.fontSize) || 14}px`,
  };
});

const { hovered: dropHovered, rejected: dropRejected } = useUiDropTarget(dropRoot, {
  resolve: (origin, y, _position, _source, x) => {
    const target = origin.closest('textarea');
    if (target instanceof HTMLTextAreaElement) textarea.value = target;
    dropSelection.value = selectionAtPoint(x ?? 0, y);
    return { instanceId, kind: 'root', position: 'inside' } as UiDropTarget;
  },
  allow: (event: UiTreeDropEvent) => !props.disabled && Boolean(props.acceptDrop?.(event.source)),
  drop: (event: UiTreeDropEvent) => {
    const current = dropSelection.value ?? selection();
    dropSelection.value = undefined;
    emit('drop', { source: event.source, selection: current, nativeEvent: event.nativeEvent });
  },
  cancelWhenPointerLeaves: true,
});

/** The adapter owns Ant Design DOM details and exposes only selection-aware editing capabilities. */
function selection() {
  return {
    start: textarea.value?.selectionStart ?? 0,
    end: textarea.value?.selectionEnd ?? 0,
  };
}

function focusSelection(start: number, end = start) {
  textareaControl.value?.focus?.();
  const element = textarea.value;
  if (!element) return;
  element.focus();
  element.setSelectionRange(start, end);
}

defineExpose({ selection, focusSelection });
</script>

<template>
  <div
    ref="dropRoot"
    class="ui-text-area"
    :class="[$attrs.class, { 'ui-text-area--drop-rejected': dropHovered && dropRejected }]"
    :style="$attrs.style"
  >
    <AInput.TextArea
      ref="textareaControl"
      :value="value"
      :placeholder="placeholder"
      :disabled="disabled"
      :rows="rows"
      :maxlength="maxlength"
      @focus="rememberTextarea"
      @select="rememberTextarea"
      @click="rememberTextarea"
      @input="rememberTextarea"
      @update:value="emit('update:value', $event)"
    />
    <span
      v-if="dropHovered && !dropRejected && dropCaretStyle"
      class="ui-text-area__drop-caret"
      :style="dropCaretStyle"
      aria-hidden="true"
    />
  </div>
</template>

<style scoped>
.ui-text-area {
  position: relative;
  min-width: 0;
}

.ui-text-area__drop-caret {
  position: absolute;
  width: 2px;
  background: var(--muyun-primary);
  pointer-events: none;
}

.ui-text-area--drop-rejected :deep(textarea) {
  outline: 1px dashed var(--muyun-danger-text);
}
</style>
