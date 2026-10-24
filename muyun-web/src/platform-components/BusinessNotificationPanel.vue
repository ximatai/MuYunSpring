<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, useId, watch } from 'vue';
import { businessNotificationExpired, presentationLabel, useLanguageContext } from '@muyun/web-core';
import { UiIcon, useUiBlockingOverlayState, uiOverlayLayers } from '@muyun/vue-ui-antdv';
import type { WebBusinessNotification, WebBusinessNotificationAction } from '@muyun/web-contracts';
import BusinessNotificationCard from './BusinessNotificationCard.vue';
import { useNotificationDeckMotion } from './useNotificationDeckMotion';

defineOptions({ name: 'BusinessNotificationPanel' });
const props = defineProps<{
  notifications: WebBusinessNotification[];
  /** Opt a card into the optional right-hand attachment area. */
  hasAccessory?: (notification: WebBusinessNotification) => boolean;
  executeAction: (
    notification: WebBusinessNotification,
    action: WebBusinessNotificationAction,
  ) => void | boolean | Promise<void | boolean>;
}>();
// Dismiss affects only this page session; the owning business keeps its processing state.
const emit = defineEmits<{ dismiss: [notificationId: string] }>();
const language = useLanguageContext();
const overlayOpen = useUiBlockingOverlayState();
const expanded = ref(false);
const restoredTopId = ref<string>();
const dismissed = ref(new Set<string>());
const handled = ref(new Set<string>());
const executing = shallowRef(new Map<string, { key: string; token: symbol }>());
const now = ref(Date.now());
const panelId = useId();
const panelElement = ref<HTMLElement>();
const bell = ref<HTMLButtonElement>();
const deckMotion = useNotificationDeckMotion(panelElement, expanded);
const { moving } = deckMotion;
const latest = computed(() => {
  const items = new Map<string, WebBusinessNotification>();
  for (const notice of props.notifications) {
    items.delete(notice.id);
    items.set(notice.id, notice);
  }
  return items;
});
const active = computed(() =>
  [...latest.value.values()]
    .reverse()
    .filter((notice) => !dismissed.value.has(notice.id) && !businessNotificationExpired(notice, now.value)),
);
const display = computed(() =>
  active.value.map((notice) => ({
    ...notice,
    title: presentationLabel(notice.title, notice.textResources?.title, language),
    subtitle: presentationLabel(notice.subtitle ?? '', notice.textResources?.subtitle, language),
    content: presentationLabel(notice.content, notice.textResources?.content, language),
    actions: notice.actions.map((action) => ({
      ...action,
      label: presentationLabel(action.label, notice.textResources?.[`action/${action.key}/label`], language),
    })),
  })),
);
const hasPersistent = computed(() => active.value.some((notice) => notice.displayPolicy === 'persistent'));
const floating = computed(() =>
  display.value.filter(
    (notice) =>
      (!overlayOpen.value || !hasPersistent.value || notice.displayPolicy === 'persistent') &&
      (!recordHandled(notice.id) ||
        notice.actions.some((action) => action.kind === 'navigate') ||
        !notice.actions.length),
  ),
);
const top = computed(() => floating.value[0]);
const depth = computed(() => Math.max(0, Math.min(floating.value.length - 1, 2)));
const stackPositions = computed(() => new Map(floating.value.map((notice, index) => [notice.id, index])));
const deckCards = computed(() =>
  display.value.map((notice, index) => ({
    notice,
    stackIndex: stackPositions.value.get(notice.id) ?? -1,
    zIndex: display.value.length - index + depth.value,
  })),
);
watch([deckCards, overlayOpen], deckMotion.stop);
const countLabel = computed(() =>
  language.t('platform.notifications.session-count', '本次提醒（{count} 条）', {
    count: active.value.length,
  }),
);

const sessionHint = computed(() =>
  language.t(
    'platform.notifications.session-note',
    '仅临时保留在本次页面中，刷新或退出后清空。关闭提醒不代表业务已处理，待办请到业务页面查看。',
  ),
);

