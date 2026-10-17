<script setup lang="ts">
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
import { RecordPanelButton, UiModal, type RecordQueryListQueryController } from '@muyun/platform-components';

const language = useLanguageContext();
const readingLanguage = businessContentLanguageContext(language);
defineOptions({ name: 'ModuleDataExchangeSurface' });
const props = defineProps<{
  context: ModuleContext<unknown>;
  queryController?: RecordQueryListQueryController;
  selectedIds?: string[];
  scopeKey: string;
  disabled?: boolean;
}>();
const emit = defineEmits<{ busy: [value: boolean]; changed: [] }>();
const supported = computed(() => props.context.runtime.snapshot()?.capabilities.includes('EXCHANGE'));
const canImport = computed(() => supported.value && props.context.can('import') === true);
const canExport = computed(() => supported.value && props.context.can('export') === true);
const open = ref(false);
const filesOpen = ref(false);
const busy = ref(false);
const error = ref('');
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
  matchFields.value = {};
  strategies.value = {};
  confirmed.value = {};
  sourceLanguage.value = '';
  resolutionLanguage.value = '';
  reviewed.value = false;
  shownRows.value = 100;
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
            .filter((sheet) => sheet.main || sheet.rowCount > 0)
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
  work: (client: ReturnType<typeof createDataExchangeClient>, current: () => boolean) => Promise<void>,
) {
  if (busy.value || props.disabled) return;
  const ownRevision = ++revision;
  busy.value = true;
  emit('busy', true);
  error.value = '';
  try {
    await work(
      createDataExchangeClient(props.context.http, props.context.moduleAlias),
      () => ownRevision === revision,
    );
  } catch (cause) {
    if (ownRevision === revision) error.value = normalizeError(cause).message;
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
  await run(async (client, current) => {
    const parsed = await client.preview(selected, command());
    if (!current()) return;
    preview.value = parsed;
    for (const sheet of parsed.sheets) {
      matchFields.value[sheet.entityAlias] = sheet.matchFields[0] ?? '';
      strategies.value[sheet.entityAlias] = 'ERROR';
    }
  });
}
const ready = computed(
  () =>
    canImport.value &&
    file.value &&
    preview.value &&
    !result.value &&
    (preview.value.purpose === 'TRANSLATIONS' ||
      preview.value.sheets
        .filter((sheet) => sheet.main || sheet.rowCount > 0)
        .every((sheet) => matchFields.value[sheet.entityAlias])),
);
async function review() {
  if (!ready.value || !file.value) return;
  reviewed.value = false;
  const selected = file.value;
  const intent = command();
  await run(async (client, current) => {
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
  await run(async (client, current) => {
    const receipt = await client.importFile(selected, intent);
    if (!current()) return;
    result.value = receipt;
    if (receipt.created + receipt.updated > 0) emit('changed');
  });
}
async function template() {
  if (!canImport.value) return;
  await run(async (client, current) => {
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
    error.value = language.t(
      'platform.screen.ModuleDataExchangeSurface.wait-for-the-current-list-query-to',
      '请等待当前列表查询成功后再导出。',
    );
    return;
  }
  const purpose = exportPurpose.value;
  const target = fileLanguage.value;
  await run(async (client, current) => {
    const blob = await client.exportFile(
      selected ? undefined : query?.request,
      { purpose, language: target },
      ids,
      selected ? undefined : { contentLanguage: query?.contentLanguage },
    );
    if (current()) save(blob, `${props.context.moduleAlias}-${purpose.toLowerCase()}-${target}.xlsx`);
  });
}
const visibleRows = computed(() => preview.value?.rows.slice(0, shownRows.value) ?? []);
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
  <div v-if="canImport || canExport" class="module-data-exchange">
    <RecordPanelButton :disabled="disabled || busy" @click="filesOpen = true">
      {{ language.t('platform.screen.ModuleDataExchangeSurface.data-files', '业务文件') }}
    </RecordPanelButton>
    <UiModal
      :open="filesOpen"
      :title="language.t('platform.screen.ModuleDataExchangeSurface.data-files', '业务文件')"
      :confirm-text="language.t('platform.screen.ModuleDataExchangeSurface.done', '完成')"
      :confirm-disabled="busy"
      :closable="!busy"
      @cancel="filesOpen = false"
      @confirm="filesOpen = false"
    >
      <div class="exchange-file-options">
        <RecordPanelButton
          v-if="canImport"
          :disabled="disabled || busy"
          @click="
            clear();
            filesOpen = false;
            open = true;
          "
        >
          {{ language.t('platform.screen.ModuleDataExchangeSurface.import-data', '导入数据') }}
        </RecordPanelButton>
        <label>
          {{
            language.t('platform.screen.ModuleDataExchangeSurface.file-language', '文件名称解析与导出语言')
          }}
          <select
            v-model="fileLanguage"
            :disabled="busy"
            :aria-label="
              language.t('platform.screen.ModuleDataExchangeSurface.file-language', '文件名称解析与导出语言')
            "
          >
            <option v-for="item in businessContentLanguages" :key="item.value" :value="item.value">
              {{ item.label }}
            </option>
          </select>
        </label>
        <template v-if="canExport">
          <select
            v-model="exportPurpose"
            :disabled="busy"
            :aria-label="language.t('platform.screen.ModuleDataExchangeSurface.file-purpose', '导出用途')"
          >
            <option value="DATA">
              {{
                language.t('platform.screen.ModuleDataExchangeSurface.business-edit-file', '业务填写与修改')
              }}
            </option>
            <option value="TRANSLATIONS">
              {{
                language.t(
                  'platform.screen.ModuleDataExchangeSurface.translation-file',
                  '人工译文修订（保存草稿）',
                )
              }}
            </option>
            <option value="READING">
              {{
                language.t('platform.screen.ModuleDataExchangeSurface.reading-file', '阅读导出（不可回导）')
              }}
            </option>
          </select>
          <RecordPanelButton v-if="queryController" :disabled="disabled || busy" @click="exportData(false)">
            {{ language.t('platform.screen.ModuleDataExchangeSurface.export-current-query', '导出当前查询') }}
          </RecordPanelButton>
          <RecordPanelButton
            v-if="selectedIds?.length"
            :disabled="disabled || busy"
            @click="exportData(true)"
          >
            {{ language.t('platform.screen.ModuleDataExchangeSurface.export-selected', '导出所选记录') }}
          </RecordPanelButton>
        </template>
        <span v-if="error && !open" role="alert">{{ error }}</span>
      </div>
    </UiModal>
    <UiModal
      :open="open"
      :title="language.t('platform.screen.ModuleDataExchangeSurface.bulk-data-import', '批量导入数据')"
      width="900px"
      :confirm-text="
        result
          ? language.t('platform.screen.ModuleDataExchangeSurface.done', '完成')
          : language.t('platform.screen.ModuleDataExchangeSurface.confirm-import', '确认导入')
      "
      :confirm-loading="busy"
      :confirm-disabled="
        busy || (!result && (!ready || !reviewed || (waitingChoices && !hasIndependentReadyGroup)))
      "
      :closable="!busy"
      @cancel="open = false"
      @confirm="result ? (open = false) : execute()"
    >
      <p>
        {{
          language.t(
            'platform.screen.ModuleDataExchangeSurface.file-contract',
            '业务文件填写原文；译文文件只维护人工草稿。更新时空白或缺列不修改，清空请在“明确清空字段”列填写字段名。0 和 false 会正常写入。',
          )
        }}
      </p>
      <RecordPanelButton :disabled="busy || disabled" @click="template">{{
        language.t('platform.screen.ModuleDataExchangeSurface.download-import-template', '下载导入模板')
      }}</RecordPanelButton>
      <input
        ref="uploadInput"
        :key="inputKey"
        type="file"
        hidden
        accept=".xlsx"
        :disabled="busy || disabled"
        @change="upload"
      />
      <RecordPanelButton :disabled="busy || disabled" @click="uploadInput?.click()">{{
        language.t('platform.screen.ModuleDataExchangeSurface.upload-import-file', '上传导入文件')
      }}</RecordPanelButton>
      <span v-if="file"> {{ file.name }}</span>
      <template v-if="preview && !result">
        <p>{{ operationLabel(preview.purpose) }} · {{ preview.language ?? 'Original' }}</p>
        <label v-if="preview.purpose === 'DATA'">
          {{ language.t('platform.screen.ModuleDataExchangeSurface.matching-language', '本次名称解析语言') }}
          <select
            v-model="resolutionLanguage"
            :disabled="busy"
            :aria-label="
              language.t('platform.screen.ModuleDataExchangeSurface.matching-language', '本次名称解析语言')
            "
          >
            <option value="">
              {{
                language.t(
                  'platform.screen.ModuleDataExchangeSurface.use-file-matching-language',
                  '沿用文件声明的语言',
                )
              }}
            </option>
            <option v-for="item in businessContentLanguages" :key="item.value" :value="item.value">
              {{ item.label }}
            </option>
          </select>
        </label>
        <label v-if="preview.purpose === 'DATA'">
          {{
            language.t('platform.screen.ModuleDataExchangeSurface.source-language', '本次业务修改的填写语言')
          }}
          <select
            v-model="sourceLanguage"
            :disabled="busy"
            :aria-label="
              language.t(
                'platform.screen.ModuleDataExchangeSurface.source-language',
                '本次业务修改的填写语言',
              )
            "
          >
            <option value="">
              {{
                language.t(
                  'platform.screen.ModuleDataExchangeSurface.use-file-language',
                  '沿用文件中的字段语言',
                )
              }}
            </option>
            <option v-for="item in businessContentLanguages" :key="item.value" :value="item.value">
              {{ item.label }}
            </option>
          </select>
        </label>
        <section v-for="sheet in preview.sheets" :key="sheet.entityAlias" class="import-sheet">
          <strong>{{ sheet.sheetName }} ({{ sheet.rowCount }})</strong>
          <template v-if="preview.purpose === 'DATA'">
            <label
              >{{ language.t('platform.screen.ModuleDataExchangeSurface.matching-field', '匹配字段') }}
              <select
                v-model="matchFields[sheet.entityAlias]"
                :disabled="busy"
                :aria-label="`${sheet.sheetName} matching field`"
              >
                <option v-for="field in sheet.matchFields" :key="field" :value="field">
                  {{ fieldLabel(sheet.entityAlias, field) }}
                </option>
              </select>
            </label>
            <label
              >{{ language.t('platform.screen.ModuleDataExchangeSurface.existing-records', '已有记录') }}
              <select
                v-model="strategies[sheet.entityAlias]"
                :disabled="busy"
                :aria-label="`${sheet.sheetName} existing records`"
              >
                <option value="ERROR">
                  {{
                    language.t(
                      'platform.screen.ModuleDataExchangeSurface.report-an-error-and-retain-existing-data',
                      '报错，保留已有数据',
                    )
                  }}
                </option>
                <option value="SKIP">
                  {{
                    language.t(
                      'platform.screen.ModuleDataExchangeSurface.skip-existing-records',
                      '跳过已有记录',
                    )
                  }}
                </option>
                <option value="OVERWRITE">
                  {{
                    language.t(
                      'platform.screen.ModuleDataExchangeSurface.apply-submitted-changes',
                      '应用本次填写的修改',
                    )
                  }}
                </option>
              </select>
            </label>
          </template>
        </section>
        <p>
          {{
            language.t(
              'platform.screen.ModuleDataExchangeSurface.binding-confirmation-hint',
              '机器译名或重名需要确认对应业务对象；此确认不表示译文质量已确认。选择后重新核对，再导入。',
            )
          }}
        </p>
        <p v-if="waitingChoices && hasIndependentReadyGroup" role="status">
          {{
            language.t(
              'platform.screen.ModuleDataExchangeSurface.pending-reference-units',
              '含待确认引用的业务单元不会写入；其他独立正确项可以先导入，失败文件保留待确认项。',
            )
          }}
        </p>
        <RecordPanelButton :disabled="busy || !ready" @click="review">{{
          language.t('platform.screen.ModuleDataExchangeSurface.review-file', '核对文件与本次修改')
        }}</RecordPanelButton>
        <p v-if="reviewed" role="status">
          {{
            language.t(
              'platform.screen.ModuleDataExchangeSurface.reviewed-file',
              '已核对，可导入正确项；失败项将单独提供修正文件。',
            )
          }}
        </p>
        <div class="exchange-preview">
          <section v-for="row in visibleRows" :key="`${row.entity}:${row.row}`" class="exchange-row">
            <strong>{{ row.relation }} · {{ operationLabel(row.operation) }}</strong>
            <p v-if="row.problem" role="alert">{{ row.problem }}</p>
            <p v-if="row.currentValues.__original">
              {{ language.t('platform.screen.ModuleDataExchangeSurface.original-content', '原文') }}:
              {{ display(row.currentValues.__original) }}
            </p>
            <dl v-if="Object.keys(row.changes).length">
              <template v-for="(value, field) in row.changes" :key="field">
                <dt>
                  {{ fieldLabel(row.entity, field) }}
                  <span v-if="row.languages[field]">({{ row.languages[field]?.languageTag ?? '?' }})</span>
                </dt>
                <dd>
                  <div v-if="Object.hasOwn(row.currentValues, field)">
                    {{ language.t('platform.screen.ModuleDataExchangeSurface.current-value', '当前内容') }}:
                    {{ display(row.currentValues[field]) }}
                  </div>
                  <div>
                    {{ language.t('platform.screen.ModuleDataExchangeSurface.submitted-value', '本次修改') }}:
                    {{ display(value) }}
                  </div>
                </dd>
              </template>
            </dl>
            <label v-for="choice in row.choices" :key="choice.field">
              {{ fieldLabel(row.entity, choice.field) }}: {{ choice.input }}
              <select
                v-model="confirmed[key(row.row, choice.field)]"
                :disabled="busy"
                :aria-label="`${row.relation} ${choice.field} ${choice.input}`"
              >
                <option value="">
                  {{
                    language.t(
                      'platform.screen.ModuleDataExchangeSurface.choose-object',
                      '请选择对应业务对象',
                    )
                  }}
                </option>
                <option v-for="candidate in choice.candidates" :key="candidate.id" :value="candidate.id">
                  {{ candidate.title }} · {{ candidate.id }} ({{ operationLabel(candidate.kind) }})
                </option>
              </select>
            </label>
          </section>
          <RecordPanelButton v-if="(preview.rows.length ?? 0) > shownRows" @click="shownRows += 100">{{
            language.t('platform.screen.ModuleDataExchangeSurface.more-rows', '显示更多记录')
          }}</RecordPanelButton>
        </div>
      </template>
      <p v-if="error" role="alert">{{ error }}</p>
      <div v-if="result" role="status">
        <p>{{ language.t('platform.screen.ModuleDataExchangeSurface.import-completed', '导入完成') }}</p>
        <p>
          {{
            language.t('platform.screen.ModuleDataExchangeSurface.import-counts', '', {
              created: result.created,
              updated: result.updated,
              skipped: result.skipped,
              errors: result.errorCount,
            })
          }}
        </p>
        <RecordPanelButton v-if="result.errorWorkbookBase64" :disabled="busy" @click="errors">{{
          language.t('platform.screen.ModuleDataExchangeSurface.download-error-file', '下载错误文件')
        }}</RecordPanelButton>
        <p v-if="result.errorCount">
          {{
            language.t(
              'platform.screen.ModuleDataExchangeSurface.failed-file-hint',
              '错误文件只包含待修正项及必要的只读关联上下文。直接修改并重新上传，无需再准备成功项。',
            )
          }}
        </p>
      </div>
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
.exchange-file-options {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.exchange-file-options label {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.exchange-file-options select {
  max-width: 100%;
}
.import-sheet {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-block: 8px;
}
.exchange-preview {
  max-height: 420px;
  overflow: auto;
  margin-top: 12px;
}
.exchange-row {
  padding: 8px;
  border-block-end: 1px solid var(--muyun-border-subtle);
  overflow-wrap: anywhere;
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
.exchange-row select {
  max-width: 100%;
}
</style>
