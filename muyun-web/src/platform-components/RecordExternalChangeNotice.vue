<script setup lang="ts">
import { UiActionButton } from '@muyun/vue-ui-antdv';

defineOptions({ name: 'RecordExternalChangeNotice' });

withDefaults(
  defineProps<{
    title?: string;
    message?: string;
    reloadTitle?: string;
    dismissTitle?: string;
  }>(),
  {
    title: '记录已发生变更',
    message: '当前记录已被其他操作更新，建议重新加载后再继续编辑。',
    reloadTitle: '重新加载',
    dismissTitle: '继续编辑',
  },
);

const emit = defineEmits<{
  reload: [];
  dismiss: [];
}>();
</script>

<template>
  <section class="record-external-change-notice" role="status">
    <div class="record-external-change-notice-content">
      <strong>{{ title }}</strong>
      <span>{{ message }}</span>
    </div>
    <div class="record-external-change-notice-actions">
      <UiActionButton density="compact" icon-name="reload" @click="emit('reload')">
        {{ reloadTitle }}
      </UiActionButton>
      <UiActionButton density="compact" emphasis="quiet" @click="emit('dismiss')">
        {{ dismissTitle }}
      </UiActionButton>
    </div>
  </section>
</template>

<style scoped>
.record-external-change-notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--muyun-warning-border);
  border-radius: 8px;
  background: var(--muyun-warning-soft);
  color: var(--muyun-warning-soft-text);
}

.record-external-change-notice-content {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.record-external-change-notice-content strong {
  font-size: 13px;
}

.record-external-change-notice-content span {
  color: var(--muyun-warning-soft-text);
  font-size: 12px;
  line-height: 1.5;
}

.record-external-change-notice-actions {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 6px;
}

@media (max-width: 720px) {
  .record-external-change-notice {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
  }

  .record-external-change-notice-actions {
    justify-content: flex-start;
  }
}
</style>
