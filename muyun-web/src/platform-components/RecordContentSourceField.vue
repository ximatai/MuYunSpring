<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { UiButton, UiModal, UiSelect } from '@muyun/vue-ui-antdv';
import DateTimeText from './DateTimeText.vue';
import {
  loadBusinessContentSources,
  loadBusinessContentHistory,
  useLanguageContext,
  type BusinessContentRelationScope,
  type ModuleContext,
} from '@muyun/web-core';
import type {
  BusinessContentCurrentSource,
  BusinessContentField,
  BusinessContentLanguageInput,
  BusinessContentRevision,
} from '@muyun/web-contracts';

const props = defineProps<{
  record: Record<string, unknown>;
  fieldName: string;
  content: BusinessContentField;
  context?: Pick<ModuleContext<unknown>, 'http' | 'moduleAlias'>;
  relation?: BusinessContentRelationScope;
  mode: 'create' | 'edit' | 'view';
  disabled?: boolean;
  sessionKey?: string | number;
  compact?: boolean;
}>();
const emit = defineEmits<{ change: [inputs: Record<string, BusinessContentLanguageInput>] }>();
const language = useLanguageContext();
const current = ref<BusinessContentCurrentSource>();
const loading = ref(false);
const failed = ref(false);
let generation = 0;
const id = computed(() => (props.record.id == null ? undefined : String(props.record.id)));
const rawText = computed(() =>
  props.record[props.fieldName] == null ? null : String(props.record[props.fieldName]),
);
const sourceMatches = computed(
  () => current.value?.content.key === props.content.key && current.value.text === rawText.value,
);
const inputs = computed(() => {
  const value = props.record.$contentLanguages;
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, BusinessContentLanguageInput>)
    : {};
});
const selected = computed(() => {
  const input = inputs.value[props.fieldName];
  return (
    (input ? input.languageTag : sourceMatches.value ? current.value?.source?.sourceLanguage : null) ??
    '__unknown__'
  );
});
const options = computed(() => {
  const values = [
    { value: '__unknown__', label: language.t('platform.content.unknown-language', '来源语言未确定') },
    { value: 'zh-CN', label: '简体中文' },
    { value: 'en', label: 'English' },
    { value: 'de', label: 'Deutsch' },
  ];
  if (!values.some((value) => value.value === selected.value))
    values.push({ value: selected.value, label: selected.value });
  return values;
});
const writable = computed(() => !props.disabled && props.mode !== 'view');
const canChoose = computed(
  () =>
    writable.value &&
    !loading.value &&
    !failed.value &&
    (props.mode === 'create' || Boolean(current.value && current.value.content.key === props.content.key)),
);

async function load() {
  const token = ++generation;
  current.value = undefined;
  failed.value = false;
  loading.value = false;
  historyOpen.value = false;
  history.value = [];
  historyGeneration++;
  if (!id.value || props.mode === 'create' || !props.context) return;
  loading.value = true;
  try {
    const response = await loadBusinessContentSources(
      props.context.http,
      props.context.moduleAlias,
      id.value,
      props.relation,
      props.record,
    );
    if (token === generation) current.value = response.fields[props.fieldName];
  } catch {
    if (token === generation) failed.value = true;
  } finally {
    if (token === generation) loading.value = false;
  }
}
function choose(value: unknown) {
  if (!canChoose.value || typeof value !== 'string') return;
  emit('change', {
    ...inputs.value,
    [props.fieldName]: {
      languageTag: value === '__unknown__' ? null : value,
      expectedRevision: current.value?.expectedRevision ?? null,
    },
  });
}
const historyOpen = ref(false);
const history = ref<BusinessContentRevision[]>([]);
const historyLoading = ref(false);
const historyFailed = ref(false);
const historyPage = ref(1);
let historyGeneration = 0;
async function showHistory(page = 1) {
  if (!props.context || !id.value) return;
  const token = ++historyGeneration;
  historyOpen.value = true;
  historyLoading.value = true;
  historyFailed.value = false;
  try {
    const rows = await loadBusinessContentHistory(
      props.context.http,
      props.context.moduleAlias,
      id.value,
      props.fieldName,
      page,
      props.relation,
    );
    if (token === historyGeneration) {
      history.value = rows;
      historyPage.value = page;
    }
  } catch {
    if (token === historyGeneration) historyFailed.value = true;
  } finally {
    if (token === historyGeneration) historyLoading.value = false;
  }
}
watch(
  [
    () => props.context?.http,
    () => props.context?.moduleAlias,
    () => id.value,
    () => props.record.version,
    () => props.relation?.parentId,
    () => props.relation?.relationCode,
    () => props.fieldName,
    () => props.content.key,
    () => props.mode,
    () => props.sessionKey,
  ],
  () => void load(),
  { immediate: true },
);
</script>

