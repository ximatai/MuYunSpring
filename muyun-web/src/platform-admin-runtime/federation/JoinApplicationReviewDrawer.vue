<script setup lang="ts">
import { onMounted, ref, watchEffect } from 'vue';
import { useLanguageContext } from '@muyun/web-core';
import type { ModulePageDrawerContext } from '@muyun/dynamic-page-runtime';
import { presentPlatformError } from '@muyun/platform-components';
import { UiTextArea, confirmAction } from '@muyun/vue-ui-antdv';

const props = defineProps<{ context: ModulePageDrawerContext; action: 'approve' | 'reject' }>();
const language = useLanguageContext();
const note = ref('');
const busy = ref(false);
const authorized = ref(false);
const id = String(props.context.record?.id ?? '');
props.context.setBeforeClose(
  () =>
    !note.value.trim() ||
    confirmAction(
      {
        title: language.t('platform.screen.useModulePageSession.discard-unsaved-changes', '放弃未保存更改'),
        content: language.t(
          'platform.screen.useModulePageSession.this-record-has-unsaved-changes-that-will-be-lost-when',
          '当前记录存在未保存的更改，关闭后将丢失。是否继续？',
        ),
        danger: true,
      },
      language,
    ),
);
onMounted(async () => {
  try {
    await props.context.module.recordActions(id);
    authorized.value = props.context.module.can(props.action, id) === true;
  } catch (error) {
    presentPlatformError(error, { language, source: 'join-review', phase: 'load' });
  }
});
watchEffect(() => {
  props.context.setOperation({
    actions: [
      {
        key: 'review',
        label:
          props.action === 'approve'
            ? language.t('platform.federation.approve', '批准加入')
            : language.t('platform.federation.reject', '拒绝加入'),
        emphasis: 'primary',
        intent: props.action === 'reject' ? 'danger' : 'normal',
        disabled: !authorized.value || busy.value,
        loading: busy.value,
        run: submit,
      },
    ],
  });
});
async function submit() {
  if (busy.value || !authorized.value) return;
  busy.value = true;
  props.context.setCloseBlocked(true);
  let saved = false;
  try {
    await props.context.module.http.request({
      method: 'POST',
      path: `/iam.join_application/${encodeURIComponent(id)}/${props.action}`,
      body: { note: note.value.trim() || null },
    });
    authorized.value = false;
    note.value = '';
    saved = true;
  } catch (error) {
    presentPlatformError(error, { language, source: 'join-review', phase: 'action' });
  } finally {
    busy.value = false;
    props.context.setCloseBlocked(false);
  }
  if (saved) {
    props.context.reload();
    props.context.close();
  }
}
</script>

<template>
  <section class="join-review">
    <p>{{ context.record?.displayName }}</p>
    <p>
      {{
        action === 'approve'
          ? language.t(
              'platform.federation.approve-hint',
              '批准后按租户接入规则处理账号，业务数据权限仍需单独授权。',
            )
          : language.t('platform.federation.reject-hint', '拒绝后保留申请及审核记录，当前申请不能再次审核。')
      }}
    </p>
    <label>
      <span>{{ language.t('platform.federation.review-note', '审核说明（可选）') }}</span>
      <UiTextArea v-model:value="note" :disabled="busy" />
    </label>
  </section>
</template>

<style scoped>
.join-review {
  display: grid;
  gap: 16px;
}
.join-review p {
  margin: 0;
}
.join-review label {
  display: grid;
  gap: 6px;
}
</style>
