<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue';
import {
  createDataExchangeClient,
  normalizeError,
  type ModuleContext,
  type ImportParseResult,
  type ImportExecutionResult,
  type ImportDuplicateStrategy,
} from '@muyun/web-core';
import { RecordPanelButton, UiModal, type RecordQueryListQueryController } from '@muyun/platform-components';

defineOptions({ name: 'ModuleDataExchangeSurface' });
const props = defineProps<{
  context: ModuleContext<unknown>;
  queryController?: RecordQueryListQueryController;
  scopeKey: string;
  disabled?: boolean;
}>();
const emit = defineEmits<{ busy: [value: boolean]; changed: [] }>();
const runtime = computed(() => props.context.runtime.snapshot());
const supported = computed(
  () => runtime.value?.moduleKind === 'dynamic' && runtime.value.capabilities.includes('EXCHANGE'),
);
const canImport = computed(() => supported.value && props.context.can('import') === true);
const canExport = computed(() => supported.value && props.context.can('export') === true);
const open = ref(false);
const busy = ref(false);
const error = ref('');
const file = shallowRef<File>();
const parsed = shallowRef<ImportParseResult>();
const result = shallowRef<ImportExecutionResult>();
const matchFields = ref<Record<string, string>>({});
const strategies = ref<Record<string, ImportDuplicateStrategy>>({});
let revision = 0;
const inputKey = ref(0);
watch(
  () => [props.context, props.scopeKey],
  () => {
    revision += 1;
    open.value = false;
    clear();
  },
);
function clear() {
  file.value = undefined;
  parsed.value = undefined;
  result.value = undefined;
  error.value = '';
  matchFields.value = {};
  strategies.value = {};
  inputKey.value += 1;
}
async function run(
  operation: (client: ReturnType<typeof createDataExchangeClient>, current: () => boolean) => Promise<void>,
) {
  if (busy.value || props.disabled) return;
  const ownRevision = ++revision;
  const context = props.context;
  busy.value = true;
  emit('busy', true);
  error.value = '';
  try {
    await operation(
      createDataExchangeClient(context.http, context.moduleAlias),
      () => ownRevision === revision,
    );
  } catch (cause) {
    if (ownRevision === revision) error.value = normalizeError(cause).message;
  } finally {
    busy.value = false;
    emit('busy', false);
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
    const preview = await client.parse(selected);
    if (!current()) return;
    parsed.value = preview;
    for (const sheet of preview.sheets) {
      matchFields.value[sheet.entityAlias] =
        sheet.fields.find((field) => field.matchKeyCandidate)?.fieldName ?? '';
      strategies.value[sheet.entityAlias] = 'ERROR';
    }
  });
}
const ready = computed(
  () =>
    canImport.value &&
    file.value &&
    parsed.value?.sheets.some((sheet) => sheet.main) &&
    parsed.value.sheets.every((sheet) => matchFields.value[sheet.entityAlias]),
);
async function execute() {
  if (!ready.value || result.value || !file.value || !parsed.value) return;
  const selected = file.value;
  const sheets = parsed.value.sheets;
  const main = sheets.find((sheet) => sheet.main)!;
  const command = {
    mainSheet: {
      matchFieldName: matchFields.value[main.entityAlias]!,
      duplicateStrategy: strategies.value[main.entityAlias]!,
    },
    childSheets: sheets
      .filter((sheet) => !sheet.main)
      .map((sheet) => ({
        entityAlias: sheet.entityAlias,
        matchFieldName: matchFields.value[sheet.entityAlias]!,
        duplicateStrategy: strategies.value[sheet.entityAlias]!,
      })),
  };
  await run(async (client, current) => {
    const receipt = await client.execute(selected, command);
    if (!current()) return;
    result.value = receipt;
    if (receipt.created + receipt.updated > 0) emit('changed');
  });
}
async function template() {
  if (!canImport.value) return;
  await run(async (client, current) => {
    const blob = await client.template();
    if (current()) save(blob, `${props.context.moduleAlias}-template.xlsx`);
  });
}
async function errors() {
  const token = result.value?.errorFileToken;
  if (!token || !canImport.value) return;
  await run(async (client, current) => {
    const blob = await client.errorFile(token);
    if (current()) save(blob, result.value?.errorFileName ?? 'import-errors.xlsx');
  });
}
async function exportData() {
  const query = props.queryController?.snapshot();
  if (!canExport.value || query?.status !== 'ready' || query.mode !== 'normal' || !query.request) {
    error.value = '请等待当前列表查询成功后再导出。';
    return;
  }
  await run(async (client, current) => {
    const blob = await client.exportData(query.request!);
    if (current()) save(blob, `${props.context.moduleAlias}-export.xlsx`);
  });
}
</script>

