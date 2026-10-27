<script setup lang="ts">
import { useLanguageContext, type PersonalAccessTokenClient } from '@muyun/web-core';
import { UiButton, UiModal } from '@muyun/vue-ui-antdv';
import PersonalAccessTokens from './PersonalAccessTokens.vue';

const props = withDefaults(
  defineProps<{ open: boolean; client: PersonalAccessTokenClient; sessionToken?: string }>(),
  { sessionToken: undefined },
);
const emit = defineEmits<{ close: [] }>();
const language = useLanguageContext();
</script>

<template>
  <UiModal
    :open="props.open"
    :title="language.t('platform.access-tokens.title', '个人访问密钥')"
    :width="640"
    scroll-content
    @cancel="emit('close')"
  >
    <PersonalAccessTokens
      v-if="open && sessionToken"
      :key="sessionToken"
      :client="client"
      :session-token="sessionToken"
    />
    <template #operation>
      <UiButton @click="emit('close')">{{ language.t('platform.content.close', '关闭') }}</UiButton>
    </template>
  </UiModal>
</template>
