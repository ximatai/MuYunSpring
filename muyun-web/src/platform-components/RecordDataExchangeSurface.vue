<script setup lang="ts">
import { UiModal, UiSelect, UiActionButton } from '@muyun/vue-ui-antdv';
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue';
import {
  useLanguageContext,
  businessContentLanguages,
  businessContentLanguageContext,
  createDataExchangeClient,
  normalizeError,
  type ModuleContext,
  type ExchangeCommand,
  type ExchangePreview,
  type ExchangeResult,
  type ExchangePurpose,
  type ImportDuplicateStrategy,
} from '@muyun/web-core';
import RecordPanelButton from './RecordPanelButton.vue';
import type { RecordActionItem } from './recordActionBarModel';
import type { RecordQueryListQueryController } from './recordQueryListQueryController';

const language = useLanguageContext();
const readingLanguage = businessContentLanguageContext(language);
defineOptions({ name: 'RecordDataExchangeSurface' });
const props = withDefaults(
  defineProps<{
    context: ModuleContext<unknown>;
    queryController?: RecordQueryListQueryController;
    selectedIds?: string[];
    scopeKey: string;
    disabled?: boolean;
    showTrigger?: boolean;
  }>(),
  { showTrigger: true },
);
const emit = defineEmits<{ busy: [value: boolean]; changed: [] }>();
const supported = computed(() => props.context.runtime.snapshot()?.capabilities.includes('EXCHANGE'));
const canImport = computed(() => supported.value && props.context.can('import') === true);
const canExport = computed(() => supported.value && props.context.can('export') === true);
const open = ref(false);
const filesOpen = ref(false);
const busy = ref(false);
const activity = ref('');
const error = ref('');
const fileError = ref('');
const action = computed<RecordActionItem | undefined>(() =>
  canImport.value || canExport.value
    ? {
        key: '__platform-data-files',
        title: language.t('platform.screen.ModuleDataExchangeSurface.data-files', '业务文件'),
        actionLevel: 'secondary',
        disabled: props.disabled || busy.value,
      }
    : undefined,
);
function openFiles() {
  if (action.value && !action.value.disabled) {
    exportRange.value = props.queryController ? 'query' : 'selected';
    filesOpen.value = true;
  }
}
defineExpose({ action, openFiles });
const file = shallowRef<File>();
const preview = shallowRef<ExchangePreview>();
const result = shallowRef<ExchangeResult>();
const matchFields = ref<Record<string, string>>({});
const strategies = ref<Record<string, ImportDuplicateStrategy>>({});
const confirmed = ref<Record<string, string>>({});
const sourceLanguage = ref('');
const resolutionLanguage = ref('');
const fileLanguage = ref(readingLanguage.languageTag.value);
const exportPurpose = ref<ExchangePurpose>('DATA');
const exportRange = ref('query');
const rowFilter = ref('auto');
const reviewed = ref(false);
const shownRows = ref(100);
const inputKey = ref(0);
const uploadInput = ref<HTMLInputElement>();
let revision = 0;
onBeforeUnmount(() => {
  revision++;
  emit('busy', false);
});

