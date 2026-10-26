<script setup lang="ts">
import type { ExternalProvisioningProfile } from '@muyun/web-contracts';
import { useLanguageContext } from '@muyun/web-core';
import { UiButton } from '@muyun/vue-ui-antdv';

defineProps<{ confirmation: ExternalProvisioningProfile; busy?: boolean; error?: string }>();
defineEmits<{ confirm: []; cancel: [] }>();
const language = useLanguageContext();
</script>

<template>
  <main class="external-provisioning-confirmation">
    <h1>{{ language.t('platform.external-login.confirm-title', '确认加入') }}</h1>
    <p>
      {{
        language.t(
          'platform.external-login.confirm-explanation',
          '请确认以下身份资料。确认后将创建本系统账号。',
        )
      }}
    </p>
    <dl>
      <dt>{{ language.t('platform.external-login.person', '姓名') }}</dt>
      <dd>{{ confirmation.displayName }}</dd>
      <dt>{{ language.t('platform.external-login.tenant', '租户') }}</dt>
      <dd>{{ confirmation.tenantId }}</dd>
      <dt>{{ language.t('platform.external-login.placements', '任职归属') }}</dt>
      <dd>{{ (confirmation.placements ?? confirmation.departmentIds).join(', ') }}</dd>
    </dl>
    <p v-if="error" role="alert">{{ error }}</p>
    <UiButton :disabled="busy" @click="$emit('confirm')">{{
      language.t('platform.external-login.confirm-join', '确认并登录')
    }}</UiButton>
    <UiButton :disabled="busy" @click="$emit('cancel')">{{
      language.t('platform.external-login.cancel-join', '取消')
    }}</UiButton>
  </main>
</template>

<style scoped>
.external-provisioning-confirmation {
  max-width: 32rem;
  margin: 10vh auto;
  padding: 2rem;
}
dt {
  font-weight: 600;
}
dd {
  margin: 0 0 1rem;
  overflow-wrap: anywhere;
}
</style>
