<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { createLanguagePreferenceController, useLanguageContext } from '@muyun/web-core';
import { UiSelect, showErrorMessage } from '@muyun/vue-ui-antdv';

defineOptions({ name: 'LanguageSelector' });
const props = defineProps<{ identityScope?: string }>();
const context = useLanguageContext();
const preferences = createLanguagePreferenceController(context);
const saving = ref(false);
const restoreFailed = ref(false);
const options = computed(() =>
  context.catalog.value.languages
    .filter((language) => language.enabled !== false)
    .map((language) => ({ value: language.tag, label: language.nativeName })),
);
watch(
  () => props.identityScope,
  (scope) => {
    restoreFailed.value = false;
    void preferences.restore(scope).catch(() => {
      restoreFailed.value = true;
    });
  },
  { immediate: true },
);
onBeforeUnmount(() => preferences.dispose());
async function change(value: unknown) {
  if (typeof value !== 'string') return;
  saving.value = true;
  try {
    await preferences.save(value);
  } catch {
    showErrorMessage(context.t('platform.language.save-failed', '语言偏好保存失败，请重试'));
  } finally {
    saving.value = false;
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
