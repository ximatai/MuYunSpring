<script setup lang="ts">
import { computed, ref } from 'vue';
import { presentPlatformError, presentPlatformMessage } from '@muyun/platform-components';
import { UiButton, UiError, UiInput } from '@muyun/vue-ui-antdv';
import type { ModulePageDrawerContext } from '@muyun/dynamic-page-runtime';
import type { ResetPasswordResponse } from '@muyun/web-contracts';

defineOptions({ name: 'UserPasswordDrawer' });

const props = defineProps<{ context: ModulePageDrawerContext }>();
const password = ref('');
const resetResult = ref<ResetPasswordResponse>();
const saving = ref(false);
const userId = computed(() =>
  props.context.record?.id == null ? undefined : String(props.context.record.id),
);

async function changePassword() {
  const id = userId.value;
  if (!id || saving.value || props.context.module.can('changePassword', id) !== true) return;
  if (!password.value) {
    presentPlatformMessage('请输入新密码。', { source: 'iam-user-password', phase: 'validation' });
    return;
  }
  saving.value = true;
  props.context.setCloseBlocked(true);
  try {
    await props.context.module.http.request({
      method: 'POST',
      path: `/iam.user/changePassword/${encodeURIComponent(id)}`,
      body: { password: password.value },
    });
    password.value = '';
    presentPlatformMessage('密码已修改。', { source: 'iam-user-password', phase: 'action' });
    props.context.refreshList();
    props.context.refreshDetailExtensions();
  } catch (cause) {
    presentPlatformError(cause, { source: 'iam-user-password', phase: 'action' });
  } finally {
    props.context.setCloseBlocked(false);
    saving.value = false;
  }
}

async function resetPassword() {
  const id = userId.value;
  if (!id || saving.value || props.context.module.can('resetPassword', id) !== true) return;
  saving.value = true;
  props.context.setCloseBlocked(true);
  try {
    resetResult.value = await props.context.module.http.request<ResetPasswordResponse>({
      method: 'POST',
      path: `/iam.user/resetPassword/${encodeURIComponent(id)}`,
    });
    props.context.refreshList();
    props.context.refreshDetailExtensions();
  } catch (cause) {
    presentPlatformError(cause, { source: 'iam-user-password', phase: 'action' });
  } finally {
    props.context.setCloseBlocked(false);
    saving.value = false;
  }
}
</script>

<template>
  <UiError v-if="!userId" title="用户不存在" message="请重新打开用户详情后再管理密码。" />
  <section v-else class="user-password-drawer">
    <label v-if="context.module.can('changePassword', userId) === true">
      <span>新密码</span>
      <UiInput v-model:value="password" type="password" :disabled="saving" placeholder="请输入新密码" />
      <UiButton type="primary" :loading="saving" @click="changePassword">修改密码</UiButton>
    </label>
    <section v-if="context.module.can('resetPassword', userId) === true" class="user-password-reset">
      <p>由系统生成临时密码。请在安全渠道交付给用户，并提醒其尽快修改。</p>
      <UiButton :loading="saving" @click="resetPassword">生成临时密码</UiButton>
      <dl v-if="resetResult">
        <div>
          <dt>临时密码</dt>
          <dd>{{ resetResult.temporaryPassword }}</dd>
        </div>
        <div>
          <dt>失效时间</dt>
          <dd>{{ resetResult.expiresAt ?? '-' }}</dd>
        </div>
      </dl>
    </section>
  </section>
</template>

<style scoped>
.user-password-drawer,
.user-password-drawer label,
.user-password-reset {
  display: grid;
  gap: 12px;
}
.user-password-drawer label,
.user-password-reset p,
.user-password-reset dt {
  color: var(--muyun-text-muted);
  font-size: 13px;
}
.user-password-reset {
  padding-top: 14px;
  border-top: 1px solid var(--muyun-border-subtle);
}
.user-password-reset p,
.user-password-reset dl {
  margin: 0;
}
.user-password-reset dl {
  display: grid;
  gap: 6px;
}
.user-password-reset dl div {
  display: grid;
  grid-template-columns: 78px minmax(0, 1fr);
  gap: 8px;
}
.user-password-reset dd {
  margin: 0;
  overflow-wrap: anywhere;
}
</style>
