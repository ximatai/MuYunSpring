<script setup lang="ts">
import { computed } from 'vue';
import { UiBadge, UiButton } from '@muyun/vue-ui-antdv';

defineOptions({ name: 'RecycleBinModeButton' });

const props = withDefaults(
  defineProps<{
    active?: boolean;
    hasRecords?: boolean;
    count?: number;
  }>(),
  {
    active: false,
    hasRecords: undefined,
    count: 0,
  },
);

const emit = defineEmits<{
  click: [event: MouseEvent];
}>();

const visualState = computed<'standard' | 'expression' | 'selected'>(() => {
  if (props.active) return 'selected';
  return props.hasRecords === true ? 'expression' : 'standard';
});
</script>

<template>
  <UiBadge
    v-if="visualState === 'expression' && props.count > 0"
    class="recycle-bin-mode-badge"
    :count="props.count"
  >
    <UiButton
      class="recycle-bin-mode-button"
      :class="`is-${visualState}`"
      type="text"
      danger
      icon-name="delete"
      @click="emit('click', $event)"
    >
      回收站
    </UiButton>
  </UiBadge>
  <UiButton
    v-else
    class="recycle-bin-mode-button"
    :class="`is-${visualState}`"
    type="text"
    :danger="visualState === 'selected'"
    :icon-name="visualState === 'selected' ? 'reload' : 'delete'"
    @click="emit('click', $event)"
  >
    {{ visualState === 'selected' ? '离开回收站' : '回收站' }}
  </UiButton>
</template>

<style scoped>
.recycle-bin-mode-button {
  border: 1px solid transparent;
}

.recycle-bin-mode-button.is-standard {
  border-color: var(--muyun-border-subtle);
}

.recycle-bin-mode-button.is-expression {
  border-color: var(--muyun-danger-border, #f5e5e7);
}

.recycle-bin-mode-badge :deep(.ant-badge-count) {
  min-width: 14px;
  height: 14px;
  padding-inline: 3px;
  font-size: 9px;
  line-height: 14px;
}

.recycle-bin-mode-button.is-expression :deep(.ant-btn-icon) {
  color: var(--muyun-danger-text);
}

.recycle-bin-mode-button.is-standard :deep(.ant-btn-icon) {
  color: var(--muyun-text-muted);
}

.recycle-bin-mode-button.is-selected {
  border-color: var(--muyun-danger-border, #f5e5e7);
  background: var(--muyun-danger-bg);
  color: var(--muyun-danger-text);
}

.recycle-bin-mode-button.is-selected :deep(.ant-btn-icon) {
  color: currentcolor;
}
</style>
