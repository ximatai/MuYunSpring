<script setup lang="ts">
import { RecordPanelButton, RecordPanelState } from '@muyun/platform-components';

defineOptions({ name: 'ModulePageBusinessState' });
const props = defineProps<{
  pending?: boolean;
  error?: string;
}>();
const emit = defineEmits<{ retry: [] }>();
</script>

<template>
  <section class="module-business-session-state" :role="props.error ? 'alert' : 'status'">
    <RecordPanelState
      :loading="props.pending"
      :loading-tip="props.pending ? '加载租户数据' : undefined"
      :description="props.error ?? ''"
    />
    <RecordPanelButton v-if="props.error" type="link" @click="emit('retry')">重试</RecordPanelButton>
  </section>
</template>

<style scoped>
.module-business-session-state {
  min-width: 0;
}
</style>
