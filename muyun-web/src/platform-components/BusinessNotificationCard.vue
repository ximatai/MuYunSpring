<script setup lang="ts">
import { computed } from 'vue';
import { useLanguageContext } from '@muyun/web-core';
import { UiActionButton, UiIcon } from '@muyun/vue-ui-antdv';
import type { WebBusinessNotification, WebBusinessNotificationAction } from '@muyun/web-contracts';
import DateTimeText from './DateTimeText.vue';

const props = withDefaults(
  defineProps<{
    notification: WebBusinessNotification;
    hasAccessory: boolean;
    busyAction?: string;
    recordHandled: boolean;
  }>(),
  { busyAction: undefined },
);
const emit = defineEmits<{ close: []; action: [action: WebBusinessNotificationAction] }>();
const language = useLanguageContext();
const tone = computed(() => (props.notification.tone === 'danger' ? 'danger' : 'default'));
function actionsFor(placement: 'leading' | 'trailing') {
  return props.notification.actions.filter((action) => (action.placement ?? 'leading') === placement);
}
function unavailable(action: WebBusinessNotificationAction) {
  return Boolean(props.busyAction) || (props.recordHandled && action.kind === 'record');
}
</script>

<template>
  <article
    class="business-notification-card"
    :class="[
      `business-notification-card--${tone}`,
      { 'business-notification-card--with-accessory': hasAccessory },
    ]"
  >
    <button
      v-if="notification.dismissible"
      class="business-notification-close"
      type="button"
      :aria-label="language.t('platform.screen.BusinessNotificationPanel.dismiss-reminder', '关闭提醒')"
      @click="emit('close')"
    >
      <UiIcon name="close" />
    </button>
    <div class="business-notification-card-surface">
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
        <p v-if="notification.subtitle" class="business-notification-subtitle">
          {{ notification.subtitle }}
        </p>
        <p class="business-notification-content">{{ notification.content }}</p>
        <div v-if="notification.actions.length" class="business-notification-actions">
          <div v-if="actionsFor('leading').length" class="business-notification-action-region">
            <UiActionButton
              v-for="action in actionsFor('leading')"
              :key="action.key"
              density="compact"
              :emphasis="action === notification.actions[0] ? 'primary' : 'secondary'"
              :intent="action.kind !== 'navigate' && action.danger ? 'danger' : 'normal'"
              :loading="busyAction === action.key"
              :disabled="unavailable(action)"
              @click="emit('action', action)"
            >
              {{ action.label }}
            </UiActionButton>
          </div>
          <div
            v-if="actionsFor('trailing').length"
            class="business-notification-action-region business-notification-action-region--trailing"
          >
            <UiActionButton
              v-for="action in actionsFor('trailing')"
              :key="action.key"
              density="compact"
              emphasis="secondary"
              :intent="action.kind !== 'navigate' && action.danger ? 'danger' : 'normal'"
              :loading="busyAction === action.key"
              :disabled="unavailable(action)"
              @click="emit('action', action)"
            >
              {{ action.label }}
            </UiActionButton>
          </div>
        </div>
      </div>
      <div v-if="hasAccessory" class="business-notification-accessory">
        <slot name="accessory" :notification="notification" />
      </div>
    </div>
  </article>
</template>

<style scoped>
.business-notification-card {
  --business-notification-accent: var(--muyun-theme-base);
  --business-notification-axis-width: 3px;
  --business-notification-header-indent: 17px;
  --business-notification-header-right-gutter: 0px;
  position: relative;
  border-radius: 12px;
  box-shadow: 0 16px 40px rgb(15 23 42 / 16%);
  pointer-events: auto;
  animation: notification-shadow-arrive 0.16s ease-out 0.6864s both;
}
.business-notification-card-surface {
  position: relative;
  display: grid;
  gap: 9px;
  padding: 18px;
  border: 1px solid color-mix(in srgb, var(--business-notification-accent) 40%, var(--muyun-border));
  border-left: var(--business-notification-axis-width) solid var(--business-notification-accent);
  border-radius: 12px;
  background: var(--muyun-surface);
  animation: notification-unfold 0.6864s cubic-bezier(0.22, 0.8, 0.2, 1) both;
}
.business-notification-card::before {
  position: absolute;
  z-index: 2;
  top: 0;
  bottom: 0;
  right: 0;
  width: var(--business-notification-axis-width);
  border-radius: 8px 0 0 8px;
  background: var(--business-notification-accent);
  content: '';
  pointer-events: none;
  opacity: 0;
  animation: notification-axis-unfold 0.6864s cubic-bezier(0.22, 0.8, 0.2, 1) both;
}
.business-notification-card--danger {
  --business-notification-accent: var(--muyun-danger-text);
}
.business-notification-card--with-accessory .business-notification-card-surface {
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
  transition:
    opacity 0.16s ease,
    transform 0.16s ease;
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
@media (hover: none) {
  .business-notification-close {
    opacity: 1;
    pointer-events: auto;
    transform: none;
  }
}
@keyframes notification-unfold {
  0% {
    clip-path: inset(100% 0 0 100%);
  }
  48.5% {
    clip-path: inset(0 0 0 100%);
  }
  100% {
    clip-path: inset(0);
  }
}
@keyframes notification-shadow-arrive {
  from {
    box-shadow: 0 16px 40px rgb(15 23 42 / 0%);
  }
  to {
    box-shadow: 0 16px 40px rgb(15 23 42 / 16%);
  }
}
@keyframes notification-axis-unfold {
  0% {
    opacity: 1;
    top: 100%;
    bottom: 0;
    right: 0;
  }
  48.5% {
    top: 0;
    bottom: 0;
    right: 0;
    opacity: 1;
  }
  99% {
    top: 0;
    bottom: 0;
    right: calc(100% - var(--business-notification-axis-width));
    opacity: 1;
  }
  100% {
    top: 0;
    bottom: 0;
    right: calc(100% - var(--business-notification-axis-width));
    opacity: 0;
  }
}
@media (prefers-reduced-motion: reduce) {
  .business-notification-card,
  .business-notification-card-surface,
  .business-notification-card::before {
    animation-duration: 0.01ms;
  }
  .business-notification-card {
    animation-delay: 0s;
  }
  .business-notification-close {
    transition: none;
  }
}
</style>
