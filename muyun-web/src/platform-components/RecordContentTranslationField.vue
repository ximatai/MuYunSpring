<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { confirmAction, UiActionButton, UiButton, UiModal, UiTextArea } from '@muyun/vue-ui-antdv';
import {
  businessContentLanguageContext,
  businessContentLanguages,
  changeBusinessContentTranslation,
  useBusinessContentReading,
  requestBusinessContentGeneration,
  loadBusinessContentTranslationHistory,
  prepareBusinessContentTranslation,
  useLanguageContext,
  useWorkspaceViewUnsavedState,
  useRecordReadRefreshGuard,
  type BusinessContentRelationScope,
  type ModuleContext,
} from '@muyun/web-core';
import type {
  BusinessContentField,
  BusinessContentTranslationMaintenance,
  BusinessContentTranslationRevision,
} from '@muyun/web-contracts';
import DateTimeText from './DateTimeText.vue';
import { useRecordSurfaceActivity } from './recordSurfaceActivity';

const props = defineProps<{
  record: Record<string, unknown>;
  fieldName: string;
  content: BusinessContentField;
  context?: Pick<ModuleContext<unknown>, 'http' | 'moduleAlias'> &
    Partial<Pick<ModuleContext<unknown>, 'can'>>;
  relation?: BusinessContentRelationScope;
  mode: 'create' | 'edit' | 'view';
  sessionKey?: string | number;
  sourceLanguageLabel?: string;
  displayText?: string;
}>();
const language = useLanguageContext();
const readingLanguage = businessContentLanguageContext(language);
const surfaceActive = useRecordSurfaceActivity();
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
  active: surfaceActive.value,
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
// A presentation decision only: it never changes field policies, source facts or authorization.
const sameLanguage = computed(
  () => matches.value && reading.value && !reading.value.translation && !reading.value.originalFallback,
);
const primaryText = computed(
  () => (matches.value && reading.value ? reading.value.text : (props.displayText ?? raw.value)) || '—',
);
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
      if (generationStatus.value.reference)
        return language.t(
          'platform.content.generation-previously-unavailable',
          '上次生成暂不可用，可核实配置后重试；原文及人工译文仍可使用',
        );
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
const maintenanceBody = ref<HTMLElement>();
useRecordReadRefreshGuard(() => editorOpen.value);
const maintenance = ref<BusinessContentTranslationMaintenance>();
const editorLanguage = ref('');
const editorRawBaseline = ref<string | null>();
const savedOperation = ref('');
const maintenanceReloaded = ref(false);
const sourceLanguageName = computed(
  () =>
    businessContentLanguages.find((entry) => entry.value === maintenance.value?.original.sourceLanguage)
      ?.label ??
    maintenance.value?.original.sourceLanguage ??
    language.t('platform.content.unknown-language', '来源语言未确定'),
);
const editorLanguageName = computed(
  () =>
    businessContentLanguages.find((entry) => entry.value === editorLanguage.value)?.label ??
    editorLanguage.value,
);
const editorAccess = ref<'edit' | 'confirm'>('edit');
const text = ref('');
const busy = ref(false);
const confirming = ref(false);
const mutationFailed = ref(false);
const maintenanceFailed = ref(false);
const history = ref<BusinessContentTranslationRevision[]>([]);
const historyOriginal = ref<BusinessContentTranslationMaintenance['original']>();
const historyPage = ref(1);
const historyFailed = ref(false);
const historyLoading = ref(false);
const machineRequestState = ref('');
const machineRequestMessage = computed(() => {
  const state = machineRequestState.value;
  if (!state) return '';
  if (state === 'UNAVAILABLE')
    return language.t('platform.content.generation-unavailable', '自动翻译未启用，原文及人工译文仍可使用');
  if (state === 'SOURCE_UNKNOWN')
    return language.t('platform.content.generation-source-unknown', '登记原文语言后可申请自动翻译');
  if (state === 'ORIGINAL')
    return language.t('platform.content.original-language-match', '原文已为当前阅读语言');
  return ['PENDING', 'RUNNING', 'RETRY', 'READY'].includes(state)
    ? language.t(
        'platform.content.machine-draft-requested',
        '机器起草已受理，可稍后读取最新版本核对结果；本次人工输入保持不变。',
      )
    : language.t(
        'platform.content.generation-request-failed',
        '暂时无法查询或申请翻译，可重试；原文仍可使用',
      );
});
let editorGeneration = 0;
let historyGeneration = 0;
const originalChanged = computed(() => Boolean(maintenance.value && editorRawBaseline.value !== raw.value));
const unsavedTranslation = computed(
  () =>
    text.value !==
    (maintenance.value?.draft?.text ??
      maintenance.value?.confirmed?.text ??
      maintenance.value?.machine?.text ??
      ''),
);

