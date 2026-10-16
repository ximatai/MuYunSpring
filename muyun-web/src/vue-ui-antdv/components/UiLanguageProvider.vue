<script setup lang="ts">
import { ConfigProvider as AConfigProvider } from 'ant-design-vue';
import { computed, onUnmounted, watch } from 'vue';
import { useLanguageContext, provideLanguageContext, type LanguageContext } from '@muyun/web-core';
import { uiLanguageLocales } from '../languageLocales';
import { installGlobalLanguage, removeGlobalLanguage } from '../languageGlobalScope';

defineOptions({ name: 'UiLanguageProvider', inheritAttrs: false });
const props = defineProps<{ context?: LanguageContext; scope?: 'local' | 'global' }>();
const inherited = useLanguageContext();
const context = props.context ?? inherited;
provideLanguageContext(context);
const locale = computed(() =>
  context.fallbackLanguageTags.value.map((tag) => uiLanguageLocales.value[tag]).find(Boolean),
);
const providerId = Symbol('UiLanguageProvider');
watch(
  () => [context.languageTag.value, context.language.value.direction, props.scope] as const,
  ([tag, direction, scope]) => {
    if (scope === 'global') installGlobalLanguage(providerId, tag, direction);
    else removeGlobalLanguage(providerId);
  },
  { immediate: true },
);
onUnmounted(() => removeGlobalLanguage(providerId));
</script>
<template>
  <div class="ui-language-provider" :lang="context.languageTag.value" :dir="context.language.value.direction">
    <AConfigProvider :locale="locale" :direction="context.language.value.direction"><slot /></AConfigProvider>
  </div>
</template>
<style scoped>
.ui-language-provider {
  display: contents;
}
</style>