watch(
  () => [props.context, props.scopeKey],
  () => {
    revision++;
    busy.value = false;
    emit('busy', false);
    open.value = false;
    filesOpen.value = false;
    clear();
  },
);
watch(
  [matchFields, strategies, confirmed, sourceLanguage, resolutionLanguage],
  () => {
    reviewed.value = false;
  },
  { deep: true },
);
function clear() {
  file.value = undefined;
  preview.value = undefined;
  result.value = undefined;
  error.value = '';
  fileError.value = '';
  matchFields.value = {};
  strategies.value = {};
  confirmed.value = {};
  sourceLanguage.value = '';
  resolutionLanguage.value = '';
  reviewed.value = false;
  shownRows.value = 100;
  rowFilter.value = 'auto';
  inputKey.value++;
}
function key(row: string, field: string) {
  return JSON.stringify([row, field]);
}
function command(): ExchangeCommand {
  return {
    documentId: preview.value?.documentId,
    previewDigest: preview.value?.previewDigest,
    sourceLanguage: sourceLanguage.value || undefined,
    resolutionLanguage: resolutionLanguage.value || undefined,
    sheets:
      preview.value?.purpose === 'TRANSLATIONS'
        ? []
        : (preview.value?.sheets
            .filter((sheet) => needsMatching(sheet))
            .map((sheet) => ({
              entityAlias: sheet.entityAlias,
              matchField: matchFields.value[sheet.entityAlias] ?? '',
              duplicateStrategy: strategies.value[sheet.entityAlias] ?? 'ERROR',
            })) ?? []),
    confirmations:
      preview.value?.rows.flatMap((row) =>
        row.choices.flatMap((choice) => {
          const id = confirmed.value[key(row.row, choice.field)];
          return id ? [{ row: row.row, field: choice.field, input: choice.input, candidateId: id }] : [];
        }),
      ) ?? [],
  };
}
async function run(
  kind: string,
  work: (client: ReturnType<typeof createDataExchangeClient>, current: () => boolean) => Promise<void>,
) {
  if (busy.value || props.disabled) return;
  const ownRevision = ++revision;
  busy.value = true;
  activity.value = kind;
  emit('busy', true);
  const operationError = kind === 'export' ? fileError : error;
  operationError.value = '';
  try {
    await work(
      createDataExchangeClient(props.context.http, props.context.moduleAlias),
      () => ownRevision === revision,
    );
  } catch (cause) {
    if (ownRevision === revision) operationError.value = normalizeError(cause).message;
  } finally {
    if (ownRevision === revision) {
      busy.value = false;
      emit('busy', false);
    }
  }
}
function save(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
async function upload(event: Event) {
  const selected = (event.target as HTMLInputElement).files?.[0];
  if (!selected || !canImport.value) return;
  clear();
  file.value = selected;
  await run('upload', async (client, current) => {
    const parsed = await client.preview(selected, command());
    if (!current()) return;
    preview.value = parsed;
    for (const sheet of parsed.sheets) {
      matchFields.value[sheet.entityAlias] =
        sheet.recommendedMatchField && sheet.matchFields.includes(sheet.recommendedMatchField)
          ? sheet.recommendedMatchField
          : sheet.matchFields.length === 1
            ? sheet.matchFields[0]!
            : '';
      strategies.value[sheet.entityAlias] = 'ERROR';
    }
  });
}
function needsMatching(sheet: ExchangePreview['sheets'][number]) {
  if (!sheet.main && sheet.rowCount === 0) return false;
  const rows = preview.value?.rows.filter((row) => row.entity === sheet.entityAlias) ?? [];
  // Only a successful server verification may classify a sheet as read-only context.
  return !rows.length || !rows.every((row) => row.operation === 'CONTEXT');
}
const ready = computed(
  () =>
    canImport.value &&
    file.value &&
    preview.value &&
    !result.value &&
    (preview.value.purpose === 'TRANSLATIONS' ||
      preview.value.sheets
        .filter((sheet) => needsMatching(sheet))
        .every((sheet) => matchFields.value[sheet.entityAlias])),
);
async function review() {
  if (!ready.value || !file.value) return;
  reviewed.value = false;
  const selected = file.value;
  const intent = command();
  await run('review', async (client, current) => {
    const parsed = await client.preview(selected, intent);
    if (!current()) return;
    // Keep prior explicit confirmations even when a successful resolution no longer needs a choice.
    preview.value = {
      ...parsed,
      rows: parsed.rows.map((row) => ({
        ...row,
        choices: [
          ...row.choices,
          ...(preview.value?.rows
            .find((old) => old.row === row.row)
            ?.choices.filter(
              (old) =>
                intent.confirmations.some(
                  (choice) =>
                    choice.row === row.row && choice.field === old.field && choice.input === old.input,
                ) && !row.choices.some((choice) => choice.field === old.field),
            ) ?? []),
        ],
      })),
    };
    reviewed.value = true;
  });
}
async function execute() {
  if (!ready.value || !reviewed.value || !file.value) return;
  const selected = file.value;
  const intent = command();
  await run('import', async (client, current) => {
    const receipt = await client.importFile(selected, intent);
    if (!current()) return;
    result.value = receipt;
    if (receipt.created + receipt.updated > 0) emit('changed');
  });
}
async function template() {
  if (!canImport.value) return;
  await run('template', async (client, current) => {
    const blob = await client.fileTemplate({ purpose: 'DATA', language: fileLanguage.value });
    if (current()) save(blob, `${props.context.moduleAlias}-data-template.xlsx`);
  });
}
function errors() {
  if (!result.value?.errorWorkbookBase64 || !canImport.value || busy.value) return;
  const binary = atob(result.value.errorWorkbookBase64);
  const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
  save(
    new Blob([bytes], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }),
    `${props.context.moduleAlias}-failed-rows.xlsx`,
  );
}
async function exportData(selected: boolean) {
  if (!canExport.value) return;
  const query = props.queryController?.snapshot();
  const ids = selected ? props.selectedIds : undefined;
  if (selected ? !ids?.length : query?.status !== 'ready' || query.mode !== 'normal' || !query.request) {
    fileError.value = language.t(
      'platform.screen.ModuleDataExchangeSurface.wait-for-the-current-list-query-to',
      '请等待当前列表查询成功后再导出。',
    );
    return;
  }
  const purpose = exportPurpose.value;
  const target = fileLanguage.value;
  await run('export', async (client, current) => {
    const blob = await client.exportFile(
      selected ? undefined : query?.request,
      { purpose, language: target },
      ids,
      selected ? undefined : { contentLanguage: query?.contentLanguage },
    );
    if (current()) save(blob, `${props.context.moduleAlias}-${purpose.toLowerCase()}-${target}.xlsx`);
  });
}
function unresolved(row: ExchangePreview['rows'][number]) {
  return row.choices.some((choice) => !confirmed.value[key(row.row, choice.field)]);
}
const attentionRows = computed(
  () => preview.value?.rows.filter((row) => row.operation === 'ERROR' || unresolved(row)) ?? [],
);
const activeFilter = computed(() =>
  rowFilter.value === 'auto' ? (attentionRows.value.length ? 'attention' : 'all') : rowFilter.value,
);
const filteredRows = computed(() =>
  activeFilter.value === 'attention' ? attentionRows.value : (preview.value?.rows ?? []),
);
const visibleRows = computed(() => filteredRows.value.slice(0, shownRows.value));
const languageOptions = computed(() => businessContentLanguages.map((item) => ({ ...item })));
function selectedString(value: unknown) {
  return typeof value === 'string' ? value : '';
}
function text(key: string, fallback: string, params?: Record<string, string | number>) {
  return language.t(`platform.screen.ModuleDataExchangeSurface.${key}`, fallback, params);
}
const busyMessage = computed(
  () =>
    ({
      upload: text('uploading-file', '正在读取文件，尚未写入业务数据。'),
      review: text('reviewing-file', '正在核对文件和当前业务数据，尚未执行导入。'),
      import: text('importing-file', '正在导入，请勿重复提交；完成后可单独修正失败项。'),
      export: text('exporting-file', '正在生成导出文件。'),
      template: text('preparing-template', '正在生成导入模板。'),
    })[activity.value as 'upload' | 'review' | 'import' | 'export' | 'template'],
);
const purposeOptions = computed(() => [
  { value: 'DATA', label: text('business-edit-file', '业务填写与修改') },
  { value: 'TRANSLATIONS', label: text('translation-file', '人工译文修订（保存草稿）') },
  { value: 'READING', label: text('reading-file', '阅读导出（不可回导）') },
]);
const rangeOptions = computed(() => [
  ...(props.queryController ? [{ value: 'query', label: text('export-current-query', '导出当前查询') }] : []),
  {
    value: 'selected',
    label: text('selected-range', '当前所选记录（{count} 条）', { count: props.selectedIds?.length ?? 0 }),
    disabled: !props.selectedIds?.length,
  },
]);
const strategyOptions = computed(() => [
  { value: 'ERROR', label: text('report-an-error-and-retain-existing-data', '报错，保留已有数据') },
  { value: 'SKIP', label: text('skip-existing-records', '跳过已有记录') },
  { value: 'OVERWRITE', label: text('apply-submitted-changes', '应用本次填写的修改') },
]);
function languageLabel(tag?: string) {
  return (
    businessContentLanguages.find((item) => item.value === tag)?.label ??
    tag ??
    text('original-language', '原文语言')
  );
}
function rowLabel(row: ExchangePreview['rows'][number]) {
  const sheet = preview.value?.sheets.find((item) => item.entityAlias === row.entity);
  return `${sheet?.sheetName ?? row.entity} · ${sheet?.main ? text('main-sheet', '主表') : text('child-sheet', '子表')}`;
}
const waitingChoices = computed(() =>
  preview.value?.rows.some((row) =>
    row.choices.some((choice) => !confirmed.value[key(row.row, choice.field)]),
  ),
);
const hasIndependentReadyGroup = computed(() => {
  const groups = new Map<string, ExchangePreview['rows']>();
  for (const row of preview.value?.rows ?? []) {
    const group = preview.value?.purpose === 'TRANSLATIONS' ? key(row.row, row.entity) : row.relation;
    const rows = groups.get(group) ?? [];
    rows.push(row);
    groups.set(group, rows);
  }
  return [...groups.values()].some((rows) =>
    rows.every(
      (row) =>
        row.operation !== 'ERROR' &&
        row.choices.every((choice) => Boolean(confirmed.value[key(row.row, choice.field)])),
    ),
  );
});
function display(value: unknown) {
  return typeof value === 'string' ? value : value === null ? '∅' : JSON.stringify(value);
}
function referenceDisplay(
  row: ExchangePreview['rows'][number],
  field: string | number,
  kind: 'current' | 'submitted',
  value: unknown,
) {
  const reference = row.references?.[field];
  return reference && value != null
    ? reference[kind] || text('unreadable-reference', '关联对象名称当前不可读')
    : display(value);
}
function fieldLabel(entity: string, field: string) {
  if (field === '__translation')
    return language.t(
      'platform.screen.ModuleDataExchangeSurface.translation-file',
      '人工译文修订（保存草稿）',
    );
  return preview.value?.sheets.find((sheet) => sheet.entityAlias === entity)?.fieldTitles[field] ?? field;
}
function operationLabel(operation: string) {
  return language.t(`platform.screen.ModuleDataExchangeSurface.operation-${operation}`, operation);
}
</script>