const dirty = computed(() => editorOpen.value && Boolean(maintenance.value) && unsavedTranslation.value);
useWorkspaceViewUnsavedState(
  () => language.t('platform.content.maintain-translation', '维护／核对译文'),
  () => dirty.value,
  () => editorOpen.value && (busy.value || confirming.value),
);
async function close() {
  if (busy.value || confirming.value) return;
  const token = editorGeneration;
  if (dirty.value) {
    confirming.value = true;
    try {
      if (
        !(await confirmAction(
          {
            title: language.t('platform.content.discard-input-title', '放弃未保存的译文修改？'),
            danger: true,
            presentation: () => ({
              title: language.t('platform.content.discard-input-title', '放弃未保存的译文修改？'),
              content: language.t(
                'platform.content.discard-input-description',
                '本次修改尚未保存，放弃后不会生成译文草稿。',
              ),
              okText: language.t('platform.content.discard-input', '放弃修改'),
              cancelText: language.t('platform.content.continue-editing', '继续编辑'),
            }),
          },
          language,
        ))
      )
        return;
    } finally {
      confirming.value = false;
    }
  }
  if (token !== editorGeneration) return;
  editorOpen.value = false;
  editorGeneration++;
  historyGeneration++;
}
async function removeTranslation() {
  if (!maintenance.value || busy.value || confirming.value || !editAllowed.value || originalChanged.value)
    return;
  const token = editorGeneration;
  confirming.value = true;
  try {
    const accepted = await confirmAction(
      {
        title: language.t('platform.content.remove-language-title', '删除本语言译文？'),
        danger: true,
        presentation: () => ({
          title: language.t('platform.content.remove-language-title', '删除本语言译文？'),
          content: language.t(
            'platform.content.remove-language-description',
            '将删除 {language} 译文及本次未保存输入，阅读将回退到原文。业务原文和受权修订历史保留。',
            { language: editorLanguage.value },
          ),
          okText: language.t('platform.content.remove-language', '删除本语言译文'),
        }),
      },
      language,
    );
    if (accepted && token === editorGeneration) await mutate('remove');
  } finally {
    confirming.value = false;
  }
}
function resetEditor() {
  editorOpen.value = false;
  maintenance.value = undefined;
  savedOperation.value = '';
  maintenanceReloaded.value = false;
  machineRequestState.value = '';
  historyOriginal.value = undefined;
  text.value = '';
  busy.value = false;
  historyLoading.value = false;
  editorGeneration++;
  historyGeneration++;
}
onBeforeUnmount(resetEditor);
async function open() {
  if (!props.context || !id.value || busy.value || !matches.value) return;
  const token = ++editorGeneration;
  editorLanguage.value = readingLanguage.languageTag.value;
  editorRawBaseline.value = raw.value;
  savedOperation.value = '';
  maintenanceReloaded.value = false;
  editorAccess.value = editAllowed.value ? 'edit' : 'confirm';
  editorOpen.value = true;
  busy.value = true;
  maintenance.value = undefined;
  maintenanceFailed.value = false;
  machineRequestState.value = '';
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
async function reloadMaintenance() {
  if (!props.context || !id.value || busy.value || originalChanged.value) return;
  const token = editorGeneration;
  const rawAtRequest = raw.value;
  busy.value = true;
  maintenanceFailed.value = false;
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
    editorRawBaseline.value = rawAtRequest;
    historyOriginal.value = response.original;
    mutationFailed.value = false;
    maintenanceReloaded.value = true;
    if (maintenanceBody.value) maintenanceBody.value.scrollTop = 0;
    savedOperation.value = '';
    void loadHistory(1);
  } catch {
    if (token === editorGeneration) maintenanceFailed.value = true;
  } finally {
    if (token === editorGeneration) busy.value = false;
  }
}
function copyMachine() {
  if (
    !busy.value &&
    editAllowed.value &&
    !originalChanged.value &&
    (!text.value.trim() || !unsavedTranslation.value)
  )
    text.value = maintenance.value?.machine?.text ?? text.value;
}
async function requestMachineDraft() {
  if (
    !props.context ||
    !id.value ||
    !maintenance.value ||
    busy.value ||
    !editAllowed.value ||
    originalChanged.value ||
    unsavedTranslation.value ||
    maintenance.value.draft ||
    maintenance.value.confirmed
  )
    return;
  const token = editorGeneration;
  busy.value = true;
  machineRequestState.value = '';
  try {
    const status = await requestBusinessContentGeneration(
      props.context.http,
      props.context.moduleAlias,
      id.value,
      props.fieldName,
      editorLanguage.value,
      true,
      { relation: props.relation },
    );
    if (token !== editorGeneration) return;
    machineRequestState.value = status.state;
  } catch {
    if (token === editorGeneration) machineRequestState.value = 'REQUEST_FAILED';
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
  savedOperation.value = '';
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
    savedOperation.value = operation;
    maintenanceReloaded.value = false;
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
watch(text, () => {
  if (unsavedTranslation.value) savedOperation.value = '';
});
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
    () => props.relation?.workflowInstanceId,
    () => props.relation?.history,
    () => props.mode,
    () => props.sessionKey,
  ],
  resetEditor,
);
</script>

