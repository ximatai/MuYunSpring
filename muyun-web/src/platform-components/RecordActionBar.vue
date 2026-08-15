<script setup lang="ts">
import { computed, ref, watch, watchEffect } from 'vue';
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

const recordActionAvailabilityLoading = ref(false);
let recordActionAvailabilitySequence = 0;

watch(
  () => props.recordId,
  async (recordId) => {
    const sequence = ++recordActionAvailabilitySequence;
    if (!recordId) {
      recordActionAvailabilityLoading.value = false;
      return;
    }
    recordActionAvailabilityLoading.value = true;
    try {
      await props.context.recordActions(recordId);
    } catch {
      // Action execution still performs backend checks; keep availability loading errors non-blocking here.
    } finally {
      if (sequence === recordActionAvailabilitySequence) {
        recordActionAvailabilityLoading.value = false;
      }
    }
  },
  { immediate: true },
);

watchEffect(() => {
  for (const action of props.actions) {
    if (action.authorizationContext && action.authorizationRecordId) {
      action.authorizationContext.recordActions(action.authorizationRecordId).catch(() => {
        // Action execution still performs backend checks; keep authorization loading errors non-blocking here.
      });
    }
  }
});

const actionAvailabilityReason = '正在校验操作可用性';
const resolvedActions = computed(() => {
  const actions = recordActionAvailabilityLoading.value
    ? props.actions.map((action) =>
        action.actionCode
          ? {
              ...action,
              disabled: true,
              disabledReason: action.disabledReason ?? actionAvailabilityReason,
            }
          : action,
      )
    : props.actions;
  return resolveRecordActions(props.context, actions, props.loading, props.recordId);
});

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