<template>
  <div
    v-if="canImport || canExport"
    class="module-data-exchange"
    :class="{ 'module-data-exchange--managed': !showTrigger }"
  >
    <RecordPanelButton v-if="showTrigger" :disabled="disabled || busy" @click="openFiles">{{
      text('data-files', '业务文件')
    }}</RecordPanelButton>
    <UiModal
      :open="filesOpen"
      scroll-content
      :title="text('data-files', '业务文件')"
      width="560px"
      :closable="!busy"
      @cancel="!busy && (filesOpen = false)"
    >
      <div class="exchange-file-options">
        <section v-if="canImport" class="exchange-section">
          <h3>{{ text('import-data', '导入数据') }}</h3>
          <p class="exchange-hint">
            {{ text('import-intro', '上传业务文件，核对本次修改后导入。失败项可单独修正重传。') }}
          </p>
          <UiActionButton
            :disabled="disabled || busy"
            @click="
              filesOpen = false;
              open = true;
            "
            >{{ text('import-data', '导入数据') }}</UiActionButton
          >
        </section>
        <section v-if="canExport" class="exchange-section">
          <h3>{{ text('export-settings', '导出设置') }}</h3>
          <label class="exchange-field">
            <span>{{ text('export-range', '导出范围') }}</span>
            <UiSelect
              :value="exportRange"
              :options="rangeOptions"
              :allow-clear="false"
              :disabled="busy"
              :aria-label="text('export-range', '导出范围')"
              style="width: 100%"
              @update:value="exportRange = selectedString($event)"
            />
          </label>
          <p class="exchange-hint">
            {{
              exportRange === 'query'
                ? text('query-range-hint', '包含当前查询条件下的全部结果，沿用服务端排序；不是仅导出当前页。')
                : text('selected-range-hint', '只导出当前选中的记录及允许访问的关联明细。')
            }}
          </p>
          <details class="exchange-details">
            <summary>{{ text('file-content-options', '文件内容与语言') }}</summary>
            <div class="exchange-settings">
              <label class="exchange-field">
                <span>{{ text('file-purpose', '导出用途') }}</span>
                <UiSelect
                  :value="exportPurpose"
                  :options="purposeOptions"
                  :allow-clear="false"
                  :disabled="busy"
                  :aria-label="text('file-purpose', '导出用途')"
                  style="width: 100%"
                  @update:value="exportPurpose = selectedString($event) as ExchangePurpose"
                />
              </label>
              <label class="exchange-field">
                <span>{{ text('file-language', '文件名称解析与导出语言') }}</span>
                <UiSelect
                  :value="fileLanguage"
                  :options="languageOptions"
                  :allow-clear="false"
                  :disabled="busy"
                  :aria-label="text('file-language', '文件名称解析与导出语言')"
                  style="width: 100%"
                  @update:value="fileLanguage = selectedString($event)"
                />
              </label>
            </div>
          </details>
          <p class="exchange-file-summary">
            {{ operationLabel(exportPurpose) }} · {{ languageLabel(fileLanguage) }}
          </p>
          <p class="exchange-hint">
            {{
              exportPurpose === 'DATA'
                ? text('data-purpose-hint', '保留业务原文供填写或修改；所选语言用于文件标题及引用名称。')
                : exportPurpose === 'READING'
                  ? text('reading-purpose-hint', '按所选语言阅读，保留原文；此文件不能回导。')
                  : text(
                      'translation-purpose-hint',
                      '只修订人工译文草稿，不修改业务原值，也不自动确认译文质量。',
                    )
            }}
          </p>
        </section>
        <p v-if="busy" role="status">{{ busyMessage }}</p>
        <p v-if="fileError" role="alert">{{ fileError }}</p>
      </div>
      <template #operation
        ><div class="exchange-operation">
          <UiActionButton :disabled="busy" @click="filesOpen = false">{{
            text('close', '关闭')
          }}</UiActionButton>
          <UiActionButton
            v-if="canExport"
            emphasis="primary"
            :loading="busy && activity === 'export'"
            :disabled="disabled || busy || (exportRange === 'selected' && !selectedIds?.length)"
            @click="exportData(exportRange === 'selected')"
            >{{ text('export-file', '导出文件') }}</UiActionButton
          >
        </div></template
      >
    </UiModal>
    <UiModal
      :open="open"
      scroll-content
      :title="text('bulk-data-import', '批量导入数据')"
      width="900px"
      :closable="!busy"
      @cancel="!busy && (open = false)"
    >
      <ol class="exchange-steps" :aria-label="text('import-progress', '导入进度')">
        <li :aria-current="!preview && !result ? 'step' : undefined" :class="{ active: !preview && !result }">
          1 {{ text('upload-stage', '上传文件') }}
        </li>
        <li :aria-current="preview && !result ? 'step' : undefined" :class="{ active: preview && !result }">
          2 {{ text('review-stage', '核对与确认') }}
        </li>
        <li :aria-current="result ? 'step' : undefined" :class="{ active: result }">
          3 {{ text('result-stage', '导入结果') }}
        </li>
      </ol>
      <p v-if="busy" role="status">{{ busyMessage }}</p>
      <div class="exchange-upload">
        <RecordPanelButton v-if="!result" :disabled="busy || disabled" @click="template">{{
          text('download-import-template', '下载导入模板')
        }}</RecordPanelButton>
        <input
          ref="uploadInput"
          :key="`exchange-upload:${inputKey}`"
          type="file"
          hidden
          accept=".xlsx"
          :disabled="busy || disabled"
          @change="upload"
        />
        <RecordPanelButton :disabled="busy || disabled" @click="uploadInput?.click()">{{
          result?.errorCount
            ? text('upload-correction-file', '上传修正文件')
            : text('upload-import-file', '上传导入文件')
        }}</RecordPanelButton>
        <span v-if="file" class="exchange-filename">{{ file.name }}</span>
      </div>
      <p v-if="!result" class="exchange-hint">
        {{ text('blank-contract', '更新已有数据时，空白或缺列不修改；0 和 false 会正常写入。') }}
      </p>
      <details v-if="!result" class="exchange-details">
        <summary>{{ text('fill-instructions', '填写说明') }}</summary>
        <p>
          {{
            text(
              'file-contract',
              '业务文件填写原文；译文文件只维护人工草稿。更新时空白或缺列不修改，清空请在“明确清空字段”列填写字段名。0 和 false 会正常写入。',
            )
          }}
        </p>
      </details>
      <template v-if="preview && !result">
        <p class="exchange-file-summary">
          {{ operationLabel(preview.purpose) }} · {{ languageLabel(preview.language) }}
        </p>
        <details v-if="preview.purpose === 'DATA'" class="exchange-details">
          <summary>{{ text('import-language-options', '名称解析与本次填写语言') }}</summary>
          <div class="exchange-settings">
            <label class="exchange-field"
              ><span>{{ text('matching-language', '本次名称解析语言') }}</span>
              <UiSelect
                :value="resolutionLanguage"
                :options="[
                  { value: '', label: text('use-file-matching-language', '沿用文件声明的语言') },
                  ...languageOptions,
                ]"
                :allow-clear="false"
                :disabled="busy"
                :aria-label="text('matching-language', '本次名称解析语言')"
                style="width: 100%"
                @update:value="resolutionLanguage = selectedString($event)"
              />
            </label>
            <label class="exchange-field"
              ><span>{{ text('source-language', '本次业务修改的填写语言') }}</span>
              <UiSelect
                :value="sourceLanguage"
                :options="[
                  { value: '', label: text('use-file-language', '沿用文件中的字段语言') },
                  ...languageOptions,
                ]"
                :allow-clear="false"
                :disabled="busy"
                :aria-label="text('source-language', '本次业务修改的填写语言')"
                style="width: 100%"
                @update:value="sourceLanguage = selectedString($event)"
              />
            </label>
          </div>
          <p class="exchange-hint">
            {{
              text(
                'language-separation-hint',
                '名称解析语言用于选择关联对象；填写语言记录本次业务原文的来源，两者分别生效。',
              )
            }}
          </p>
        </details>
        <p v-if="preview.purpose === 'DATA'" class="exchange-hint">
          {{
            text(
              'identity-policy-hint',
              '已导出的业务记录按文件中的身份更新；手填新行首次匹配到已有记录时，按下方策略处理。已提交行重传会跳过。',
            )
          }}
        </p>
        <section v-for="sheet in preview.sheets" :key="sheet.entityAlias" class="import-sheet">
          <h3>
            {{ sheet.sheetName }} ·
            {{ sheet.main ? text('main-sheet', '主表') : text('child-sheet', '子表') }} ·
            {{ text('sheet-rows', '{count} 行', { count: sheet.rowCount }) }}
          </h3>
          <div v-if="preview.purpose === 'DATA' && needsMatching(sheet)" class="exchange-settings">
            <label class="exchange-field"
              ><span>{{ text('matching-field', '匹配字段') }}</span>
              <UiSelect
                :value="matchFields[sheet.entityAlias] || undefined"
                :placeholder="text('choose-match-field', '选择识别已有记录的字段')"
                :options="
                  sheet.matchFields.map((field) => ({
                    value: field,
                    label: fieldLabel(sheet.entityAlias, field),
                  }))
                "
                :allow-clear="false"
                :disabled="busy"
                :aria-label="`${sheet.sheetName} ${text('matching-field', '匹配字段')}`"
                style="width: 100%"
                @update:value="matchFields[sheet.entityAlias] = selectedString($event)"
              />
            </label>
            <label class="exchange-field"
              ><span>{{ text('existing-records', '已有记录') }}</span>
              <UiSelect
                :value="strategies[sheet.entityAlias]"
                :options="strategyOptions"
                :allow-clear="false"
                :disabled="busy"
                :aria-label="`${sheet.sheetName} ${text('existing-records', '已有记录')}`"
                style="width: 100%"
                @update:value="
                  strategies[sheet.entityAlias] = selectedString($event) as ImportDuplicateStrategy
                "
              />
            </label>
          </div>
          <p
            v-if="preview.purpose === 'DATA' && sheet.rowCount > 0 && !needsMatching(sheet)"
            class="exchange-hint"
          >
            {{ text('context-only-sheet', '仅用于关联已有业务，不重复写入，也无需选择匹配字段。') }}
          </p>
          <p v-if="sheet.recommendedMatchField && needsMatching(sheet)" class="exchange-hint">
            {{
              text('recommended-match', '已按对象声明推荐唯一匹配字段：{field}。', {
                field: fieldLabel(sheet.entityAlias, sheet.recommendedMatchField),
              })
            }}
          </p>
        </section>
        <p v-if="preview.rows.some((row) => row.choices.length)" class="exchange-hint">
          {{
            text(
              'binding-confirmation-hint',
              '机器译名或重名需要确认对应业务对象；此确认不表示译文质量已确认。选择后重新核对，再导入。',
            )
          }}
        </p>
        <p v-if="waitingChoices && hasIndependentReadyGroup" role="status">
          {{
            text(
              'pending-reference-units',
              '含待确认引用的业务单元不会写入；其他独立正确项可以先导入，失败文件保留待确认项。',
            )
          }}
        </p>
        <div class="exchange-preview-tools">
          <RecordPanelButton
            :selected="activeFilter === 'attention'"
            @click="
              rowFilter = 'attention';
              shownRows = 100;
            "
            >{{
              text('attention-rows', '待处理（{count}）', { count: attentionRows.length })
            }}</RecordPanelButton
          >
          <RecordPanelButton
            :selected="activeFilter === 'all'"
            @click="
              rowFilter = 'all';
              shownRows = 100;
            "
            >{{ text('all-rows', '全部（{count}）', { count: preview.rows.length }) }}</RecordPanelButton
          >
          <span role="status">{{
            reviewed
              ? text('reviewed-file', '已核对，可导入正确项；失败项将单独提供修正文件。')
              : text('review-required', '请核对文件；修改选项或对象后需要重新核对。')
          }}</span>
        </div>
        <div class="exchange-preview">
          <p v-if="!visibleRows.length" class="exchange-hint">
            {{ text('no-attention-rows', '当前没有待处理项，可切换查看全部记录。') }}
          </p>
          <section
            v-for="row in visibleRows"
            :key="`${row.entity}:${row.row}`"
            class="exchange-row"
            :class="{ 'exchange-row--attention': row.operation === 'ERROR' || unresolved(row) }"
          >
            <div class="exchange-row-heading">
              <strong>{{ rowLabel(row) }}</strong
              ><span>{{ operationLabel(row.operation) }}</span>
            </div>
            <p class="exchange-hint">{{ text('relation-identity', '关联标识') }} {{ row.relation }}</p>
            <p v-if="row.problem" role="alert">{{ row.problem }}</p>
            <p v-if="row.currentValues.__original">
              {{ text('original-content', '原文') }}: {{ display(row.currentValues.__original) }}
            </p>
            <dl v-if="Object.keys(row.changes).length">
              <template v-for="(value, field) in row.changes" :key="field">
                <dt>
                  {{ fieldLabel(row.entity, field) }}
                  <span v-if="row.languages[field]" class="exchange-hint"
                    >({{ languageLabel(row.languages[field]?.languageTag) }})</span
                  >
                </dt>
                <dd>
                  <div v-if="Object.hasOwn(row.currentValues, field)">
                    {{ text('current-value', '当前内容') }}:
                    {{ referenceDisplay(row, field, 'current', row.currentValues[field]) }}
                  </div>
                  <div>
                    {{ text('submitted-value', '本次修改') }}:
                    {{ referenceDisplay(row, field, 'submitted', value) }}
                  </div>
                  <details v-if="row.references?.[field]" class="exchange-hint">
                    <summary>{{ text('reference-identity', '关联身份') }}</summary>
                    <div v-if="Object.hasOwn(row.currentValues, field)">
                      {{ text('current-value', '当前内容') }}: {{ display(row.currentValues[field]) }}
                    </div>
                    <div>{{ text('submitted-value', '本次修改') }}: {{ display(value) }}</div>
                  </details>
                </dd>
              </template>
            </dl>
            <label v-for="choice in row.choices" :key="choice.field" class="exchange-field">
              <span>{{ fieldLabel(row.entity, choice.field) }}: {{ choice.input }}</span>
              <UiSelect
                :value="confirmed[key(row.row, choice.field)] || undefined"
                :options="
                  choice.candidates.map((candidate) => ({
                    value: candidate.id,
                    label: `${candidate.title} · ${candidate.subtitle || candidate.id} (${operationLabel(candidate.kind)})`,
                  }))
                "
                :placeholder="text('choose-object', '请选择对应业务对象')"
                :allow-clear="true"
                :disabled="busy"
                :aria-label="`${row.relation} ${choice.field} ${choice.input}`"
                style="width: 100%"
                @update:value="confirmed[key(row.row, choice.field)] = selectedString($event)"
              />
            </label>
          </section>
          <RecordPanelButton v-if="filteredRows.length > shownRows" @click="shownRows += 100">{{
            text('more-rows', '显示更多记录')
          }}</RecordPanelButton>
        </div>
      </template>
      <p v-if="error" role="alert">{{ error }}</p>
      <div v-if="result" class="exchange-result" role="status">
        <h3>
          {{
            result.partialSuccess
              ? text('partially-completed', '部分导入成功')
              : result.errorCount
                ? text('not-imported', '仍有待修正项')
                : text('import-completed', '导入完成')
          }}
        </h3>
        <p>
          {{
            text('import-counts', '', {
              created: result.created,
              updated: result.updated,
              skipped: result.skipped,
              errors: result.errorCount,
            })
          }}
        </p>
        <UiActionButton
          v-if="result.errorWorkbookBase64"
          emphasis="primary"
          :disabled="busy"
          @click="errors"
          >{{ text('download-error-file', '下载错误文件') }}</UiActionButton
        >
        <p v-if="result.errorCount">
          {{
            text(
              'failed-file-hint',
              '错误文件只包含待修正项及必要的只读关联上下文。直接修改并重新上传，无需再准备成功项。',
            )
          }}
        </p>
      </div>
      <template #operation
        ><div class="exchange-operation">
          <UiActionButton :disabled="busy" @click="open = false">{{
            result ? text('done', '完成') : text('close', '关闭')
          }}</UiActionButton>
          <UiActionButton v-if="preview && !result" :disabled="busy || !ready" @click="review">{{
            text('review-file', '核对文件与本次修改')
          }}</UiActionButton>
          <UiActionButton
            v-if="!result"
            emphasis="primary"
            :loading="busy && activity === 'import'"
            :disabled="busy || !ready || !reviewed || (waitingChoices && !hasIndependentReadyGroup)"
            @click="execute"
            >{{ text('confirm-import', '确认导入') }}</UiActionButton
          >
        </div></template
      >
    </UiModal>
  </div>
