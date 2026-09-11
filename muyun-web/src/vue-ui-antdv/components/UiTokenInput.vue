<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { createUiTreeInstanceId, useUiDropTarget } from '../useUiTreeDrag';
import type { UiDragSource, UiDropTarget, UiTreeDropEvent } from '../types';

export interface UiTokenInputToken {
  /** Source positions are always offsets in value, never offsets in label. */
  start: number;
  end: number;
  /** Exact source text to restore when this visual atom is copied or deleted. */
  source: string;
  /** Human readable decoration. */
  label: string;
  title?: string;
}

defineOptions({ name: 'UiTokenInput', inheritAttrs: false });

const props = withDefaults(
  defineProps<{
    value: string;
    tokens?: readonly UiTokenInputToken[];
    placeholder?: string;
    disabled?: boolean;
    acceptDrop?: (source: UiDragSource) => boolean;
  }>(),
  { tokens: () => [], placeholder: undefined, disabled: false, acceptDrop: undefined },
);

const emit = defineEmits<{
  'update:value': [value: string];
  selection: [selection: { start: number; end: number }];
  drop: [event: { source: UiDragSource; selection: { start: number; end: number }; nativeEvent?: Event }];
}>();

const input = ref<HTMLElement>();
const dropRoot = ref<HTMLElement>();
const composing = ref(false);
const internalDrag = ref<{ start: number; end: number; source: string }>();
const internalDropHovered = ref(false);
const dropSelection = ref<{ start: number; end: number }>();
const lastSelection = ref({ start: 0, end: 0 });
const instanceId = createUiTreeInstanceId();
let renderedSignature = '';
let history = [props.value];
let historyIndex = 0;
let pendingSelection: { start: number; end: number } | undefined;
let draggedToken: HTMLElement | undefined;

const validTokens = computed(() => {
  const result: UiTokenInputToken[] = [];
  let previous = 0;
  for (const token of [...props.tokens].sort(
    (left, right) => left.start - right.start || left.end - right.end,
  )) {
    if (
      !Number.isInteger(token.start) ||
      !Number.isInteger(token.end) ||
      token.start < previous ||
      token.end <= token.start ||
      token.end > props.value.length ||
      props.value.slice(token.start, token.end) !== token.source
    )
      continue;
    result.push(token);
    previous = token.end;
  }
  return result;
});

