<script setup lang="ts">
import { computed, ref } from 'vue';
import { UiActionButton, UiIcon } from '@muyun/vue-ui-antdv';
import type { WebBusinessNotification, WebBusinessNotificationAction } from '@muyun/web-contracts';

defineOptions({ name: 'BusinessNotificationPanel' });

const props = defineProps<{
  notifications: WebBusinessNotification[];
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
    <article v-for="notification in visible" :key="notification.id" class="business-notification-card">
      <button
        v-if="notification.dismissible"
        class="business-notification-close"
        type="button"
        aria-label="关闭提醒"
        @click="emit('dismiss', notification.id)"
      >
        <UiIcon name="close" />
      </button>
      <div class="business-notification-mark"><UiIcon name="notification" /></div>
      <div class="business-notification-copy">
        <h2>{{ notification.title }}</h2>
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
          <div v-if="actionsFor(notification, 'trailing').length" class="business-notification-action-region business-notification-action-region--trailing">
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
  width: min(388px, calc(100vw - 32px));
  gap: 10px;
  pointer-events: none;
}
.business-notification-panel--expanded {
  max-height: calc(100vh - 48px);
  overflow-y: auto;
  pointer-events: auto;
}
.business-notification-card {
  position: relative;
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  gap: 12px;
  padding: 20px 18px;
  border: 1px solid var(--muyun-border);
  border-radius: 14px;
  background: var(--muyun-surface);
  box-shadow: 0 18px 44px color-mix(in srgb, var(--muyun-text) 14%, transparent);
  pointer-events: auto;
  animation: notification-arrive 0.2s ease-out;
}
.business-notification-mark {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  color: var(--muyun-primary);
  background: color-mix(in srgb, var(--muyun-primary) 12%, var(--muyun-surface));
}
.business-notification-mark :deep(.anticon) {
  font-size: 18px;
}
.business-notification-copy {
  min-width: 0;
}
h2,
p {
  margin: 0;
}
h2 {
  margin-top: -2px;
  padding-right: 20px;
  color: var(--muyun-text);
  font-size: 14px;
  line-height: 21px;
}
.business-notification-subtitle {
  margin-top: 2px;
  color: var(--muyun-text-muted);
  font-size: 12px;
}
.business-notification-content {
  margin-top: 8px;
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
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--muyun-border);
}
.business-notification-action-region { display: flex; flex-wrap: wrap; gap: 8px; }
.business-notification-action-region--trailing { margin-left: auto; }
.business-notification-close {
  position: absolute;
  top: 10px;
  right: 10px;
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: 0;
  border-radius: 7px;
  color: var(--muyun-text-muted);
  background: transparent;
  cursor: pointer;
}
.business-notification-close:hover {
  background: var(--muyun-hover);
  color: var(--muyun-primary);
}
.business-notification-more {
  justify-self: end;
  padding: 0 8px;
  border: 0;
  color: var(--muyun-text-muted);
  background: transparent;
  cursor: pointer;
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
