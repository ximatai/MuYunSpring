<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import type { ActionControlContext } from '@ximatai/muyun-web-app';

const props = defineProps<{ context: Readonly<ActionControlContext> }>();
const held = ref(false);
let interaction: AbortController | undefined;
let pointer: { element: HTMLElement; id: number } | undefined;
let key: string | undefined;

function start() {
  if (props.context.disabled || !props.context.active || props.context.signal.aborted || held.value)
    return false;
  held.value = true;
  interaction = new AbortController();
  // Start the business resource here using interaction.signal. Late setup must
  // release its result if the signal was aborted while initialization was pending.
  return true;
}
function stop() {
  held.value = false;
  interaction?.abort();
  interaction = undefined;
  // Release the business resource here. Cleanup must be idempotent.
  const captured = pointer;
  pointer = undefined;
  key = undefined;
  if (captured?.element.hasPointerCapture?.(captured.id)) captured.element.releasePointerCapture(captured.id);
}
function pointerDown(event: PointerEvent) {
  if (event.button !== 0 || !event.isPrimary || !start()) return;
  const element = event.currentTarget as HTMLElement;
  pointer = { element, id: event.pointerId };
  element.setPointerCapture?.(event.pointerId);
  event.preventDefault();
}
function pointerEnd(event: PointerEvent) {
  if (pointer?.id === event.pointerId) stop();
}
function keyDown(event: KeyboardEvent) {
  if (event.key !== ' ' && event.key !== 'Enter') return;
  event.preventDefault();
  if (!event.repeat && start()) key = event.key;
}
function keyUp(event: KeyboardEvent) {
  if (event.key === key) {
    event.preventDefault();
    stop();
  }
}
function visibilityChanged() {
  if (document.hidden) stop();
}
watch(
  () => props.context.signal,
  (signal, _, onCleanup) => {
    stop();
    signal.addEventListener('abort', stop, { once: true });
    onCleanup(() => signal.removeEventListener('abort', stop));
  },
  { immediate: true, flush: 'sync' },
);
onMounted(() => {
  window.addEventListener('blur', stop);
  document.addEventListener('visibilitychange', visibilityChanged);
});
onBeforeUnmount(() => {
  stop();
  window.removeEventListener('blur', stop);
  document.removeEventListener('visibilitychange', visibilityChanged);
});
</script>

<template>
  <button
    type="button"
    class="hold-action-example"
    :disabled="context.disabled"
    :aria-label="context.title"
    :title="context.title"
    :aria-pressed="held"
    @pointerdown="pointerDown"
    @pointerup="pointerEnd"
    @pointercancel="pointerEnd"
    @lostpointercapture="pointerEnd"
    @keydown="keyDown"
    @keyup="keyUp"
    @blur="stop"
    @contextmenu.prevent
  >
    <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="12" r="8" fill="none" stroke="currentColor" stroke-width="2" />
      <circle cx="12" cy="12" r="4" fill="currentColor" />
    </svg>
  </button>
</template>

<style scoped>
.hold-action-example {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: 1px solid currentColor;
  border-radius: 6px;
  color: var(--muyun-primary-color, #1677ff);
  background: transparent;
  cursor: pointer;
  touch-action: none;
  user-select: none;
}
.hold-action-example[aria-pressed='true'] {
  background: #e6f4ff;
}
.hold-action-example:disabled {
  opacity: 0.45;
  cursor: default;
}
</style>
