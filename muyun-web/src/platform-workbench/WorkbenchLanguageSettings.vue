<script setup lang="ts">
import { computed } from 'vue';
import {
  businessContentLanguageContext,
  businessContentLanguages,
  useLanguageContext,
} from '@muyun/web-core';
import { UiActionButton, UiPopover, UiSelect, showErrorMessage } from '@muyun/vue-ui-antdv';
import { useLanguagePreferenceSelection } from './useLanguagePreferenceSelection';

const props = defineProps<{ identityScope?: string }>();
const language = useLanguageContext();
const reading = businessContentLanguageContext(language);
const interfacePreference = useLanguagePreferenceSelection(language, () => props.identityScope);
const readingPreference = useLanguagePreferenceSelection(
  reading,
  () => props.identityScope,
  'business-content.language',
);
const interfaceOptions = computed(() =>
  language.catalog.value.languages
    .filter((entry) => entry.enabled !== false)
    .map((entry) => ({ value: entry.tag, label: entry.nativeName })),
);
const readingOptions = computed(() => [
  { value: '__follow__', label: language.t('platform.content.follow-interface', '跟随界面语言') },
  ...businessContentLanguages,
]);
const interfaceName = computed(
  () =>
    interfaceOptions.value.find((entry) => entry.value === language.languageTag.value)?.label ??
    language.languageTag.value,
);
const readingName = computed(
  () =>
    businessContentLanguages.find((entry) => entry.value === reading.languageTag.value)?.label ??
    reading.languageTag.value,
);
async function change(value: unknown, kind: 'interface' | 'reading') {
  if (typeof value !== 'string') return;
  try {
    await (kind === 'interface' ? interfacePreference : readingPreference).save(
      value === '__follow__' ? undefined : value,
    );
  } catch {
    showErrorMessage(language.t('platform.language.save-failed', '语言偏好保存失败，请重试'));
  }
}
</script>
<template>
  <UiPopover placement="bottomRight">
    <UiActionButton
      :title="language.t('platform.content.language-settings', '语言设置')"
      class="workbench-language-settings__trigger"
    >
      {{ language.t('platform.content.language-settings', '语言设置')
      }}<span v-if="reading.languageTag.value !== language.languageTag.value"> · {{ readingName }}</span>
    </UiActionButton>
    <template #content>
      <div class="workbench-language-settings">
        <label v-if="interfaceOptions.length > 1">
          <span>{{ language.t('platform.language.label', '界面语言') }}</span>
          <UiSelect
            :value="language.languageTag.value"
            :options="interfaceOptions"
            :disabled="interfacePreference.saving.value"
            :aria-label="language.t('platform.language.label', '界面语言')"
            @update:value="change($event, 'interface')"
          />
        </label>
        <p v-else>{{ language.t('platform.language.label', '界面语言') }}：{{ interfaceName }}</p>
        <label>
          <span>{{ language.t('platform.content.reading-language', '业务阅读语言') }}</span>
          <UiSelect
            :value="reading.requestedLanguageTag.value ?? '__follow__'"
            :options="readingOptions"
            :disabled="readingPreference.saving.value"
            :aria-label="language.t('platform.content.reading-language', '业务阅读语言')"
            @update:value="change($event, 'reading')"
          />
        </label>
        <p>
          {{
            language.t(
              'platform.content.language-settings-description',
              '阅读语言只改变业务内容的展示，不修改原文或本次填写语言。',
            )
          }}
        </p>
        <p
          v-if="interfacePreference.restoreFailed.value || readingPreference.restoreFailed.value"
          role="status"
        >
          {{ language.t('platform.language.restore-failed', '暂时无法恢复语言偏好，可继续选择界面语言') }}
        </p>
      </div>
    </template>
  </UiPopover>
</template>
<style scoped>
.workbench-language-settings {
  display: grid;
  gap: 12px;
  width: 280px;
  max-width: calc(100vw - 48px);
  font-size: 12px;
}
.workbench-language-settings label {
  display: grid;
  gap: 8px;
}
.workbench-language-settings p {
  margin: 0;
  color: var(--muyun-text-muted);
}
</style>
