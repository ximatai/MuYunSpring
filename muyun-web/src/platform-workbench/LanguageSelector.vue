<script setup lang="ts">
import { computed } from 'vue';
import { useLanguageContext } from '@muyun/web-core';
import { UiSelect, showErrorMessage } from '@muyun/vue-ui-antdv';
import { useLanguagePreferenceSelection } from './useLanguagePreferenceSelection';

defineOptions({ name: 'LanguageSelector' });
const props = defineProps<{ identityScope?: string }>();
const context = useLanguageContext();
const { saving, restoreFailed, save } = useLanguagePreferenceSelection(context, () => props.identityScope);
const options = computed(() =>
  context.catalog.value.languages
    .filter((language) => language.enabled !== false)
    .map((language) => ({ value: language.tag, label: language.nativeName })),
);
async function change(value: unknown) {
  if (typeof value !== 'string') return;
  try {
    await save(value);
  } catch {
    showErrorMessage(context.t('platform.language.save-failed', '语言偏好保存失败，请重试'));
  }
}
</script>
<template>
  <UiSelect
    :value="context.languageTag.value"
    :options="options"
    :disabled="saving"
    :title="
      restoreFailed
        ? context.t('platform.language.restore-failed', '暂时无法恢复语言偏好，可继续选择界面语言')
        : undefined
    "
    :aria-label="context.t('platform.language.label', '界面语言')"
    @update:value="change"
  />
</template>