let expiryTimer: ReturnType<typeof setTimeout> | undefined;
function scheduleExpiry() {
  clearTimeout(expiryTimer);
  now.value = Date.now();
  const future = [...latest.value.values()]
    .map((item) => Date.parse(item.expiresAt ?? ''))
    .filter((time) => time > now.value);
  if (future.length)
    expiryTimer = setTimeout(scheduleExpiry, Math.min(Math.min(...future) - now.value, 2147483647));
}
watch(() => props.notifications.map((item) => [item.id, item.expiresAt]), scheduleExpiry, {
  immediate: true,
});
watch(
  () => [...latest.value.keys()],
  (ids) => {
    handled.value = new Set([...handled.value].filter((id) => ids.includes(id)));
    executing.value = new Map([...executing.value].filter(([id]) => ids.includes(id)));
  },
);
watch(
  () => active.value.length,
  (count) => {
    if (!count) expanded.value = false;
  },
);
let disposed = false;
onBeforeUnmount(() => {
  disposed = true;
  clearTimeout(expiryTimer);
});
function dismissCard(id: string) {
  if (!latest.value.get(id)?.dismissible) return;
  dismissed.value = new Set([...dismissed.value, id]);
  emit('dismiss', id);
}
function busyAction(id: string) {
  return executing.value.get(id)?.key;
}
function recordHandled(id: string) {
  return handled.value.has(id);
}
async function run(notice: WebBusinessNotification, action: WebBusinessNotificationAction) {
  const original = latest.value.get(notice.id);
  const originalAction = original?.actions.find((item) => item.key === action.key);
  if (
    !original ||
    !originalAction ||
    dismissed.value.has(original.id) ||
    businessNotificationExpired(original) ||
    busyAction(original.id) ||
    (originalAction.kind === 'record' && recordHandled(original.id))
  )
    return;
  const signature = JSON.stringify(originalAction);
  const token = Symbol(original.id);
  executing.value = new Map(executing.value).set(original.id, { key: originalAction.key, token });
  try {
    const result = await props.executeAction(original, originalAction);
    const current = latest.value.get(original.id);
    if (
      disposed ||
      executing.value.get(original.id)?.token !== token ||
      result === false ||
      dismissed.value.has(original.id) ||
      !current ||
      JSON.stringify(current.actions.find((item) => item.key === originalAction.key)) !== signature ||
      businessNotificationExpired(current)
    )
      return;
    if (originalAction.dismissOnSuccess) {
      dismissed.value = new Set([...dismissed.value, original.id]);
      emit('dismiss', original.id);
    } else if (original.displayPolicy === 'persistent' && originalAction.kind === 'record') {
      handled.value = new Set([...handled.value, original.id]);
    }
  } finally {
    if (executing.value.get(original.id)?.token === token) {
      const next = new Map(executing.value);
      next.delete(original.id);
      executing.value = next;
    }
  }
}
async function openPanel() {
  if (await deckMotion.arrange(true)) panelElement.value?.focus();
}
async function closePanel() {
  restoredTopId.value = top.value?.id;
  void deckMotion.arrange(false);
  await nextTick();
  bell.value?.focus();
}
</script>

<template>
  <Teleport to="body" :disabled="!hasPersistent">
    <aside
      v-if="active.length"
      :hidden="overlayOpen && !hasPersistent"
      class="business-notification-panel"
      :aria-label="language.t('platform.screen.BusinessNotificationPanel.business-reminders', '业务提醒')"
      aria-live="polite"
      @keydown.esc.stop="closePanel"
    >
      <section
        :id="panelId"
        ref="panelElement"
        :class="[
          expanded ? 'business-notification-current' : 'business-notification-stack',
          {
            'business-notification-stack--restored': top?.id === restoredTopId,
            'business-notification-deck--moving': moving,
          },
        ]"
        :style="{ '--notification-stack-depth': depth }"
        tabindex="-1"
        :aria-label="countLabel"
      >
        <template v-if="!expanded">
          <div
            v-for="edge in depth"
            :key="`edge-${edge}`"
            data-notification-stack-edge
            class="business-notification-stack-edge"
            :style="{ '--notification-stack-index': edge, zIndex: depth - edge }"
            aria-hidden="true"
          />
        </template>
        <div
          v-for="{ notice, stackIndex, zIndex } in deckCards"
          :key="notice.id"
          :data-notification-id="notice.id"
          class="business-notification-deck-card"
          :class="{
            'business-notification-deck-card--top': notice.id === top?.id,
            'business-notification-deck-card--back': !expanded && notice.id !== top?.id,
          }"
          :style="{
            '--notification-stack-index': Math.min(depth, Math.max(0, stackIndex)),
            zIndex,
          }"
          :hidden="
            (overlayOpen && notice.displayPolicy !== 'persistent') ||
            (!expanded && (stackIndex < 0 || (!moving && stackIndex > 2)))
          "
          :inert="!expanded && notice.id !== top?.id"
        >
          <BusinessNotificationCard
            v-if="expanded || moving || notice.id === top?.id"
            :notification="notice"
            :has-accessory="hasAccessory?.(notice) ?? false"
            :busy-action="busyAction(notice.id)"
            :record-handled="recordHandled(notice.id)"
            @close="dismissCard(notice.id)"
            @action="run(notice, $event)"
          >
            <template #accessory="context"><slot name="accessory" v-bind="context" /></template>
          </BusinessNotificationCard>
        </div>
      </section>
      <button
        ref="bell"
        type="button"
        class="business-notification-bell"
        :aria-label="countLabel"
        :title="sessionHint"
        :aria-description="sessionHint"
        :aria-controls="panelId"
        :aria-expanded="expanded"
        @click="expanded ? closePanel() : openPanel()"
      >
        <UiIcon name="notification" /><span class="business-notification-badge" aria-hidden="true">{{
          active.length
        }}</span>
      </button>
    </aside>
  </Teleport>
