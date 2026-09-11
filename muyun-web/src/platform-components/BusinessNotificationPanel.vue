<script setup lang="ts">
import { computed, ref } from 'vue';
import { UiActionButton, UiIcon } from '@muyun/vue-ui-antdv';
import type { WebBusinessNotification, WebBusinessNotificationAction } from '@muyun/web-contracts';
import DateTimeText from './DateTimeText.vue';

defineOptions({ name: 'BusinessNotificationPanel' });

const props = defineProps<{
  notifications: WebBusinessNotification[];
  /** Allows a consumer to opt an individual card into the optional right-hand attachment area. */
  hasAccessory?: (notification: WebBusinessNotification) => boolean;
  executeAction: (
    notification: WebBusinessNotification,
    action: WebBusinessNotificationAction,
  ) => void | Promise<void>;
}>();

const emit = defineEmits<{
  dismiss: [notificationId: string];
}>();

const executing = ref<string>();
const expanded = ref(false);
const orderedNotifications = computed(() => [
  ...props.notifications.filter((notification) => !notification.dismissible),
  ...props.notifications.filter((notification) => notification.dismissible),
]);
const visible = computed(() =>
  expanded.value ? orderedNotifications.value : orderedNotifications.value.slice(0, 3),
);
const hiddenCount = computed(() => Math.max(orderedNotifications.value.length - 3, 0));

function actionsFor(notification: WebBusinessNotification, placement: 'leading' | 'trailing') {
  return notification.actions.filter((action) => (action.placement ?? 'leading') === placement);
}

function toneFor(notification: WebBusinessNotification) {
  // Success describes the business outcome. It is not an alarm severity, so keep it on the
  // workbench primary colour rather than competing with the success-green feedback vocabulary.
  return notification.tone === 'danger' ? 'danger' : 'default';
}

function shouldShowAccessory(notification: WebBusinessNotification) {
  return props.hasAccessory?.(notification) ?? false;
}

async function run(notification: WebBusinessNotification, action: WebBusinessNotificationAction) {
  const key = `${notification.id}:${action.key}`;
  if (executing.value) return;
  executing.value = key;
  try {
    await props.executeAction(notification, action);
  } finally {
    if (executing.value === key) executing.value = undefined;
  }
}
</script>

<template>
  <aside
    v-if="visible.length"
    class="business-notification-panel"
    :class="{ 'business-notification-panel--expanded': expanded }"
    aria-live="polite"
    aria-label="业务提醒"
  >
    <article
      v-for="notification in visible"
      :key="notification.id"
      class="business-notification-card"
      :class="[
        `business-notification-card--${toneFor(notification)}`,
        { 'business-notification-card--with-accessory': shouldShowAccessory(notification) },
      ]"
    >
      <button
        v-if="notification.dismissible"
        class="business-notification-close"
        type="button"
        aria-label="关闭提醒"
        @click="emit('dismiss', notification.id)"
      >
        <UiIcon name="close" />
      </button>
      <div class="business-notification-copy">
        <header class="business-notification-header">
          <span class="business-notification-status-dot" aria-hidden="true" />
          <h2>{{ notification.title }}</h2>
          <DateTimeText
            v-if="notification.occurredAt"
            class="business-notification-time"
            :value="notification.occurredAt"
          />
        </header>
        <p v-if="notification.subtitle" class="business-notification-subtitle">{{ notification.subtitle }}</p>
        <p class="business-notification-content">{{ notification.content }}</p>
        <div v-if="notification.actions.length" class="business-notification-actions">
          <div v-if="actionsFor(notification, 'leading').length" class="business-notification-action-region">
            <UiActionButton
              v-for="action in actionsFor(notification, 'leading')"
              :key="action.key"
              density="compact"
              :emphasis="action === notification.actions[0] ? 'primary' : 'secondary'"
              :intent="action.kind !== 'navigate' && action.danger ? 'danger' : 'normal'"
              :loading="executing === `${notification.id}:${action.key}`"
              @click="run(notification, action)"
            >
              {{ action.label }}
            </UiActionButton>
          </div>
          <div
            v-if="actionsFor(notification, 'trailing').length"
            class="business-notification-action-region business-notification-action-region--trailing"
          >
            <UiActionButton
              v-for="action in actionsFor(notification, 'trailing')"
              :key="action.key"
              density="compact"
              emphasis="secondary"
              :intent="action.kind !== 'navigate' && action.danger ? 'danger' : 'normal'"
              :loading="executing === `${notification.id}:${action.key}`"
              @click="run(notification, action)"
            >
              {{ action.label }}
            </UiActionButton>
          </div>
        </div>
      </div>
      <div v-if="shouldShowAccessory(notification)" class="business-notification-accessory">
        <slot name="accessory" :notification="notification" />
      </div>
    </article>
    <button
      v-if="hiddenCount || expanded"
      class="business-notification-more"
      type="button"
      :aria-expanded="expanded"
      @click="expanded = !expanded"
    >
      {{ expanded ? '收起提醒' : `查看全部（还有 ${hiddenCount} 条）` }}
    </button>
  </aside>