function ariaText(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function signature() {
  return `${props.value}\u0000${props.disabled}\u0000${validTokens.value.map((token) => `${token.start}:${token.end}:${token.label}:${token.title || ''}`).join('|')}`;
}

function appendText(parent: HTMLElement, text: string) {
  if (text) parent.append(document.createTextNode(text));
}

function tokenElement(token: UiTokenInputToken) {
  const element = document.createElement('span');
  element.className = 'ui-token-input__token';
  element.contentEditable = 'false';
  element.draggable = !props.disabled;
  element.dataset.source = token.source;
  element.dataset.start = String(token.start);
  element.dataset.end = String(token.end);
  element.title = token.title || token.source;
  element.setAttribute('aria-label', token.label);
  element.textContent = token.label;
  return element;
}

function renderValue(selectionToRestore = pendingSelection) {
  const element = input.value;
  if (!element || composing.value) return;
  const nextSignature = signature();
  if (nextSignature === renderedSignature && sourceValue(element) === props.value) return;
  element.replaceChildren();
  let cursor = 0;
  for (const token of validTokens.value) {
    appendText(element, props.value.slice(cursor, token.start));
    element.append(tokenElement(token));
    cursor = token.end;
  }
  appendText(element, props.value.slice(cursor));
  if (!element.childNodes.length) element.append(document.createTextNode(''));
  renderedSignature = nextSignature;
  pendingSelection = undefined;
  if (selectionToRestore && document.activeElement === element) restoreSelection(selectionToRestore);
}

function sourceValue(element = input.value) {
  if (!element) return '';
  return Array.from(element.childNodes).map(sourceOfNode).join('');
}

function sourceOfNode(node: Node): string {
  if (node.nodeType === Node.TEXT_NODE) return node.textContent || '';
  if (node instanceof HTMLBRElement) return '\n';
  if (node instanceof HTMLElement && node.dataset.source !== undefined) return node.dataset.source;
  const content = Array.from(node.childNodes).map(sourceOfNode).join('');
  // Chromium may turn a pasted line into a block. Preserve the line break rather than silently deleting it.
  return /^(DIV|P)$/.test((node as Element).tagName || '') && node.nextSibling ? `${content}\n` : content;
}

function offsetInside(node: Node, container: Node, offset: number): number {
  if (node === container) {
    if (node.nodeType === Node.TEXT_NODE) return Math.min(offset, node.textContent?.length || 0);
    return Array.from(node.childNodes)
      .slice(0, Math.min(offset, node.childNodes.length))
      .reduce((total, child) => total + sourceOfNode(child).length, 0);
  }
  let total = 0;
  for (const child of Array.from(node.childNodes)) {
    if (child === container || child.contains(container))
      return total + offsetInside(child, container, offset);
    total += sourceOfNode(child).length;
  }
  return total;
}

function offsetOfPoint(container: Node, offset: number) {
  const element = input.value;
  if (!element) return 0;
  if (container === element) return offsetInside(element, container, offset);
  let total = 0;
  for (const node of Array.from(element.childNodes)) {
    const length = sourceOfNode(node).length;
    if (node instanceof HTMLElement && node.dataset.source !== undefined && node.contains(container)) {
      // Atomic visual labels never contribute to source offsets. A caret inside
      // a token resolves to its nearest source boundary.
      return total + (offset > 0 ? length : 0);
    }
    if (node === container || node.contains(container)) return total + offsetInside(node, container, offset);
    total += length;
  }
  return total;
}

function selection() {
  const element = input.value;
  const current = element?.ownerDocument.getSelection();
  if (!element || !current?.rangeCount || !current.anchorNode || !element.contains(current.anchorNode)) {
    return lastSelection.value;
  }
  return normalizeSelection({
    start: offsetOfPoint(current.anchorNode, current.anchorOffset),
    end: offsetOfPoint(current.focusNode || current.anchorNode, current.focusOffset),
  });
}

function rangeForOffset(offset: number) {
  const element = input.value!;
  const range = document.createRange();
  let remaining = Math.max(0, Math.min(offset, props.value.length));
  for (const node of Array.from(element.childNodes)) {
    const source = sourceOfNode(node);
    if (remaining <= source.length) {
      if (node.nodeType === Node.TEXT_NODE) range.setStart(node, remaining);
      else if (remaining === 0) range.setStartBefore(node);
      else range.setStartAfter(node);
      range.collapse(true);
      return range;
    }
    remaining -= source.length;
  }
  range.selectNodeContents(element);
  range.collapse(false);
  return range;
}

function restoreSelection(next: { start: number; end: number }) {
  const element = input.value;
  if (!element) return;
  const documentSelection = element.ownerDocument.getSelection();
  if (!documentSelection) return;
  const range = rangeForOffset(next.start);
  const ending = rangeForOffset(next.end);
  range.setEnd(ending.startContainer, ending.startOffset);
  documentSelection.removeAllRanges();
  documentSelection.addRange(range);
  lastSelection.value = normalizeSelection(next);
}

function focusSelection(start: number, end = start) {
  // A host can insert source and focus in the same Vue tick, before the
  // queued decoration render. Resolve offsets against the current source DOM.
  renderValue();
  input.value?.focus();
  restoreSelection(normalizeSelection({ start, end }));
  emitSelection();
}

function emitSelection() {
  const current = normalizeSelection(selection());
  lastSelection.value = current;
  emit('selection', current);
}

function normalizeSelection(current: { start: number; end: number }) {
  return current.start <= current.end ? current : { start: current.end, end: current.start };
}

function rememberHistory(next: string) {
  if (history[historyIndex] === next) return;
  history = [...history.slice(0, historyIndex + 1), next];
  historyIndex = history.length - 1;
}

function applyValue(next: string, nextSelection: { start: number; end: number }, record = true) {
  if (record) rememberHistory(next);
  lastSelection.value = normalizeSelection(nextSelection);
  pendingSelection = nextSelection;
  emit('update:value', next);
  // Controlled parents update on the next tick. Rendering here keeps token deletion and movement responsive.
  nextTick(() => renderValue(nextSelection));
}

function onInput() {
  if (props.disabled || composing.value) return;
  const next = sourceValue();
  renderedSignature = '';
  rememberHistory(next);
  emit('update:value', next);
  emitSelection();
}

function onBeforeInput(event: InputEvent) {
  if (props.disabled || composing.value) return;
  if (event.inputType === 'historyUndo' || event.inputType === 'historyRedo') {
    event.preventDefault();
    moveHistory(event.inputType === 'historyUndo' ? -1 : 1);
    return;
  }
  const current = normalizeSelection(selection());
  const replacesToken = current.start !== current.end && tokenIntersecting(current.start, current.end);
  if (event.inputType === 'insertParagraph' || event.inputType === 'insertLineBreak') {
    event.preventDefault();
    const next = props.value.slice(0, current.start) + '\n' + props.value.slice(current.end);
    applyValue(next, { start: current.start + 1, end: current.start + 1 });
    return;
  }
  if (replacesToken && event.inputType === 'insertText') {
    event.preventDefault();
    const inserted = event.data || '';
    const next = props.value.slice(0, current.start) + inserted + props.value.slice(current.end);
    applyValue(next, { start: current.start + inserted.length, end: current.start + inserted.length });
  }
}

function onCompositionStart() {
  composing.value = true;
}

function onCompositionEnd() {
  composing.value = false;
  onInput();
  nextTick(() => renderValue(selection()));
}

function tokenIntersecting(start: number, end: number) {
  return validTokens.value.find((token) => token.start < end && token.end > start);
}

function moveHistory(delta: number) {
  const nextIndex = historyIndex + delta;
  if (nextIndex < 0 || nextIndex >= history.length) return;
  historyIndex = nextIndex;
  const cursor = Math.min(selection().start, history[historyIndex]!.length);
  applyValue(history[historyIndex]!, { start: cursor, end: cursor }, false);
}

function onKeydown(event: KeyboardEvent) {
  if (props.disabled || event.isComposing) return;
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z') {
    event.preventDefault();
    moveHistory(event.shiftKey ? 1 : -1);
    return;
  }
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'y') {
    event.preventDefault();
    moveHistory(1);
    return;
  }
  const current = normalizeSelection(selection());
  const start = current.start;
  const end = current.end;
  if (start !== end) {
    if ((event.key === 'Backspace' || event.key === 'Delete') && tokenIntersecting(start, end)) {
      event.preventDefault();
      applyValue(props.value.slice(0, start) + props.value.slice(end), { start, end: start });
    }
    return;
  }
  if (event.key === 'Backspace') {
    const token = validTokens.value.find((item) => item.end === start);
    if (token) {
      event.preventDefault();
      applyValue(props.value.slice(0, token.start) + props.value.slice(token.end), {
        start: token.start,
        end: token.start,
      });
    }
  }
  if (event.key === 'Delete') {
    const token = validTokens.value.find((item) => item.start === start);
    if (token) {
      event.preventDefault();
      applyValue(props.value.slice(0, token.start) + props.value.slice(token.end), { start, end: start });
    }
  }
}