<template>
  <div v-if="canImport || canExport" class="module-data-exchange">
    <RecordPanelButton
      v-if="canImport"
      :disabled="disabled || busy"
      @click="
        clear();
        open = true;
      "
      >导入数据</RecordPanelButton
    >
    <RecordPanelButton v-if="canExport" :disabled="disabled || busy" @click="exportData"
      >导出当前查询</RecordPanelButton
    >
    <span v-if="error && !open" role="alert">{{ error }}</span>
    <UiModal
      :open="open"
      title="批量导入数据"
      width="680px"
      :confirm-text="result ? '完成' : '确认导入'"
      :confirm-loading="busy"
      :confirm-disabled="busy || (!result && !ready)"
      :closable="!busy"
      @cancel="open = false"
      @confirm="result ? (open = false) : execute()"
    >
      <p>先下载模板填写数据，再上传核对。解析文件不会写入数据，确认导入后才执行。</p>
      <RecordPanelButton :disabled="busy || disabled" @click="template">下载导入模板</RecordPanelButton>
      <p>
        <input
          :key="inputKey"
          aria-label="上传导入文件"
          type="file"
          accept=".xlsx"
          :disabled="busy || disabled"
          @change="upload"
        />
      </p>
      <section v-for="sheet in parsed?.sheets" :key="sheet.entityAlias" class="import-sheet">
        <strong>{{ sheet.sheetName }}（{{ sheet.main ? '主表' : '子表' }}，{{ sheet.rowCount }} 行）</strong>
        <label
          >匹配字段
          <select
            v-model="matchFields[sheet.entityAlias]"
            :aria-label="`${sheet.sheetName}匹配字段`"
            :disabled="busy || Boolean(result)"
          >
            <option
              v-for="field in sheet.fields.filter((item) => item.matchKeyCandidate)"
              :key="field.fieldName"
              :value="field.fieldName"
            >
              {{ field.title }}
            </option>
          </select></label
        >
        <label
          >已有记录
          <select
            v-model="strategies[sheet.entityAlias]"
            :aria-label="`${sheet.sheetName}重复处理`"
            :disabled="busy || Boolean(result)"
          >
            <option value="ERROR">报错，保留已有数据</option>
            <option value="SKIP">跳过已有记录</option>
            <option value="OVERWRITE">覆盖已有记录</option>
          </select></label
        >
      </section>
      <p v-if="parsed">
        覆盖会修改匹配到的已有记录，请核对匹配字段和文件内容。错误文件保留出错组的完整数据；重传时各表选择“跳过已有记录”可保留已成功数据，需修改已有记录时再选择“覆盖”。
      </p>
      <p v-if="error" role="alert">{{ error }}</p>
      <div v-if="result" role="status">
        <p>
          {{ result.message }}：新增 {{ result.created }}，更新 {{ result.updated }}，跳过
          {{ result.skipped }}，异常 {{ result.errorCount }} 项。
        </p>
        <p v-for="summary in result.summaries" :key="summary.entityAlias">
          {{
            parsed?.sheets.find((sheet) => sheet.entityAlias === summary.entityAlias)?.sheetName ??
            summary.entityAlias
          }}：新增 {{ summary.created }}，更新 {{ summary.updated }}，跳过 {{ summary.skipped }}，异常
          {{ summary.errors }} 项。
        </p>
        <RecordPanelButton v-if="result.errorFileToken" :disabled="busy" @click="errors"
          >下载错误文件</RecordPanelButton
        >
        <p>需要重试时，请修正文件并重新上传。</p>
      </div>
    </UiModal>
  </div>
</template>

<style scoped>
.module-data-exchange {
  display: flex;
  gap: 8px;
  align-items: center;
}
.import-sheet {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin: 16px 0;
}
.import-sheet label {
  display: flex;
  gap: 6px;
  align-items: center;
}
[role='alert'] {
  color: var(--muyun-danger-text);
}
</style>
