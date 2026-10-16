<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { UiButton, UiModal, UiTextArea } from '@muyun/vue-ui-antdv';
import {
  businessContentLanguageContext,
  changeBusinessContentTranslation,
  useBusinessContentReading,
  loadBusinessContentTranslationHistory,
  prepareBusinessContentTranslation,
  useLanguageContext,
  type BusinessContentRelationScope,
  type ModuleContext,
} from '@muyun/web-core';
import type {
  BusinessContentField,
  BusinessContentTranslationMaintenance,
  BusinessContentTranslationRevision,
} from '@muyun/web-contracts';
import DateTimeText from './DateTimeText.vue';

const props = defineProps<{
  record: Record<string, unknown>;
  fieldName: string;
  content: BusinessContentField;
  context?: Pick<ModuleContext<unknown>, 'http' | 'moduleAlias'> &
    Partial<Pick<ModuleContext<unknown>, 'can'>>;
  relation?: BusinessContentRelationScope;
  mode: 'create' | 'edit' | 'view';
  sessionKey?: string | number;
}>();
const language = useLanguageContext();
const readingLanguage = businessContentLanguageContext(language);
const id = computed(() => (props.record.id == null ? undefined : String(props.record.id)));
const raw = computed(() =>
  props.record[props.fieldName] == null ? null : String(props.record[props.fieldName]),
);
const {
  reading,
  loading,
  failed,
  matches,
  status: generationStatus,
  generationBusy,
  generationFailed,
  pollingPaused,
  canRetry,
  load,
  retry: retryGeneration,
} = useBusinessContentReading(() => ({
  http: props.context?.http,
  moduleAlias: props.context?.moduleAlias,
  record: props.record,
  fieldName: props.fieldName,
  content: props.content,
  language: readingLanguage.languageTag.value,
  mode: props.mode,
  relation: props.relation,
  sessionKey: props.sessionKey,
}));
const generationMessage = computed(() => {
  if (
    props.content.policy === 'AUTO_TRANSLATE' &&
    matches.value &&
    reading.value?.originalFallback &&
    raw.value?.trim() &&
    !reading.value.source?.sourceLanguage
  )
    return language.t('platform.content.generation-source-unknown', '登记原文语言后可申请自动翻译');
  if (generationFailed.value)
    return language.t(
      'platform.content.generation-request-failed',
      '暂时无法查询或申请翻译，可重试；原文仍可使用',
    );
  if (pollingPaused.value)
    return language.t('platform.content.generation-waiting', '译文仍在后台处理，可稍后刷新');
  switch (generationStatus.value?.state) {
    case 'PENDING':
    case 'RUNNING':
      return language.t('platform.content.generation-running', '正在生成阅读译文，可继续使用原文');
    case 'RETRY':
      return language.t('platform.content.generation-retrying', '翻译暂时失败，系统将稍后重试；原文仍可使用');
    case 'UNAVAILABLE':
      return language.t('platform.content.generation-unavailable', '自动翻译未启用，原文及人工译文仍可使用');
    case 'REMOVED':
      return language.t('platform.content.generation-removed', '本语言译文已明确删除，可通过维护重新建立');
    case 'UNSUPPORTED':
      return language.t('platform.content.generation-unsupported', '此内容暂不支持自动翻译，可维护人工译文');
    case 'SOURCE_UNKNOWN':
      return language.t('platform.content.generation-source-unknown', '登记原文语言后可申请自动翻译');
    case 'FAILED':
    case 'CANCELLED':
    case 'STALE':
    case 'MISSING':
      return language.t(
        'platform.content.generation-failed',
        '译文未生成，可核实原文及权限后重试，或维护人工译文',
      );
    default:
      return '';
  }
});
const editAllowed = computed(() => props.context?.can?.('contentEdit') === true);
const confirmAllowed = computed(() => props.context?.can?.('contentConfirm') === true);
const editorOpen = ref(false);
const maintenance = ref<BusinessContentTranslationMaintenance>();
const editorLanguage = ref('');
const editorAccess = ref<'edit' | 'confirm'>('edit');
const text = ref('');
const busy = ref(false);
const mutationFailed = ref(false);
const maintenanceFailed = ref(false);
const history = ref<BusinessContentTranslationRevision[]>([]);
const historyOriginal = ref<BusinessContentTranslationMaintenance['original']>();
const historyPage = ref(1);
const historyFailed = ref(false);
const historyLoading = ref(false);
let editorGeneration = 0;
let historyGeneration = 0;
const originalChanged = computed(() =>
  Boolean(maintenance.value && maintenance.value.original.text !== raw.value),
);
const unsavedTranslation = computed(
  () =>
    text.value !==
    (maintenance.value?.draft?.text ??
      maintenance.value?.confirmed?.text ??
      maintenance.value?.machine?.text ??
      ''),
);