function onPaste(event: ClipboardEvent) {
  if (props.disabled) return;
  const text = event.clipboardData?.getData('text/plain');
  if (text === undefined) return;
  event.preventDefault();
  const current = normalizeSelection(selection());
  const next = props.value.slice(0, current.start) + text + props.value.slice(current.end);
  applyValue(next, { start: current.start + text.length, end: current.start + text.length });
}

function onCopy(event: ClipboardEvent) {
  const current = normalizeSelection(selection());
  event.clipboardData?.setData('text/plain', props.value.slice(current.start, current.end));
  event.preventDefault();
}

function onCut(event: ClipboardEvent) {
  if (props.disabled) return;
  const current = normalizeSelection(selection());
  event.clipboardData?.setData('text/plain', props.value.slice(current.start, current.end));
  event.preventDefault();
  if (current.start === current.end) return;
  applyValue(props.value.slice(0, current.start) + props.value.slice(current.end), {
    start: current.start,
    end: current.start,
  });
}

function onDragStart(event: DragEvent) {
  // Chromium can dispatch dragstart from the label's text node.
  const origin =
    event.target instanceof Element ? event.target : (event.target as Node | null)?.parentElement;
  const token = origin?.closest<HTMLElement>('[data-source]');
  if (!token || props.disabled) return;
  draggedToken?.classList.remove('ui-token-input__token--dragging');
  draggedToken = token;
  draggedToken.classList.add('ui-token-input__token--dragging');
  internalDrag.value = {
    start: Number(token.dataset.start),
    end: Number(token.dataset.end),
    source: token.dataset.source || '',
  };
  event.dataTransfer?.setData('application/x-ui-token-input', token.dataset.source || '');
  event.dataTransfer?.setData('text/plain', token.dataset.source || '');
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move';
}

function offsetAtPoint(x: number, y: number) {
  const document = input.value?.ownerDocument;
  if (!document || !input.value) return selection().start;
  const caretPosition = document.caretPositionFromPoint?.(x, y);
  if (caretPosition) return offsetOfPoint(caretPosition.offsetNode, caretPosition.offset);
  const caretRange = document.caretRangeFromPoint?.(x, y);
  return caretRange ? offsetOfPoint(caretRange.startContainer, caretRange.startOffset) : selection().start;
}

function setDropSelectionAtPoint(x: number, y: number) {
  const offset = offsetAtPoint(x, y);
  dropSelection.value = { start: offset, end: offset };
}