<template>
  <section class="record-content-translation" :data-translation-field="fieldName">
    <template v-if="mode === 'view'">
      <div
        class="record-content-translation__text"
        :lang="matches ? (reading?.actualLanguage ?? undefined) : undefined"
      >
        {{ primaryText }}
      </div>
      <small v-if="loading" class="record-content-translation__status" role="status">{{
        language.t('platform.content.reading-loading', '正在读取译文')
      }}</small>
      <small v-if="matches && reading?.translation" class="record-content-translation__status">{{
        kindLabel(reading.translation.kind)
      }}</small>
    </template>
    <span v-if="failed" role="status">{{
      language.t('platform.content.reading-failed', '暂时无法读取译文，原文仍可使用')
    }}</span>
    <span
      v-else-if="matches && reading?.originalFallback && raw?.trim()"
      class="record-content-translation__status"
      role="status"
      >{{ language.t('platform.content.original-fallback', '当前语言暂无有效译文，显示原文') }}</span
    >
    <p v-if="matches && reading?.humanReviewRequired" role="status">
      {{ language.t('platform.content.human-review-required', '原文已变更，旧人工译文待复核') }}
    </p>
    <details class="record-content-translation__details">
      <summary>
        {{
          mode === 'view'
            ? language.t('platform.content.original-and-language', '原文与语言')
            : language.t('platform.content.writing-language-summary', '填写语言：{language}', {
                language:
                  sourceLanguageLabel ?? language.t('platform.content.unknown-language', '来源语言未确定'),
              })
        }}
      </summary>
      <slot name="source"
        ><p class="record-content-translation__text">{{ raw ?? '—' }}</p></slot
      >
      <template v-if="id && context && mode !== 'create'">
        <span v-if="loading">{{ language.t('platform.content.reading-loading', '正在读取译文') }}</span>
        <template v-else-if="matches && reading">
          <div
            v-if="mode !== 'view' && reading.translation"
            class="record-content-translation__text"
            :lang="reading.actualLanguage ?? undefined"
          >
            <strong>{{ kindLabel(reading.translation.kind) }}</strong>
            <p>{{ reading.text }}</p>
          </div>
          <span v-else-if="sameLanguage">{{
            language.t('platform.content.original-language-match', '原文已为当前阅读语言')
          }}</span>
        </template>
        <span v-else-if="reading">{{
          language.t('platform.content.unsaved-original', '原文有未保存修改，保存后再读取或维护译文')
        }}</span>
        <p v-if="matches && generationMessage" role="status">{{ generationMessage }}</p>
        <div v-if="!sameLanguage" class="record-content-translation__actions">
          <UiActionButton :disabled="loading" density="compact" @click="load">{{
            language.t('platform.content.refresh-reading', '刷新译文')
          }}</UiActionButton>
          <UiActionButton
            v-if="canRetry"
            :disabled="generationBusy"
            density="compact"
            @click="retryGeneration"
            >{{ language.t('platform.content.retry-generation', '重新申请自动翻译') }}</UiActionButton
          >
          <UiActionButton
            v-if="editAllowed || confirmAllowed"
            :disabled="!matches || !raw?.trim() || loading || busy"
            density="compact"
            @click="open"
            >{{ language.t('platform.content.maintain-translation', '维护／核对译文') }}</UiActionButton
          >
        </div>
      </template>
    </details>
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
    <div ref="maintenanceBody" class="record-content-maintenance">
      <p class="record-content-maintenance__scope">
        {{ language.t('platform.content.translation-target', '本次维护语言') }}：<strong>{{
          editorLanguageName
        }}</strong>
      </p>
      <p class="record-content-translation__status">
        {{ language.t('platform.content.translation-only', '这里只修订译文；修改业务原文请返回业务表单。') }}
      </p>
      <p v-if="busy && !maintenance" role="status">
        {{ language.t('platform.content.maintenance-loading', '正在读取原文与译文') }}
      </p>
      <p v-if="maintenanceFailed" role="alert">
        {{ language.t('platform.content.maintenance-retry', '暂时无法读取维护版本，本次输入保留，可重试。') }}
        <UiButton :disabled="busy" @click="maintenance ? reloadMaintenance() : open()">{{
          language.t('platform.content.retry', '重试')
        }}</UiButton>
      </p>
      <template v-if="maintenance">
        <div class="record-content-maintenance__comparison">
          <section>
            <h4>{{ language.t('platform.content.current-original', '当前业务原文') }}</h4>
            <p class="record-content-translation__status">
              {{
                language.t('platform.content.source-version', '原文版本 {revision}', {
                  revision: maintenance.original.revision,
                })
              }}
              · {{ maintenance.original.authorName ?? maintenance.original.authorId ?? '—' }} ·
              {{ sourceLanguageName }}
            </p>
            <p class="record-content-translation__text">{{ maintenance.original.text }}</p>
          </section>
          <section>
            <h4>{{ language.t('platform.content.current-reading', '当前普通阅读版本') }}</h4>
            <p class="record-content-translation__status">
              {{
                maintenance.confirmed
                  ? kindLabel('CONFIRMED')
                  : maintenance.machine
                    ? kindLabel('MACHINE')
                    : language.t('platform.content.reading-original', '显示原文')
              }}
            </p>
            <p class="record-content-translation__text">
              {{ maintenance.confirmed?.text ?? maintenance.machine?.text ?? maintenance.original.text }}
            </p>
          </section>
        </div>
        <p v-if="maintenance.previousHuman" role="status">
          {{
            language.t(
              'platform.content.previous-human-notice',
              '有旧原文的人工译文可供参考，当前译文需按当前原文独立核对。',
            )
          }}
        </p>
        <details
          v-if="maintenance.previousHuman"
          class="record-content-translation__details record-content-translation__revision"
        >
          <summary>{{ language.t('platform.content.previous-human', '旧原文的人工译文参考') }}</summary>
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
        </details>
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
          <UiTextArea
            :value="text"
            :disabled="busy || !editAllowed"
            :rows="5"
            @update:value="text = $event"
          />
        </label>
        <p v-if="originalChanged" role="alert">
          {{ language.t('platform.content.unsaved-original', '原文有未保存修改，保存后再读取或维护译文') }}
        </p>
        <p v-if="mutationFailed" role="alert">
          {{
            language.t(
              'platform.content.translation-save-failed',
              '译文未保存，可能原文、译文或权限已变化。本次输入保留，可读取最新版本后重新核对。',
            )
          }}
        </p>
        <p v-if="maintenanceReloaded" role="status">
          {{
            language.t(
              'platform.content.maintenance-reloaded',
              '已读取最新版本，本次输入保留；请对照当前原文核对后保存。',
            )
          }}
        </p>
        <p v-if="savedOperation" role="status">
          {{
            savedOperation === 'draft'
              ? language.t(
                  'platform.content.draft-saved',
                  '人工草稿已保存，普通阅读版本未改变；核对完成后可确认。',
                )
              : savedOperation === 'confirm'
                ? language.t('platform.content.confirmation-saved', '已确认，关闭后相关页面将更新阅读版本。')
                : language.t('platform.content.removal-saved', '本语言译文已删除，普通阅读回退到原文。')
          }}
        </p>
        <p v-if="machineRequestMessage" role="status">{{ machineRequestMessage }}</p>
        <div class="record-content-translation__actions">
          <UiButton
            v-if="editAllowed && content.policy === 'MANUAL_TRANSLATIONS'"
            :disabled="
              busy ||
              originalChanged ||
              unsavedTranslation ||
              Boolean(maintenance.draft || maintenance.confirmed)
            "
            @click="requestMachineDraft"
            >{{ language.t('platform.content.request-machine-draft', '机器起草') }}</UiButton
          >
          <UiButton
            v-if="maintenance.machine && editAllowed"
            :disabled="busy || originalChanged || Boolean(text.trim() && unsavedTranslation)"
            @click="copyMachine"
            >{{ language.t('platform.content.use-machine', '使用机器译文') }}</UiButton
          >
          <UiButton :disabled="busy || originalChanged" @click="reloadMaintenance">{{
            language.t('platform.content.reload-maintenance', '读取最新版本，保留输入')
          }}</UiButton>
        </div>
        <details v-if="editAllowed" class="record-content-translation__details">
          <summary>{{ language.t('platform.content.translation-more', '更多译文操作') }}</summary>
          <UiActionButton
            v-if="editAllowed"
            intent="danger"
            :disabled="busy || originalChanged || !maintenance.expectedRevision"
            @click="removeTranslation"
            >{{ language.t('platform.content.remove-language', '删除本语言译文') }}</UiActionButton
          >
        </details>
        <details class="record-content-translation__details">
          <summary>{{ language.t('platform.content.translation-history', '本原文的译文修订历史') }}</summary>
          <div class="record-content-translation__actions">
            <UiButton :disabled="historyLoading" @click="selectHistory(maintenance.original)">{{
              language.t('platform.content.current-translation-history', '查看当前原文的译文历史')
            }}</UiButton>
            <UiButton
              v-if="maintenance.previousHuman"
              :disabled="historyLoading"
              @click="selectHistory(maintenance.previousHuman.original)"
              >{{
                language.t('platform.content.previous-translation-history', '查看旧参考的译文历史')
              }}</UiButton
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
            <span>
              · {{ row.authorName ?? row.authorId ?? '—' }} · <DateTimeText :value="row.createdAt"
            /></span>
            <p class="record-content-translation__text">{{ row.text }}</p>
          </article>
          <div class="record-content-translation__actions">
            <UiButton :disabled="historyLoading || historyPage <= 1" @click="loadHistory(historyPage - 1)">{{
              language.t('platform.content.previous', '上一页')
            }}</UiButton>
            <UiButton v-if="historyFailed" :disabled="historyLoading" @click="loadHistory(historyPage)">{{
              language.t('platform.content.retry', '重试')
            }}</UiButton>
            <UiButton
              :disabled="historyLoading || history.length < 20"
              @click="loadHistory(historyPage + 1)"
              >{{ language.t('platform.content.next', '下一页') }}</UiButton
            >
          </div>
        </details>
      </template>
    </div>
    <template #operation>
      <div class="record-content-maintenance__operations">
        <UiActionButton :disabled="busy || confirming" @click="close">{{
          language.t('platform.content.close', '关闭')
        }}</UiActionButton>
        <template v-if="maintenance">
          <UiActionButton
            v-if="editAllowed"
            :emphasis="unsavedTranslation || !maintenance.draft ? 'primary' : 'secondary'"
            :disabled="busy || originalChanged || !text.trim()"
            @click="mutate('draft')"
            >{{ language.t('platform.content.save-draft', '保存人工草稿') }}</UiActionButton
          >
          <UiActionButton
            v-if="confirmAllowed"
            :emphasis="maintenance.draft && !unsavedTranslation ? 'primary' : 'secondary'"
            :disabled="busy || originalChanged || !maintenance.draft || unsavedTranslation"
            @click="mutate('confirm')"
            >{{ language.t('platform.content.confirm-reviewed', '确认已审阅的草稿') }}</UiActionButton
          >
        </template>
      </div>
    </template>
  </UiModal>