</template>

<style scoped>
.business-notification-current {
  display: flex;
  flex-direction: column;
  gap: 20px;
  min-height: 0;
  max-height: calc(100dvh - 112px);
  padding: 16px;
  overflow-y: auto;
  overscroll-behavior: contain;
  pointer-events: auto;
  outline: none;
}
.business-notification-current :deep(.business-notification-card) {
  box-shadow: none;
}
.business-notification-deck-card {
  flex-shrink: 0;
  min-width: 0;
  transform-origin: top left;
}
.business-notification-deck-card[hidden] {
  display: none;
}
.business-notification-stack--restored :deep(.business-notification-card),
.business-notification-stack--restored :deep(.business-notification-card-surface),
.business-notification-stack--restored :deep(.business-notification-card::before),
.business-notification-current :deep(.business-notification-card),
.business-notification-current :deep(.business-notification-card-surface),
.business-notification-current :deep(.business-notification-card::before) {
  animation: none;
}
.business-notification-panel {
  width: min(480px, calc(100vw - 32px));
  position: fixed;
  right: 24px;
  bottom: 24px;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 16px;
  z-index: v-bind('uiOverlayLayers.notification');
  pointer-events: none;
}
.business-notification-panel[hidden] {
  display: none;
}
.business-notification-stack {
  position: absolute;
  right: 0;
  bottom: 64px;
  width: 100%;
  padding: 16px 16px calc(16px + var(--notification-stack-depth) * 10px);
  pointer-events: auto;
}
.business-notification-stack .business-notification-deck-card--top {
  position: relative;
}
.business-notification-stack-edge,
.business-notification-stack .business-notification-deck-card--back {
  position: absolute;
  top: 16px;
  left: 16px;
  right: 16px;
  bottom: calc(16px + var(--notification-stack-depth) * 10px);
  transform: translate(
    calc(var(--notification-stack-index) * 10px),
    calc(var(--notification-stack-index) * 10px)
  );
  pointer-events: none;
}
.business-notification-stack-edge {
  border: 1px solid var(--muyun-border);
  border-radius: 12px;
  background: var(--muyun-surface);
  box-shadow: 0 6px 16px rgb(15 23 42 / 8%);
}
.business-notification-deck--moving
  .business-notification-deck-card--back
  :deep(.business-notification-card) {
  box-shadow: none;
}
.business-notification-deck--moving {
  pointer-events: none;
}
.business-notification-deck--moving :deep(.business-notification-card),
.business-notification-deck--moving :deep(button) {
  pointer-events: none;
}
.business-notification-bell {
  position: relative;
  align-self: flex-end;
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border: 1px solid var(--muyun-border);
  border-radius: 50%;
  background: var(--muyun-surface);
  color: var(--muyun-primary);
  box-shadow: 0 4px 12px rgb(15 23 42 / 12%);
  pointer-events: auto;
  cursor: pointer;
}
.business-notification-badge {
  position: absolute;
  top: -6px;
  right: -6px;
  min-width: 20px;
  height: 20px;
  padding: 0 4px;
  border-radius: 10px;
  background: var(--muyun-theme-base);
  color: var(--muyun-surface);
  font-size: 11px;
  line-height: 20px;
}
@media (max-width: 720px) {
  .business-notification-panel {
    right: 16px;
    bottom: 16px;
  }
}
</style>