</template>

<style scoped>
.module-data-exchange {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.module-data-exchange--managed {
  display: contents;
}
.exchange-operation {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}
.exchange-file-options {
  display: grid;
  gap: 16px;
}
.exchange-section + .exchange-section {
  border-block-start: 1px solid var(--muyun-border-subtle);
  padding-block-start: 16px;
}
h3 {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
}
p {
  margin-block: 8px;
}
.exchange-hint {
  color: var(--muyun-text-muted);
  font-size: 12px;
}
.exchange-settings {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-block: 12px;
}
.exchange-field {
  display: grid;
  gap: 6px;
  min-width: 0;
}
.exchange-details {
  margin-block: 12px;
}
.exchange-details summary {
  cursor: pointer;
  color: var(--muyun-text-muted);
}
.exchange-details summary:focus-visible {
  outline: 2px solid var(--muyun-primary);
  outline-offset: 3px;
}
.exchange-file-summary {
  font-weight: 500;
}
.exchange-steps {
  display: flex;
  gap: 16px;
  list-style: none;
  padding: 0 0 12px;
  margin: 0 0 16px;
  border-block-end: 1px solid var(--muyun-border-subtle);
  color: var(--muyun-text-muted);
}
.exchange-steps .active {
  color: var(--muyun-text);
  font-weight: 600;
}
.exchange-upload,
.exchange-preview-tools {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
}
.exchange-filename {
  overflow-wrap: anywhere;
}
.exchange-preview-tools {
  margin-block: 12px;
}
.exchange-preview-tools > span {
  flex-basis: 100%;
  font-size: 12px;
}
.exchange-preview {
  min-width: 0;
}
.exchange-row {
  padding: 12px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: var(--muyun-radius-md, 6px);
  margin-block-end: 10px;
  overflow-wrap: anywhere;
}
.exchange-row--attention {
  border-inline-start: 3px solid var(--muyun-warning-text);
}
.exchange-row-heading {
  display: flex;
  gap: 8px;
  justify-content: space-between;
  flex-wrap: wrap;
}
.exchange-row dl {
  display: grid;
  grid-template-columns: minmax(100px, 1fr) minmax(0, 4fr);
  gap: 4px 12px;
}
.exchange-row dd {
  margin: 0;
  white-space: pre-wrap;
}
.exchange-result {
  padding-block: 16px;
}
@media (max-width: 600px) {
  .exchange-settings {
    grid-template-columns: minmax(0, 1fr);
  }
  .exchange-row dl {
    grid-template-columns: minmax(0, 1fr);
  }
  .exchange-steps {
    gap: 8px;
    flex-wrap: wrap;
  }
}
</style>
