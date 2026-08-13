<script setup lang="ts">
import { computed, watch } from 'vue';
import type { ModuleContext } from '@muyun/web-core';
import { UiActionButton, UiTooltip } from '@muyun/vue-ui-antdv';
import { resolveRecordActions, type RecordActionItem } from './recordActionBarModel';

defineOptions({ name: 'RecordActionBar' });

const props = withDefaults(
  defineProps<{
    context: ModuleContext<unknown>;
    actions: RecordActionItem[];
    recordId?: string;
    loading?: boolean;
    size?: 'default' | 'compact';
  }>(),
  {
    recordId: undefined,
    loading: false,
    size: 'default',
  },
);

const emit = defineEmits<{
  action: [action: RecordActionItem, event: MouseEvent];
}>();

watch(
  () => props.recordId,
  (recordId) => {
    if (recordId) {
      props.context.recordActions(recordId).catch(() => {
        // Action execution still performs backend checks; keep action loading errors non-blocking here.
      });
    }
  },
  { immediate: true },
);

const resolvedActions = computed(() =>
  resolveRecordActions(props.context, props.actions, props.loading, props.recordId),
);

function handleClick(action: RecordActionItem, event: MouseEvent) {
  emit('action', action, event);
}
</script>

<template>
  <div class="record-action-bar" :class="{ compact: size === 'compact' }">
    <UiTooltip
      v-for="action in resolvedActions"
      :key="action.key"
      :title="action.disabled ? (action.disabledReason ?? action.reason ?? '') : ''"
    >
      <span class="record-action-tooltip-trigger">
        <UiActionButton
          :emphasis="
            action.primary ? 'primary' : size === 'compact' && !action.danger ? 'quiet' : 'secondary'
          "
          :disabled="action.disabled"
          :loading="action.loading"
          :intent="action.danger ? 'danger' : 'normal'"
          :density="size === 'compact' ? 'compact' : 'regular'"
          :icon-name="action.iconName"
          @click="handleClick(action, $event)"
        >
          {{ action.title }}
        </UiActionButton>
      </span>
    </UiTooltip>
  </div>
</template>

<style scoped>
.record-action-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.record-action-bar.compact {
  gap: 4px;
}

.record-action-tooltip-trigger {
  display: inline-flex;
}

.record-action-bar.compact :deep(.ant-btn) {
  min-width: 0;
  height: 26px;
  padding: 0 8px;
  font-size: 12px;
}

.record-action-bar.compact :deep(.ant-btn-icon) {
  font-size: 12px;
}
</style>
