<script setup lang="ts">
import UiIcon from './UiIcon.vue';
import type { UiRecordInlineAction } from '../types';

defineOptions({ name: 'UiRecordExplorerItem', inheritAttrs: false });

withDefaults(
  defineProps<{
    title: string;
    secondary?: string;
    tag?: string;
    muted?: boolean;
    selected?: boolean;
    actions?: UiRecordInlineAction[];
    clickable?: boolean;
  }>(),
  {
    secondary: undefined,
    tag: undefined,
    muted: false,
    selected: false,
    actions: undefined,
    clickable: false,
  },
);

const emit = defineEmits<{
  click: [];
  action: [action: UiRecordInlineAction];
}>();

function handleAction(event: MouseEvent, action: UiRecordInlineAction) {
  event.stopPropagation();
  if (action.disabled) {
    event.preventDefault();
    return;
  }
  emit('action', action);
}

function actionFallbackLabel(action: UiRecordInlineAction) {
  return action.title.trim().slice(0, 1);
}
</script>

<template>
  <span
    :class="[
      'ui-record-explorer-item',
      $attrs.class,
      {
        'ui-record-explorer-item-muted': muted,
        'ui-record-explorer-item-selected': selected,
        'ui-record-explorer-item-clickable': clickable,
      },
    ]"
    :style="$attrs.style"
    @click="emit('click')"
  >
    <span class="ui-record-explorer-item-main">
      <span class="ui-record-explorer-item-title">{{ title }}</span>
      <span v-if="secondary" class="ui-record-explorer-item-secondary">{{ secondary }}</span>
      <span v-if="tag" class="ui-record-explorer-item-tag">{{ tag }}</span>
    </span>
    <span v-if="actions?.length" class="ui-record-explorer-item-actions">
      <button
        v-for="action in actions"
        :key="action.key"
        class="ui-record-explorer-item-action"
        :class="{ danger: action.danger, 'show-label': action.showLabel }"
        :title="action.disabled ? (action.disabledReason ?? action.title) : action.title"
        :aria-label="action.disabled ? (action.disabledReason ?? action.title) : action.title"
        :disabled="action.disabled"
        type="button"
        @click="handleAction($event, action)"
      >
        <UiIcon v-if="action.iconName && !action.showLabel" :name="action.iconName" />
        <span v-else class="ui-record-explorer-item-action-label">
          {{ action.showLabel ? action.title : actionFallbackLabel(action) }}
        </span>
      </button>
    </span>
  </span>
</template>

<style scoped>
.ui-record-explorer-item {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  width: 100%;
  min-height: 24px;
  padding: 0 4px;
  border-radius: 4px;
  color: var(--muyun-text-body);
  font-size: 14px;
  line-height: 24px;
}

.ui-record-explorer-item-clickable {
  cursor: pointer;
}

.ui-record-explorer-item-clickable:hover,
.ui-record-explorer-item-selected {
  background: var(--muyun-hover);
}

.ui-record-explorer-item-muted {
  color: var(--muyun-text-muted);
}

.ui-record-explorer-item-main {
  display: inline-flex;
  flex: 1 1 auto;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.ui-record-explorer-item-title {
  flex: 0 1 auto;
  overflow: hidden;
  color: inherit;
  font-size: inherit;
  line-height: inherit;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ui-record-explorer-item-secondary {
  flex: 0 1 auto;
  overflow: hidden;
  color: var(--muyun-text-muted);
  font-size: 12px;
  line-height: inherit;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ui-record-explorer-item-tag {
  flex: 0 0 auto;
  padding: 1px 5px;
  border: 1px solid var(--muyun-border);
  border-radius: 4px;
  color: var(--muyun-text-muted);
  font-size: 11px;
  line-height: 16px;
}

.ui-record-explorer-item-actions {
  display: inline-flex;
  flex: 0 0 auto;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.12s ease;
}

.ui-record-explorer-item:hover .ui-record-explorer-item-actions,
.ui-record-explorer-item:focus-within .ui-record-explorer-item-actions,
.ui-record-explorer-item-selected .ui-record-explorer-item-actions {
  opacity: 1;
}

.ui-record-explorer-item-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--muyun-text-muted);
  cursor: pointer;
}

.ui-record-explorer-item-action.show-label {
  width: auto;
  padding: 0 6px;
}

.ui-record-explorer-item-action:hover:not(:disabled) {
  background: var(--muyun-hover);
  box-shadow:
    inset 0 0 0 1px var(--muyun-border-subtle),
    0 1px 2px rgb(15 23 42 / 8%);
}

.ui-record-explorer-item-action.danger {
  color: var(--muyun-danger-text);
}

.ui-record-explorer-item-action-label {
  font-size: 12px;
  line-height: 1;
}

.ui-record-explorer-item-action:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}
</style>
