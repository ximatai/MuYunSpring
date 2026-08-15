<script setup lang="ts">
import { computed } from 'vue';
import { UiSwitch } from '@muyun/vue-ui-antdv';

defineOptions({ name: 'RecordStatusSwitch' });

const props = withDefaults(
  defineProps<{
    enabled?: boolean;
    disabled?: boolean;
    loading?: boolean;
    showLabel?: boolean;
  }>(),
  {
    enabled: true,
    disabled: false,
    loading: false,
    showLabel: true,
  },
);

const emit = defineEmits<{
  change: [enabled: boolean];
}>();

const checked = computed(() => props.enabled !== false);

function handleChange(enabled: boolean) {
  if (props.disabled || props.loading) {
    return;
  }
  emit('change', enabled);
}
</script>

<template>
  <div class="record-status-switch">
    <div v-if="showLabel" class="record-status-switch-label">
      <span>启用状态</span>
      <strong>{{ checked ? '启用' : '停用' }}</strong>
    </div>
    <UiSwitch
      :checked="checked"
      :disabled="disabled"
      :loading="loading"
      checked-text="启"
      unchecked-text="停"
      @change="handleChange"
    />
  </div>
</template>

<style scoped>
.record-status-switch {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
  transform: translateY(var(--muyun-record-status-switch-offset-y, 0));
}

.record-status-switch-label {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.record-status-switch-label span {
  color: var(--muyun-text-muted);
  font-size: 12px;
  font-weight: 700;
}

.record-status-switch-label strong {
  color: var(--muyun-text-body);
  font-size: 13px;
  font-weight: 500;
}
</style>
