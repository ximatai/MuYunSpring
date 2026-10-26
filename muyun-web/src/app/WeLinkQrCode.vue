<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, useId } from 'vue';
import { useLanguageContext } from '@muyun/web-core';
import type { ExternalLoginStart } from '@muyun/web-contracts';
import { UiButton } from '@muyun/vue-ui-antdv';
import { navigateToExternalAuthorization } from './externalLoginReturn';
import { loadWeLinkScript, WELINK_ORIGIN, WELINK_SCRIPT } from './weLinkQrCode';

const props = defineProps<{ start: ExternalLoginStart }>();
const emit = defineEmits<{ close: [] }>();
const language = useLanguageContext();
const id = `welink-${useId()}`;
const container = ref<HTMLElement>();
const failed = ref(false);
const expired = ref(false);
let active = true;
let navigated = false;
let timer: ReturnType<typeof setTimeout> | undefined;

function receive(event: MessageEvent) {
  const iframe = container.value?.querySelector('iframe');
  if (
    !active ||
    failed.value ||
    expired.value ||
    navigated ||
    Date.now() >= Date.parse(props.start.expiresAt) ||
    event.origin !== WELINK_ORIGIN ||
    !iframe?.contentWindow ||
    event.source !== iframe.contentWindow ||
    typeof event.data !== 'string' ||
    !event.data.trim() ||
    event.data.length > 4096
  ) {
    return;
  }
  const qr = props.start.qrCode!;
  const url = new URL(qr.authorizationUrl);
  // Rebuild the complete allowlisted query; no message-supplied URL or identity is trusted.
  url.search = new URLSearchParams({
    client_id: qr.clientId,
    redirect_uri: qr.redirectUri,
    response_type: 'code',
    scope: qr.scope,
    state: props.start.state,
    code: event.data,
  }).toString();
  navigated = true;
  navigateToExternalAuthorization(url.toString());
}

onMounted(async () => {
  try {
    const qr = props.start.qrCode;
    if (
      !qr ||
      qr.providerType !== 'welink' ||
      qr.scriptUrl !== WELINK_SCRIPT ||
      qr.messageOrigin !== WELINK_ORIGIN ||
      qr.state !== props.start.state ||
      qr.scope !== 'snsapi_login' ||
      new URL(qr.authorizationUrl).origin !== WELINK_ORIGIN ||
      new URL(qr.authorizationUrl).pathname !== '/sso/oauth2/sns_authorize'
    ) {
      throw new Error('Unrecognized QR presentation');
    }
    const remaining = Date.parse(props.start.expiresAt) - Date.now();
    if (!Number.isFinite(remaining) || remaining <= 0) {
      expired.value = true;
      return;
    }
    timer = setTimeout(() => (expired.value = true), Math.min(remaining, 2147483647));
    const create = await loadWeLinkScript();
    if (!active || expired.value) return;
    window.addEventListener('message', receive);
    create({
      id,
      client_id: qr.clientId,
      redirect_uri: qr.redirectUri,
      state: qr.state,
      scope: qr.scope,
      response_type: 'code',
      self_redirect: 'false',
      lang: language.languageTag.value.startsWith('en') ? 'en' : 'cn',
      width: 300,
      height: 330,
    });
    const iframe = container.value?.querySelector('iframe');
    if (iframe) iframe.title = language.t('platform.welink.qr-title', 'WeLink 扫码授权');
  } catch {
    if (active) failed.value = true;
  }
});

onBeforeUnmount(() => {
  active = false;
  clearTimeout(timer);
  window.removeEventListener('message', receive);
});
</script>

<template>
  <section class="welink-qr" aria-live="polite">
    <p v-if="expired">{{ language.t('platform.welink.expired', '二维码授权已超时，请关闭后重新发起') }}</p>
    <p v-else-if="failed" role="alert">
      {{
        language.t(
          'platform.welink.load-failed',
          '无法加载 WeLink 二维码，请检查网络后重新发起，或使用其他登录方式',
        )
      }}
    </p>
    <template v-else>
      <p>{{ language.t('platform.welink.scan', '请使用 WeLink 扫码并在手机上确认授权') }}</p>
      <div :id="id" ref="container" />
    </template>
    <UiButton @click="emit('close')">{{ language.t('platform.action.cancel', '取消') }}</UiButton>
  </section>
</template>

<style scoped>
.welink-qr {
  display: grid;
  justify-items: center;
  gap: 12px;
  margin-top: 16px;
}
</style>