function onInternalDragOver(event: DragEvent) {
  // Never let a native drop insert an HTML fragment or a visible label.
  event.preventDefault();
  if (!internalDrag.value || props.disabled) return;
  internalDropHovered.value = true;
  setDropSelectionAtPoint(event.clientX, event.clientY);
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move';
}

function onInternalDragLeave(event: DragEvent) {
  const next = event.relatedTarget;
  if (!(next instanceof Node) || !dropRoot.value?.contains(next)) internalDropHovered.value = false;
}

function onDragEnd() {
  draggedToken?.classList.remove('ui-token-input__token--dragging');
  draggedToken = undefined;
  internalDrag.value = undefined;
  internalDropHovered.value = false;
  dropSelection.value = undefined;
}

function onInternalDrop(event: DragEvent) {
  event.preventDefault();
  const moved = internalDrag.value;
  draggedToken?.classList.remove('ui-token-input__token--dragging');
  draggedToken = undefined;
  internalDrag.value = undefined;
  internalDropHovered.value = false;
  dropSelection.value = undefined;
  if (props.disabled) return;
  const target = offsetAtPoint(event.clientX, event.clientY);
  if (!moved) {
    const text = event.dataTransfer?.getData('text/plain');
    if (text)
      applyValue(props.value.slice(0, target) + text + props.value.slice(target), {
        start: target + text.length,
        end: target + text.length,
      });
    return;
  }
  if (
    (target >= moved.start && target <= moved.end) ||
    props.value.slice(moved.start, moved.end) !== moved.source
  )
    return;
  const without = props.value.slice(0, moved.start) + props.value.slice(moved.end);
  const adjusted = target > moved.end ? target - moved.source.length : target;
  const next = without.slice(0, adjusted) + moved.source + without.slice(adjusted);
  applyValue(next, { start: adjusted + moved.source.length, end: adjusted + moved.source.length });
}

const { hovered: dropHovered, rejected: dropRejected } = useUiDropTarget(dropRoot, {
  resolve: (_origin, y, _position, _source, x) => {
    setDropSelectionAtPoint(x ?? 0, y);
    return { instanceId, kind: 'root', position: 'inside' } as UiDropTarget;
  },
  allow: (event: UiTreeDropEvent) => !props.disabled && Boolean(props.acceptDrop?.(event.source)),
  drop: (event: UiTreeDropEvent) => {
    const current = dropSelection.value ?? normalizeSelection(selection());
    dropSelection.value = undefined;
    emit('drop', { source: event.source, selection: current, nativeEvent: event.nativeEvent });
  },
  cancelWhenPointerLeaves: true,
});

const showsAcceptedDrop = computed(
  () => (Boolean(dropHovered.value) && !dropRejected.value) || internalDropHovered.value,
);
const showsDropIndicator = computed(() => showsAcceptedDrop.value && Boolean(dropSelection.value));
const dropIndicatorStyle = computed(() => {
  const editor = input.value;
  const root = dropRoot.value;
  const offset = dropSelection.value?.start;
  if (!editor || !root || offset === undefined) return undefined;
  const range = rangeForOffset(offset);
  const caret = typeof range.getBoundingClientRect === 'function' ? range.getBoundingClientRect() : undefined;
  const rootRect = root.getBoundingClientRect();
  if (caret?.height) {
    return {
      height: `${caret.height}px`,
      left: `${caret.left - rootRect.left}px`,
      top: `${caret.top - rootRect.top}px`,
    };
  }
  const editorRect = editor.getBoundingClientRect();
  const lineHeight = Number.parseFloat(getComputedStyle(editor).lineHeight) || 20;
  return {
    height: `${lineHeight}px`,
    left: `${editorRect.left - rootRect.left + 11}px`,
    top: `${editorRect.top - rootRect.top + 8}px`,
  };
});

watch(
  [() => props.value, () => props.disabled, validTokens],
  () => {
    // Rule changes create a keyed editor instance. Within one instance a value
    // supplied by the host (for example a directory insertion) remains undoable.
    rememberHistory(props.value);
    nextTick(() => renderValue(selection()));
  },
  {
    deep: true,
    flush: 'post',
  },
);
onMounted(() => renderValue());
onBeforeUnmount(() => {
  draggedToken?.classList.remove('ui-token-input__token--dragging');
  draggedToken = undefined;
  internalDrag.value = undefined;
});

defineExpose({ selection, focusSelection });
</script>

