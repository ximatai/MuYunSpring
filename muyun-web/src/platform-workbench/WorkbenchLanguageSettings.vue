<script setup lang="ts">
import { computed } from 'vue';
import {
  businessContentLanguageContext,
  businessContentLanguages,
  useLanguageContext,
} from '@muyun/web-core';
import { UiButton, UiModal, UiSelect, showErrorMessage } from '@muyun/vue-ui-antdv';
import { useLanguagePreferenceSelection } from './useLanguagePreferenceSelection';

const props = defineProps<{ identityScope?: string; open?: boolean }>();
const emit = defineEmits<{ 'update:open': [open: boolean] }>();
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
  <UiModal
    :open="Boolean(open)"
    :title="language.t('platform.content.language-settings', '语言设置')"
    :width="400"
    @cancel="emit('update:open', false)"
  >
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
    <template #operation>
      <UiButton
        :aria-label="language.t('platform.action.done', '完成')"
        @click="emit('update:open', false)"
        >{{ language.t('platform.action.done', '完成') }}</UiButton
      >
    </template>
  </UiModal>
</template>
<style scoped>
.workbench-language-settings {
  display: grid;
  gap: 12px;
  width: 100%;
  font-size: var(--muyun-font-size-body, 13px);
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
