<script setup lang="ts">
import ManagementTabs from './ManagementTabs.vue';

defineOptions({ name: 'RecordRelationTabs' });
defineProps<{ tabs: Array<{ key: string; title: string }>; activeKey?: string; disabled?: boolean }>();
const emit = defineEmits<{ 'update:activeKey': [key: string] }>();
</script>

<template>
  <ManagementTabs
    :tabs="tabs"
    :active-key="activeKey"
    :disabled="disabled"
    label="关联记录"
    @update:active-key="emit('update:activeKey', $event)"
  />
</template>