function close() {
  if (busy.value) return;
  editorOpen.value = false;
  editorGeneration++;
  historyGeneration++;
}
function resetEditor() {
  editorOpen.value = false;
  maintenance.value = undefined;
  historyOriginal.value = undefined;
  text.value = '';
  busy.value = false;
  historyLoading.value = false;
  editorGeneration++;
  historyGeneration++;
}
async function open() {
  if (!props.context || !id.value || busy.value || !matches.value) return;
  const token = ++editorGeneration;
  editorLanguage.value = readingLanguage.languageTag.value;
  editorAccess.value = editAllowed.value ? 'edit' : 'confirm';
  editorOpen.value = true;
  busy.value = true;
  maintenance.value = undefined;
  maintenanceFailed.value = false;
  mutationFailed.value = false;
  history.value = [];
  try {
    const response = await prepareBusinessContentTranslation(
      props.context.http,
      props.context.moduleAlias,
      id.value,
      props.fieldName,
      editorLanguage.value,
      editorAccess.value,
      props.relation,
    );
    if (token !== editorGeneration) return;
    maintenance.value = response;
    historyOriginal.value = response.original;
    text.value = response.draft?.text ?? response.confirmed?.text ?? response.machine?.text ?? '';
    void loadHistory(1);
  } catch {
    if (token === editorGeneration) maintenanceFailed.value = true;
  } finally {
    if (token === editorGeneration) busy.value = false;
  }
}
async function loadHistory(page: number) {
  if (!props.context || !id.value || !historyOriginal.value) return;
  const token = ++historyGeneration;
  history.value = [];
  historyPage.value = page;
  historyLoading.value = true;
  historyFailed.value = false;
  try {
    const rows = await loadBusinessContentTranslationHistory(
      props.context.http,
      props.context.moduleAlias,
      id.value,
      props.fieldName,
      editorLanguage.value,
      editorAccess.value,
      historyOriginal.value,
      page,
      props.relation,
    );
    if (token === historyGeneration) {
      history.value = rows;
    }
  } catch {
    if (token === historyGeneration) historyFailed.value = true;
  } finally {
    if (token === historyGeneration) historyLoading.value = false;
  }
}
function selectHistory(original: BusinessContentTranslationMaintenance['original']) {
  historyOriginal.value = original;
  void loadHistory(1);
}
function copyPrevious() {
  if (busy.value || !editAllowed.value || originalChanged.value || unsavedTranslation.value) return;
  const previous = maintenance.value?.previousHuman;
  if (previous?.translation.text) text.value = previous.translation.text;
}
async function mutate(operation: 'draft' | 'confirm' | 'remove') {
  if (!props.context || !id.value || !maintenance.value || busy.value || originalChanged.value) return;
  if (
    operation === 'confirm'
      ? !confirmAllowed.value || !maintenance.value.draft || unsavedTranslation.value
      : !editAllowed.value
  )
    return;
  const token = editorGeneration;
  const reviewed = maintenance.value;
  busy.value = true;
  mutationFailed.value = false;
  try {
    const response = await changeBusinessContentTranslation(
      props.context.http,
      props.context.moduleAlias,
      id.value,
      props.fieldName,
      editorLanguage.value,
      operation,
      {
        sourceRevisionId: reviewed.original.revisionId,
        sourceRevision: reviewed.original.revision,
        expectedRevision: reviewed.expectedRevision,
        ...(operation === 'draft' ? { text: text.value } : {}),
        ...(operation === 'confirm' ? { reviewedDraftRevision: reviewed.draft!.revision } : {}),
      },
      props.relation,
      props.record,
    );
    if (token !== editorGeneration) return;
    maintenance.value = response;
    historyOriginal.value = response.original;
    text.value = response.draft?.text ?? response.confirmed?.text ?? response.machine?.text ?? '';
    void loadHistory(1);
    void load();
  } catch {
    if (token === editorGeneration) mutationFailed.value = true;
  } finally {
    if (token === editorGeneration) busy.value = false;
  }
}
function kindLabel(kind: BusinessContentTranslationRevision['kind']) {
  return kind === 'CONFIRMED'
    ? language.t('platform.content.confirmed', '人工确认')
    : kind === 'DRAFT'
      ? language.t('platform.content.draft', '人工草稿（未用于阅读）')
      : kind === 'REMOVED'
        ? language.t('platform.content.removed', '已明确删除')
        : language.t('platform.content.machine', '机器译文');
}
watch(
  [
    () => props.context?.http,
    () => props.context?.moduleAlias,
    () => id.value,
    () => props.fieldName,
    () => props.content.key,
    () => props.relation?.parentId,
    () => props.relation?.relationCode,
    () => props.mode,
    () => props.sessionKey,
  ],
  resetEditor,
);
</script>