<template>
  <component
    :is="compact ? 'details' : 'div'"
    class="record-content-source"
    :class="{ 'record-content-source--compact': compact }"
    :data-content-field="fieldName"
  >
    <summary v-if="compact">
      {{ language.t('platform.content.source-language-read', '原文语言') }}：{{
        options.find((value) => value.value === selected)?.label
      }}
    </summary>
    <div class="record-content-source__controls">
      <label v-if="writable">
        <span>{{ language.t('platform.content.source-language', '本次原文的填写语言') }}</span>
        <UiSelect
          :value="selected"
          :options="options"
          :disabled="!canChoose"
          :aria-label="language.t('platform.content.source-language', '本次原文的填写语言')"
          @update:value="choose"
        />
      </label>
      <span v-else-if="sourceMatches"
        >{{ language.t('platform.content.source-language-read', '原文语言') }}：{{
          options.find((value) => value.value === selected)?.label
        }}</span
      >
      <span v-if="sourceMatches && current?.source" class="record-content-source__fact">
        {{
          language.t('platform.content.source-version', '原文版本 {revision}', {
            revision: current.source.revision,
          })
        }}
        · {{ language.t('platform.content.author', '作者') }}：{{
          current.source.authorName ??
          current.source.authorId ??
          language.t('platform.content.unknown-author', '历史作者未知')
        }}
      </span>
      <span v-if="loading">{{ language.t('platform.content.loading', '正在核实来源') }}</span>
      <span v-if="failed" role="status"
        >{{ language.t('platform.content.source-unavailable', '暂时无法核实来源，已填写的原文保留') }}
        <UiButton @click="load">{{ language.t('platform.content.retry', '重试') }}</UiButton>
      </span>
      <UiButton v-if="id && mode !== 'create' && context" @click="showHistory()">{{
        language.t('platform.content.history', '原文修订历史')
      }}</UiButton>
    </div>
  </component>
  <UiModal
    :open="historyOpen"
    :title="language.t('platform.content.history', '原文修订历史')"
    :confirm-text="language.t('platform.content.close', '关闭')"
    :width="640"
    @confirm="historyOpen = false"
    @cancel="historyOpen = false"
  >
    <p v-if="historyLoading">{{ language.t('platform.content.loading', '正在核实来源') }}</p>
    <p v-else-if="historyFailed" role="alert">
      {{ language.t('platform.content.history-unavailable', '暂时无法读取历史，请重试') }}
    </p>
    <template v-else>
      <p v-if="!history.length">{{ language.t('platform.content.no-history', '尚无已追踪的原文修订') }}</p>
      <article v-for="revision in history" :key="revision.revision" class="record-content-source__revision">
        <strong>{{
          language.t('platform.content.source-version', '原文版本 {revision}', {
            revision: revision.revision,
          })
        }}</strong>
        <div>
          {{ revision.sourceLanguage ?? language.t('platform.content.unknown-language', '来源语言未确定') }} ·
          {{ language.t('platform.content.author', '作者') }}：{{
            revision.authorName ??
            revision.authorId ??
            language.t('platform.content.unknown-author', '历史作者未知')
          }}
        </div>
        <div v-if="revision.revisionKind === 'UNTRACKED_BASELINE'">
          {{ language.t('platform.content.baseline', '存量原文基线，原填写时间未知') }}
        </div>
        <div v-else-if="revision.revisionKind === 'SOURCE_LANGUAGE_CORRECTION'">
          {{ language.t('platform.content.language-correction', '来源语言修订') }}
        </div>
        <div>
          {{ language.t('platform.content.revised-by', '修订记录人') }}：{{
            revision.revisionAuthorName ?? revision.revisionAuthorId ?? '—'
          }}
          · <DateTimeText :value="revision.createdAt" />
        </div>
        <div v-if="revision.authoredAt">
          {{ language.t('platform.content.authored-at', '原文填写时间') }}：<DateTimeText
            :value="revision.authoredAt"
          />
        </div>
        <p class="record-content-source__text">{{ revision.text ?? '—' }}</p>
      </article>
    </template>
    <div class="record-content-source__pages">
      <UiButton :disabled="historyLoading || historyPage <= 1" @click="showHistory(historyPage - 1)">{{
        language.t('platform.content.previous', '上一页')
      }}</UiButton>
      <UiButton v-if="historyFailed" @click="showHistory(historyPage)">{{
        language.t('platform.content.retry', '重试')
      }}</UiButton>
      <UiButton :disabled="historyLoading || history.length < 20" @click="showHistory(historyPage + 1)">{{
        language.t('platform.content.next', '下一页')
      }}</UiButton>
    </div>
  </UiModal>
</template>

<style scoped>
.record-content-source {
  margin-top: 6px;
  font-size: 12px;
  color: var(--muyun-text-muted);
}
.record-content-source__controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 10px;
}
.record-content-source--compact summary {
  cursor: pointer;
}
.record-content-source--compact[open] .record-content-source__controls {
  margin-top: 6px;
}
.record-content-source label {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.record-content-source label :deep(.ant-select) {
  min-width: 150px;
}
.record-content-source__fact {
  overflow-wrap: anywhere;
}
.record-content-source__revision {
  padding: 12px 0;
  border-bottom: 1px solid var(--muyun-border-subtle);
}
.record-content-source__text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.record-content-source__pages {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
</style>
