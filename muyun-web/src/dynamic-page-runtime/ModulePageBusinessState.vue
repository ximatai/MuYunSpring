<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import { RecordPanelButton, RecordPanelState } from '@muyun/platform-components';

const language = useLanguageContext();

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
      :loading-tip="
        props.pending
          ? language.t('platform.screen.ModulePageBusinessState.loading-tenant-data', '加载租户数据')
          : undefined
      "
      :description="props.error ?? ''"
    />
    <RecordPanelButton v-if="props.error" type="link" @click="emit('retry')">{{
      language.t('platform.screen.ModulePageBusinessState.retry', '重试')
    }}</RecordPanelButton>
  </section>
</template>

<style scoped>
.module-business-session-state {
  min-width: 0;
}
</style>
