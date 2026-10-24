<script setup lang="ts">
import { AppError, normalizeError, useLanguageContext, userFacingErrorMessage } from '@muyun/web-core';
import type { ExternalLoginClient } from '@muyun/web-core';
import type { ExternalIdentityView, ExternalLoginMethod, OwnExternalIdentities } from '@muyun/web-contracts';
import { UiButton, confirmAction } from '@muyun/vue-ui-antdv';
import { onMounted, ref } from 'vue';
import { navigateToExternalAuthorization, rememberExternalLogin } from './externalLoginReturn';

defineOptions({ name: 'ExternalIdentityBindings' });

const props = defineProps<{
  client: ExternalLoginClient;
  token: string;
}>();

const language = useLanguageContext();
const identities = ref<OwnExternalIdentities>();
/** Federation disabled on the server: the section stays hidden instead of showing an error. */
const unavailable = ref(false);
const busy = ref(false);
const error = ref<() => string>();

onMounted(load);

async function load() {
  error.value = undefined;
  try {
    identities.value = await props.client.identities(props.token);
  } catch (cause) {
    if (cause instanceof AppError && cause.status === 404) {
      unavailable.value = true;
      return;
    }
    error.value = () => userFacingErrorMessage(normalizeError(cause), language);
  }
}

async function bind(method: ExternalLoginMethod) {
  busy.value = true;
  error.value = undefined;
  try {
    const start = await props.client.startBind(method.providerId, props.token);
    rememberExternalLogin(start, 'bind');
    navigateToExternalAuthorization(start.authorizationUrl);
  } catch (cause) {
    error.value = () => userFacingErrorMessage(normalizeError(cause), language);
    busy.value = false;
  }
}

async function unbind(binding: ExternalIdentityView) {
  const confirmed = await confirmAction(
    {
      title: language.t('platform.screen.ExternalIdentityBindings.unbind-title', '解除外部账号绑定'),
      content: language.t(
        'platform.screen.ExternalIdentityBindings.unbind-content',
        '解除后将无法再通过 {provider} 登录当前账号。',
        { provider: binding.providerTitle },
      ),
      danger: true,
    },
    language,
  );
  if (!confirmed) {
    return;
  }
  busy.value = true;
  error.value = undefined;
  try {
    await props.client.unbind(binding.id, props.token);
    await load();
  } catch (cause) {
    error.value = () => userFacingErrorMessage(normalizeError(cause), language);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section
    v-if="!unavailable && (identities?.bindings.length || identities?.bindableProviders.length || error)"
    class="external-bindings"
  >
    <h3>{{ language.t('platform.screen.ExternalIdentityBindings.title', '外部账号绑定') }}</h3>
    <p v-if="error" class="external-bindings-error">{{ error() }}</p>
    <ul v-if="identities" class="external-bindings-list">
      <li v-for="binding in identities.bindings" :key="binding.id">
        <span class="external-bindings-provider">{{ binding.providerTitle }}</span>
        <span class="external-bindings-account">{{ binding.displayName || binding.externalUserId }}</span>
        <UiButton size="small" danger :disabled="busy" @click="unbind(binding)">
          {{ language.t('platform.screen.ExternalIdentityBindings.unbind', '解除绑定') }}
        </UiButton>
      </li>
      <li v-for="method in identities.bindableProviders" :key="method.providerId">
        <span class="external-bindings-provider">{{ method.title }}</span>
        <span class="external-bindings-account muted">
          {{ language.t('platform.screen.ExternalIdentityBindings.not-bound', '未绑定') }}
        </span>
        <UiButton size="small" :loading="busy" @click="bind(method)">
          {{ language.t('platform.screen.ExternalIdentityBindings.bind', '去绑定') }}
        </UiButton>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.external-bindings {
  display: grid;
  gap: 10px;
  margin-top: 16px;
}

.external-bindings h3 {
  margin: 0;
  color: var(--muyun-support-text);
  font-size: 14px;
}

.external-bindings-error {
  margin: 0;
  color: var(--muyun-danger-soft-text);
  font-size: 13px;
}

.external-bindings-list {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.external-bindings-list li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr) auto;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border: 1px solid var(--muyun-support-border);
  border-radius: 6px;
}

.external-bindings-provider {
  color: var(--muyun-support-text-body);
  font-weight: 600;
}

.external-bindings-account {
  overflow: hidden;
  color: var(--muyun-support-text-body);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.external-bindings-account.muted {
  color: var(--muyun-support-text-muted);
}
</style>