</template>

<style scoped>
.business-notification-panel {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 1200;
  display: grid;
  width: min(480px, calc(100vw - 32px));
  gap: 12px;
  pointer-events: none;
}
.business-notification-panel--expanded {
  max-height: calc(100vh - 48px);
  overflow-y: auto;
  pointer-events: auto;
}
.business-notification-card {
  --business-notification-accent: var(--muyun-theme-base);
  --business-notification-header-indent: 17px;
  --business-notification-header-right-gutter: 0px;
  position: relative;
  display: grid;
  gap: 9px;
  padding: 18px;
  border: 1px solid color-mix(in srgb, var(--business-notification-accent) 40%, var(--muyun-border));
  border-left: 4px solid var(--business-notification-accent);
  border-radius: 12px;
  background: var(--muyun-surface);
  box-shadow: 0 16px 40px rgb(15 23 42 / 16%);
  pointer-events: auto;
  animation: notification-arrive 0.2s ease-out;
}
.business-notification-card--danger {
  --business-notification-accent: var(--muyun-danger-text);
}
.business-notification-card--with-accessory {
  --business-notification-accessory-width: 112px;
  grid-template-columns: minmax(0, 1fr);
  min-height: 168px;
}
.business-notification-copy {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.business-notification-accessory {
  position: absolute;
  right: calc(18px + var(--business-notification-header-right-gutter));
  bottom: 18px;
  width: var(--business-notification-accessory-width);
  height: 96px;
  min-width: 0;
}
.business-notification-card--with-accessory .business-notification-copy {
  min-height: 132px;
}
.business-notification-header {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding-right: var(--business-notification-header-right-gutter);
}
h2,
p {
  margin: 0;
}
h2 {
  color: var(--muyun-text);
  font-size: 16px;
  line-height: 22px;
}
.business-notification-status-dot {
  width: 9px;
  height: 9px;
  flex: none;
  border-radius: 50%;
  background: var(--business-notification-accent);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--business-notification-accent) 14%, transparent);
}
.business-notification-time {
  margin-left: auto;
  color: var(--muyun-text-muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  line-height: 20px;
  white-space: nowrap;
}
.business-notification-subtitle {
  margin-left: var(--business-notification-header-indent);
  color: var(--muyun-text-muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.business-notification-card--with-accessory .business-notification-subtitle,
.business-notification-card--with-accessory .business-notification-content,
.business-notification-card--with-accessory .business-notification-actions {
  margin-right: calc(var(--business-notification-accessory-width) + 10px);
}
.business-notification-content {
  margin-top: 8px;
  margin-left: var(--business-notification-header-indent);
  max-height: 100px;
  padding-right: 4px;
  overflow-y: auto;
  color: var(--muyun-text-body);
  font-size: 13px;
  line-height: 20px;
  white-space: pre-wrap;
}
.business-notification-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: auto;
  margin-left: var(--business-notification-header-indent);
  padding-top: 16px;
}
.business-notification-action-region {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.business-notification-action-region--trailing {
  margin-left: auto;
}
.business-notification-close {
  position: absolute;
  z-index: 1;
  top: -8px;
  right: -8px;
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: 1px solid var(--muyun-danger-border);
  border-radius: 50%;
  color: var(--muyun-danger-text);
  background: var(--muyun-danger-bg);
  box-shadow: 0 2px 6px rgb(15 23 42 / 16%);
  cursor: pointer;
  font-size: 15px;
  opacity: 0;
  pointer-events: none;
  transform: translate(2px, -2px);
  transition: opacity 0.16s ease, transform 0.16s ease;
}
.business-notification-card:hover .business-notification-close,
.business-notification-card:focus-within .business-notification-close {
  opacity: 1;
  pointer-events: auto;
  transform: none;
}
.business-notification-close:hover {
  background: color-mix(in srgb, var(--muyun-danger-text) 16%, var(--muyun-danger-bg));
  color: var(--muyun-danger-text);
}
.business-notification-more {
  justify-self: end;
  padding: 0 8px;
  border: 0;
  color: var(--muyun-text-muted);
  background: transparent;
  cursor: pointer;
  pointer-events: auto;
  font-size: 12px;
  text-align: right;
}
.business-notification-more:hover {
  color: var(--muyun-primary);
}
@keyframes notification-arrive {
  from {
    opacity: 0;
    transform: translateY(12px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
</style>