<template>
  <section
    v-if="id && context && mode !== 'create'"
    class="record-content-translation"
    :data-translation-field="fieldName"
  >
    <span v-if="loading">{{ language.t('platform.content.reading-loading', '正在读取译文') }}</span>
    <span v-else-if="failed" role="status">{{
      language.t('platform.content.reading-failed', '暂时无法读取译文，原文仍可使用')
    }}</span>
    <template v-else-if="matches && reading">
      <p v-if="reading.humanReviewRequired" role="status">
        {{ language.t('platform.content.human-review-required', '原文已变更，旧人工译文待复核') }}
      </p>
      <div
        v-if="reading.translation"
        class="record-content-translation__text"
        :lang="reading.actualLanguage ?? undefined"
      >
        <strong>{{ readingLanguage.languageTag.value }} · {{ kindLabel(reading.translation.kind) }}</strong>
        <p>{{ reading.text }}</p>
      </div>
      <span v-else-if="reading.originalFallback">{{
        language.t('platform.content.original-fallback', '当前语言暂无有效译文，显示原文')
      }}</span>
      <span v-else>{{ language.t('platform.content.original-language-match', '原文已为当前阅读语言') }}</span>
    </template>
    <span v-else-if="reading">{{
      language.t('platform.content.unsaved-original', '原文有未保存修改，保存后再读取或维护译文')
    }}</span>
    <p v-if="matches && generationMessage" role="status">{{ generationMessage }}</p>
    <div class="record-content-translation__actions">
      <UiButton :disabled="loading" @click="load">{{
        language.t('platform.content.refresh-reading', '刷新译文')
      }}</UiButton>
      <UiButton v-if="canRetry" :disabled="generationBusy" @click="retryGeneration">{{
        language.t('platform.content.retry-generation', '重新申请自动翻译')
      }}</UiButton>
      <UiButton
        v-if="editAllowed || confirmAllowed"
        :disabled="!matches || !raw?.trim() || loading || busy"
        @click="open"
      >
        {{ language.t('platform.content.maintain-translation', '维护／核对译文') }}
      </UiButton>
    </div>
  </section>
  <UiModal
    :open="editorOpen"
    :title="language.t('platform.content.maintain-translation', '维护／核对译文')"
    :confirm-text="language.t('platform.content.close', '关闭')"
    :confirm-disabled="busy"
    :closable="!busy"
    :width="720"
    @confirm="close"
    @cancel="close"
  >
    <p>{{ language.t('platform.content.translation-target', '本次维护语言') }}：{{ editorLanguage }}</p>
    <p v-if="maintenanceFailed" role="alert">
      {{ language.t('platform.content.maintenance-failed', '暂时无法打开译文维护，请关闭后重试') }}
    </p>
    <template v-if="maintenance">
      <p>
        {{
          language.t('platform.content.source-version', '原文版本 {revision}', {
            revision: maintenance.original.revision,
          })
        }}
      </p>
      <p class="record-content-translation__text">{{ maintenance.original.text }}</p>
      <article v-if="maintenance.previousHuman" class="record-content-translation__revision">
        <strong>{{ language.t('platform.content.previous-human', '旧原文的人工译文参考') }}</strong>
        <p>
          {{
            language.t(
              'platform.content.previous-human-rule',
              '此译文对应旧原文，需核对当前原文后保存新草稿；旧确认状态不会沿用。',
            )
          }}
        </p>
        <p>
          {{
            language.t('platform.content.source-version', '原文版本 {revision}', {
              revision: maintenance.previousHuman.original.revision,
            })
          }}
        </p>
        <p class="record-content-translation__text">{{ maintenance.previousHuman.original.text }}</p>
        <strong>{{ kindLabel(maintenance.previousHuman.translation.kind) }}</strong>
        <span>
          ·
          {{
            maintenance.previousHuman.translation.authorName ??
            maintenance.previousHuman.translation.authorId ??
            '—'
          }}
          · <DateTimeText :value="maintenance.previousHuman.translation.createdAt"
        /></span>
        <p class="record-content-translation__text">{{ maintenance.previousHuman.translation.text }}</p>
        <UiButton
          v-if="editAllowed"
          :disabled="busy || originalChanged || unsavedTranslation"
          @click="copyPrevious"
          >{{ language.t('platform.content.copy-previous', '复制到输入框后核对') }}</UiButton
        >
      </article>
      <p>
        {{
          language.t(
            'platform.content.draft-reading-rule',
            '保存人工草稿不会替换普通阅读版本；确认仅表达译文质量，不影响业务办理。',
          )
        }}
      </p>
      <label>
        <span>{{ language.t('platform.content.translation-text', '译文内容') }}</span>
        <UiTextArea :value="text" :disabled="busy || !editAllowed" :rows="5" @update:value="text = $event" />
      </label>
      <p v-if="originalChanged" role="alert">
        {{ language.t('platform.content.unsaved-original', '原文有未保存修改，保存后再读取或维护译文') }}
      </p>
      <p v-if="mutationFailed" role="alert">
        {{
          language.t(
            'platform.content.translation-save-failed',
            '译文未保存，可能原文、译文或权限已变化。本次输入保留，请核实后重新打开维护。',
          )
        }}
      </p>
      <div class="record-content-translation__actions">
        <UiButton
          v-if="editAllowed"
          :disabled="busy || originalChanged || !text.trim()"
          @click="mutate('draft')"
          >{{ language.t('platform.content.save-draft', '保存人工草稿') }}</UiButton
        >
        <UiButton
          v-if="confirmAllowed"
          :disabled="busy || originalChanged || !maintenance.draft || unsavedTranslation"
          @click="mutate('confirm')"
          >{{ language.t('platform.content.confirm-reviewed', '确认已审阅的草稿') }}</UiButton
        >
        <UiButton
          v-if="editAllowed"
          :disabled="busy || originalChanged || !maintenance.expectedRevision"
          @click="mutate('remove')"
          >{{ language.t('platform.content.remove-language', '删除本语言译文') }}</UiButton
        >
      </div>
      <h4>{{ language.t('platform.content.translation-history', '本原文的译文修订历史') }}</h4>
      <div class="record-content-translation__actions">
        <UiButton :disabled="historyLoading" @click="selectHistory(maintenance.original)">{{
          language.t('platform.content.current-translation-history', '查看当前原文的译文历史')
        }}</UiButton>
        <UiButton
          v-if="maintenance.previousHuman"
          :disabled="historyLoading"
          @click="selectHistory(maintenance.previousHuman.original)"
          >{{ language.t('platform.content.previous-translation-history', '查看旧参考的译文历史') }}</UiButton
        >
      </div>
      <p v-if="historyOriginal">
        {{
          language.t('platform.content.source-version', '原文版本 {revision}', {
            revision: historyOriginal.revision,
          })
        }}
      </p>
      <p v-if="historyFailed" role="status">
        {{ language.t('platform.content.history-unavailable', '暂时无法读取历史，请重试') }}
      </p>
      <article v-for="row in history" :key="row.revision" class="record-content-translation__revision">
        <strong>{{ kindLabel(row.kind) }} · {{ row.revision }}</strong>
        <span> · {{ row.authorName ?? row.authorId ?? '—' }} · <DateTimeText :value="row.createdAt" /></span>
        <p class="record-content-translation__text">{{ row.text }}</p>
      </article>
      <div class="record-content-translation__actions">
        <UiButton :disabled="historyLoading || historyPage <= 1" @click="loadHistory(historyPage - 1)">{{
          language.t('platform.content.previous', '上一页')
        }}</UiButton>
        <UiButton v-if="historyFailed" :disabled="historyLoading" @click="loadHistory(historyPage)">{{
          language.t('platform.content.retry', '重试')
        }}</UiButton>
        <UiButton :disabled="historyLoading || history.length < 20" @click="loadHistory(historyPage + 1)">{{
          language.t('platform.content.next', '下一页')
        }}</UiButton>
      </div>
    </template>
  </UiModal>
</template>
<style scoped>
.record-content-translation {
  margin-top: 6px;
  color: var(--muyun-text-muted);
  font-size: 12px;
}
.record-content-translation__text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  color: var(--muyun-text);
}
.record-content-translation__text p {
  margin: 4px 0;
}
.record-content-translation__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}
.record-content-translation__revision {
  border-top: 1px solid var(--muyun-border);
  padding: 8px 0;
}
</style>
