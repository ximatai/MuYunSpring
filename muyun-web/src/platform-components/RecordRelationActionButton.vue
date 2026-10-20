<script setup lang="ts">
import type { UiIconName } from '@muyun/vue-ui-antdv';
import RecordPanelButton from './RecordPanelButton.vue';

defineOptions({ name: 'RecordRelationActionButton' });
defineProps<{
  iconName: UiIconName;
  title: string;
  ariaLabel?: string;
  disabled?: boolean;
  loading?: boolean;
  danger?: boolean;
}>();
const emit = defineEmits<{ click: [event: MouseEvent] }>();
</script>

<template>
  <RecordPanelButton
    class="record-relation-action-button"
    type="text"
    :icon-name="iconName"
    :title="title"
    :aria-label="ariaLabel ?? title"
    :disabled="disabled"
    :loading="loading"
    :danger="danger"
    @click="emit('click', $event)"
  />
</template>

<style scoped>
.record-relation-action-button :deep(.ant-btn),
:deep(.record-relation-action-button.ant-btn) {
  align-items: center;
  border-radius: 50%;
  display: inline-flex;
  height: 24px;
  justify-content: center;
  min-width: 24px;
  padding: 0;
  width: 24px;
}
.record-relation-action-button :deep(.anticon),
:deep(.record-relation-action-button.ant-btn .anticon) {
  font-size: 12px;
}
</style>