</template>
<style scoped>
.record-content-maintenance {
  display: grid;
  gap: 12px;
  max-height: min(65vh, 680px);
  overflow: auto;
}
.record-content-maintenance p,
.record-content-maintenance h4 {
  margin: 0;
}
.record-content-maintenance__comparison {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  padding: 12px;
  background: var(--muyun-surface);
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 6px;
}
.record-content-maintenance__comparison section,
.record-content-maintenance label {
  display: grid;
  gap: 8px;
  min-width: 0;
}
.record-content-maintenance__operations {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}
@media (max-width: 600px) {
  .record-content-maintenance__comparison {
    grid-template-columns: minmax(0, 1fr);
  }
}
.record-content-translation {
  min-width: 0;
}
.record-content-translation__text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  color: var(--muyun-text-body);
}
.record-content-translation__text p {
  margin: 4px 0;
}
.record-content-translation__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}
.record-content-translation__status {
  display: block;
  color: var(--muyun-text-muted);
  font-size: 12px;
}
.record-content-translation__details {
  margin-top: 8px;
  color: var(--muyun-text-muted);
  font-size: 12px;
}
.record-content-translation__details > summary {
  cursor: pointer;
  width: fit-content;
}
.record-content-translation__details[open] > summary {
  margin-bottom: 8px;
}
.record-content-translation__revision {
  border-top: 1px solid var(--muyun-border);
  padding: 8px 0;
}
</style>