<template>
  <div
    ref="dropRoot"
    class="ui-token-input"
    data-ui-drop-root
    tabindex="-1"
    :class="[
      $attrs.class,
      {
        'ui-token-input--disabled': disabled,
        'ui-token-input--drop-active': showsAcceptedDrop,
        'ui-token-input--drop-rejected': dropHovered && dropRejected,
      },
    ]"
    :style="$attrs.style"
  >
    <div
      ref="input"
      class="ui-token-input__editor"
      :contenteditable="!disabled"
      role="textbox"
      aria-multiline="true"
      :aria-label="ariaText($attrs['aria-label'])"
      :aria-labelledby="ariaText($attrs['aria-labelledby'])"
      :aria-describedby="ariaText($attrs['aria-describedby'])"
      :aria-disabled="disabled"
      :data-placeholder="placeholder"
      :tabindex="disabled ? -1 : 0"
      @beforeinput="onBeforeInput"
      @input="onInput"
      @keydown="onKeydown"
      @keyup="emitSelection"
      @click="emitSelection"
      @mouseup="emitSelection"
      @paste="onPaste"
      @copy="onCopy"
      @cut="onCut"
      @compositionstart="onCompositionStart"
      @compositionend="onCompositionEnd"
      @dragstart="onDragStart"
      @dragover="onInternalDragOver"
      @dragleave="onInternalDragLeave"
      @dragend="onDragEnd"
      @drop="onInternalDrop"
    />
    <span
      v-if="showsDropIndicator"
      aria-hidden="true"
      class="ui-token-input__drop-caret"
      :style="dropIndicatorStyle"
    />
  </div>
</template>

<style scoped>
.ui-token-input {
  position: relative;
  min-width: 0;
}
.ui-token-input__editor {
  box-sizing: border-box;
  min-height: 120px;
  width: 100%;
  padding: 8px 11px;
  color: var(--muyun-text, rgba(0, 0, 0, 0.88));
  background: var(--muyun-surface, #fff);
  border: 1px solid var(--muyun-border, #d9d9d9);
  border-radius: 6px;
  font: inherit;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
  outline: none;
}
.ui-token-input__editor:focus {
  border-color: var(--muyun-primary, #1677ff);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--muyun-primary, #1677ff) 15%, transparent);
}
.ui-token-input__editor:empty::before {
  content: attr(data-placeholder);
  color: var(--muyun-text-secondary, rgba(0, 0, 0, 0.45));
  pointer-events: none;
}
.ui-token-input__editor :deep(.ui-token-input__token) {
  /* An inline block exposes its box edge as the baseline in Chromium. That
   * lifts the label text above neighbouring formula characters. An inline
   * flex container uses its text baseline instead. */
  display: inline-flex;
  align-items: baseline;
  max-width: 100%;
  margin: 0 1px;
  padding: 0 6px;
  overflow: hidden;
  color: var(--muyun-primary, #1677ff);
  text-overflow: ellipsis;
  vertical-align: baseline;
  white-space: nowrap;
  background: color-mix(in srgb, var(--muyun-primary, #1677ff) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--muyun-primary, #1677ff) 28%, transparent);
  border-radius: 4px;
  cursor: grab;
  user-select: all;
}
.ui-token-input--disabled .ui-token-input__editor {
  cursor: not-allowed;
  color: var(--muyun-text-secondary, rgba(0, 0, 0, 0.45));
  background: var(--muyun-fill, #f5f5f5);
}
.ui-token-input--disabled .ui-token-input__editor :deep(.ui-token-input__token) {
  cursor: not-allowed;
}
.ui-token-input__editor :deep(.ui-token-input__token--dragging) {
  opacity: 0.55;
  cursor: grabbing;
}
.ui-token-input--drop-active .ui-token-input__editor {
  border-color: var(--muyun-primary, #1677ff);
  background: color-mix(in srgb, var(--muyun-primary, #1677ff) 4%, var(--muyun-surface, #fff));
}
.ui-token-input__drop-caret {
  position: absolute;
  z-index: 1;
  width: 2px;
  pointer-events: none;
  background: var(--muyun-primary, #1677ff);
  border-radius: 1px;
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--muyun-primary, #1677ff) 22%, transparent);
  animation: ui-token-input-drop-caret 0.9s steps(2, start) infinite;
}
.ui-token-input--drop-rejected .ui-token-input__editor {
  border-color: var(--muyun-danger-text, #ff4d4f);
  outline: 1px dashed var(--muyun-danger-text, #ff4d4f);
  outline-offset: 2px;
}
@keyframes ui-token-input-drop-caret {
  50% {
    opacity: 0.35;
  }
}
@media (prefers-reduced-motion: reduce) {
  .ui-token-input__drop-caret {
    animation: none;
  }
}
</style>
