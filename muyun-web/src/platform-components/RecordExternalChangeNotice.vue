<script setup lang="ts">
import { computed } from 'vue';
import { useLanguageContext } from '@muyun/web-core';
import { UiActionButton } from '@muyun/vue-ui-antdv';

const language = useLanguageContext();

defineOptions({ name: 'RecordExternalChangeNotice' });

const props = withDefaults(
  defineProps<{
    title?: string;
    message?: string;
    reloadTitle?: string;
    dismissTitle?: string;
  }>(),
  {
    title: undefined,
    message: undefined,
    reloadTitle: undefined,
    dismissTitle: undefined,
  },
);

const resolvedTitle = computed(
  () => props.title ?? language.t('platform.screen.RecordExternalChangeNotice.title', '记录已发生变更'),
);
const resolvedMessage = computed(
  () =>
    props.message ??
    language.t(
      'platform.screen.RecordExternalChangeNotice.message',
      '当前记录已被其他操作更新，建议重新加载后再继续编辑。',
    ),
);
const resolvedReloadTitle = computed(
  () => props.reloadTitle ?? language.t('platform.screen.RecordExternalChangeNotice.reload', '重新加载'),
);
const resolvedDismissTitle = computed(
  () =>
    props.dismissTitle ?? language.t('platform.screen.RecordExternalChangeNotice.dismissTitle', '继续编辑'),
);

const emit = defineEmits<{
  reload: [];
  dismiss: [];
}>();
</script>

<template>
  <section class="record-external-change-notice" role="status">
    <div class="record-external-change-notice-content">
      <strong>{{ resolvedTitle }}</strong>
      <span>{{ resolvedMessage }}</span>
    </div>
    <div class="record-external-change-notice-actions">
      <UiActionButton density="compact" icon-name="reload" @click="emit('reload')">
        {{ resolvedReloadTitle }}
      </UiActionButton>
      <UiActionButton density="compact" emphasis="quiet" @click="emit('dismiss')">
        {{ resolvedDismissTitle }}
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
