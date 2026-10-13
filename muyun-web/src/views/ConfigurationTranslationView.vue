<script setup lang="ts">
import { computed, watch } from 'vue';
import { useLanguageContext, useModuleContext } from '@muyun/web-core';
import { UiButton, UiInput, UiSelect, UiTextArea } from '@muyun/vue-ui-antdv';
import { presentPlatformError } from '@muyun/platform-components';
import { createConfigurationTranslationWorkspace } from './configurationTranslationWorkspace';

const language = useLanguageContext();
const t = language.t;
const context = useModuleContext({ moduleAlias: 'platform.localization' });
const workspace = createConfigurationTranslationWorkspace(context.http);
const {
  moduleAlias,
  sourceId,
  targetLanguage,
  sources,
  source,
  translation,
  previous,
  text,
  dirty,
  busy,
  ready,
} = workspace;
const languages = computed(() =>
  language.catalog.value.languages
    .filter((item) => item.enabled !== false)
    .map((item) => ({ value: item.tag, label: item.nativeName })),
);
watch(
  language.catalog,
  (catalog) => {
    if (!catalog.languages.some((item) => item.tag === targetLanguage.value && item.enabled !== false)) {
      targetLanguage.value =
        catalog.languages.find((item) => item.enabled !== false && item.tag !== catalog.defaultTag)?.tag ??
        catalog.defaultTag;
    }
  },
  { immediate: true },
);
const sourceOptions = computed(() =>
  sources.value.map((item) => ({ value: item.sourceId, label: item.originalText })),
);
async function run(operation: () => Promise<void>) {
  try {
    await operation();
  } catch (cause) {
    presentPlatformError(cause, { source: 'configuration-translation', phase: 'action' });
  }
}
</script>

<template>
  <section class="configuration-translation">
    <h2>{{ t('platform.translation.title', '配置翻译') }}</h2>
    <p>{{ t('platform.translation.scope', '维护已生效界面原文的译文；业务记录内容保持原文。') }}</p>
    <div class="configuration-translation__toolbar">
      <label
        >{{ t('platform.translation.module', '模块')
        }}<UiInput v-model:value="moduleAlias" :disabled="busy" placeholder="education.exam"
      /></label>
      <UiButton
        :disabled="
          busy ||
          !moduleAlias ||
          context.can('viewTranslationSources') !== true ||
          context.can('viewTranslations') !== true
        "
        @click="run(workspace.load)"
        >{{ t('platform.translation.load', '加载原文') }}</UiButton
      >
      <label
        >{{ t('platform.translation.target-language', '翻译语言')
        }}<UiSelect v-model:value="targetLanguage" :options="languages" :allow-clear="false" :disabled="busy"
      /></label>
    </div>
    <template v-if="sources.length && ready">
      <label
        >{{ t('platform.translation.source', '选择原文')
        }}<UiSelect
          v-model:value="sourceId"
          :options="sourceOptions"
          :allow-clear="false"
          :disabled="busy"
          show-search
      /></label>
      <template v-if="source">
        <p class="configuration-translation__location">
          <span>{{ t('platform.translation.location', '来源位置') }}</span> <code>{{ source.sourceId }}</code>
        </p>
        <label
          >{{ t('platform.translation.original', '原文') }}<UiTextArea :value="source.originalText" disabled
        /></label>
        <p v-if="previous" role="status">
          {{ t('platform.translation.review-required', '原文已变化，旧译文仅供复核参考：')
          }}{{ previous.publishedText ?? previous.draftText }}
        </p>
        <label
          >{{ t('platform.translation.draft', '译文草稿')
          }}<UiTextArea v-model:value="text" :disabled="busy || context.can('saveTranslationDraft') !== true"
        /></label>
        <label
          >{{ t('platform.translation.published', '已发布译文')
          }}<UiTextArea :value="translation?.publishedText ?? ''" disabled
        /></label>
        <div class="configuration-translation__toolbar">
          <UiButton
            :disabled="busy || !ready || !text.trim() || context.can('saveTranslationDraft') !== true"
            @click="run(workspace.save)"
            >{{ t('platform.translation.save-draft', '保存草稿') }}</UiButton
          >
          <UiButton
            :disabled="busy || !ready || dirty || !translation || context.can('publishTranslation') !== true"
            @click="run(workspace.publish)"
            >{{ t('platform.translation.publish', '独立发布译文') }}</UiButton
          >
        </div>
      </template>
    </template>
    <p v-else-if="!busy">
      {{
        t(
          'platform.translation.empty',
          '选择模块并加载可翻译的在线配置原文。代码声明文案通过所属应用语言资源维护。',
        )
      }}
    </p>
  </section>
</template>

<style scoped>
.configuration-translation {
  padding: 24px;
  display: grid;
  gap: 16px;
  max-width: 1080px;
  margin-inline: auto;
}
.configuration-translation label {
  display: grid;
  gap: 8px;
  min-width: 0;
}
.configuration-translation__toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 16px;
}
.configuration-translation__toolbar label {
  flex: 1;
  min-width: 220px;
}
.configuration-translation__location code {
  overflow-wrap: anywhere;
}
</style>
