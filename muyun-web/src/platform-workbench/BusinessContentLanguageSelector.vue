<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import {
  businessContentLanguageContext,
  businessContentLanguages,
  createLanguagePreferenceController,
  useLanguageContext,
  userPreferences,
} from '@muyun/web-core';
import { UiSelect, showErrorMessage } from '@muyun/vue-ui-antdv';

const props = defineProps<{ identityScope?: string }>();
const language = useLanguageContext();
const context = businessContentLanguageContext(language);
const preferences = createLanguagePreferenceController(context, userPreferences, 'business-content.language');
const saving = ref(false);
const restoreFailed = ref(false);
const options = computed(() => [
  { value: '__follow__', label: language.t('platform.content.follow-interface', '跟随界面语言') },
  ...businessContentLanguages,
]);
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
    await preferences.save(value === '__follow__' ? undefined : value);
  } catch {
    showErrorMessage(language.t('platform.content.preference-save-failed', '阅读语言偏好保存失败，请重试'));
  } finally {
    saving.value = false;
  }
}
</script>
<template>
  <label class="business-content-language">
    <span>{{ language.t('platform.content.reading-language', '业务阅读语言') }}</span>
    <UiSelect
      :value="context.requestedLanguageTag.value ?? '__follow__'"
      :options="options"
      :disabled="saving"
      :aria-label="language.t('platform.content.reading-language', '业务阅读语言')"
      :title="
        restoreFailed
          ? language.t('platform.content.preference-restore-failed', '暂时无法恢复阅读语言偏好，可继续选择')
          : undefined
      "
      @update:value="change"
    />
  </label>
</template>
<style scoped>
.business-content-language {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}
</style>
