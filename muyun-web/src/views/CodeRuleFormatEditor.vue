<script setup lang="ts">
import { computed, watch, toRaw } from 'vue';
import { useLanguageContext } from '@muyun/web-core';
import { FormulaExpressionEditor, RecordContentSectionHeading } from '@muyun/platform-components';
import { UiButton, UiCheckbox, UiInput, UiSelect, UiHelp } from '@muyun/vue-ui-antdv';
import {
  codeSegmentTypes,
  codeSourceTitle,
  newCodeSegment,
  newCodeSequence,
  type CodeRule,
  type CodeSegment,
  type CodeSegmentType,
  type CodeSource,
  type CodeTrigger,
} from './codeConfiguration';
const props = defineProps<{ sources: CodeSource[]; editable: boolean; trigger?: CodeTrigger }>();
const draft = defineModel<CodeRule>('rule', { required: true });
const language = useLanguageContext();
const t = (key: string, fallback: string) => language.t(`platform.code.config.${key}`, fallback);
const sourceOptions = computed(() =>
  props.sources.map((source) => ({ value: source.fieldName, label: codeSourceTitle(source, language) })),
);
const formulaFields = computed(() =>
  props.sources.map((source) => ({
    name: source.fieldName,
    label: codeSourceTitle(source, language),
    valueType: source.type,
  })),
);
const segmentNames: Record<CodeSegmentType, string> = {
  CONSTANT: '固定文字',
  FIELD_VALUE: '业务字段',
  CONTEXT_VAR: '动作上下文',
  SYSTEM_TIME: '业务时间',
  SEQUENCE: '流水号',
  VALUE_MAPPING: '值映射',
  FORMULA: '公式',
};
const segmentOptions = computed(() =>
  codeSegmentTypes.map((value) => ({
    value,
    label: t(`segment.${value}`, segmentNames[value]),
  })),
);
const enumOptions = (scope: string, entries: Record<string, string>) =>
  Object.entries(entries).map(([value, label]) => ({ value, label: t(`enum.${scope}.${value}`, label) }));
const dateFormats = [
  'YY',
  'YYYY',
  'YYMM',
  'YYYYMM',
  'YYMMDD',
  'YYYYMMDD',
  'YYYY_MM',
  'YYYY_MM_DD',
  'YYYY_MM_DD_HH_MM_SS',
  'HHMMSS',
];
const segmentKeys = new WeakMap<CodeSegment, number>();
let keySequence = 0;
const segmentDrafts = new WeakMap<CodeSegment, Map<CodeSegmentType, CodeSegment>>();
let inactiveSequencePolicy: CodeRule['sequencePolicy'] = null;
watch(
  () => draft.value,
  () => {
    inactiveSequencePolicy = null;
  },
);
function segmentKey(segment: CodeSegment) {
  segment = toRaw(segment);
  if (!segmentKeys.has(segment)) segmentKeys.set(segment, ++keySequence);
  return segmentKeys.get(segment)!;
}
function hasNullableSource(segment: CodeSegment) {
  return ['FIELD_VALUE', 'CONTEXT_VAR', 'VALUE_MAPPING', 'FORMULA'].includes(segment.segmentType);
}
function canGroupSequence(segment: CodeSegment) {
  return !!draft.value.sequencePolicy && !['CONSTANT', 'SEQUENCE'].includes(segment.segmentType);
}
const inheritedSequenceLength = computed(() =>
  language.t('platform.code.config.inherited-sequence-length', '继承流水长度：{length}', {
    length: draft.value.sequencePolicy?.sequenceLength ?? '—',
  }),
);
const sequenceLimit = computed(() => {
  const length = draft.value.sequencePolicy?.sequenceLength;
  if (length == null || !Number.isInteger(length) || length < 1 || length > 18) return '';
  return language.t('platform.code.config.sequence-limit-hint', '按 {length} 位流水，最大 {maximum}', {
    length,
    maximum: '9'.repeat(length),
  });
});
function hasAdvancedSettings(segment: CodeSegment) {
  return (
    segment.length != null ||
    !!segment.separator ||
    (segment.padMode ?? 'NONE') !== 'NONE' ||
    (segment.truncatePolicy ?? 'NONE') !== 'NONE' ||
    (segment.nullPolicy ?? 'ERROR') !== 'ERROR' ||
    !!segment.sequenceBasis
  );
}
function advancedSummary(segment: CodeSegment) {
  if (segment.segmentType === 'SEQUENCE' && segment.length != null)
    return language.t('platform.code.config.sequence-length-override-summary', '长度覆盖 {length} 位', {
      length: segment.length,
    });
  return t('configured', '已配置');
}
const sequenceSummary = computed(() => {
  const policy = draft.value.sequencePolicy;
  if (!policy) return '';
  const parts = [
    policy.startValue != null && policy.startValue !== 1
      ? `${t('start', '起始值')} ${policy.startValue}`
      : '',
    policy.stepValue != null && policy.stepValue !== 1 ? `${t('step', '递增步长')} ${policy.stepValue}` : '',
    policy.maxValue != null ? `${t('maximum', '流水上限')} ${policy.maxValue}` : '',
    policy.overflowPolicy === 'RESET' ? t('enum.overflow.RESET', '从起点重新尝试') : '',
  ].filter(Boolean);
  return parts.length ? parts.join(' · ') : t('defaults', '默认设置');
});
function numeric(value: unknown) {
  return value === '' || value == null ? null : Number(value);
}
function syncPolicy() {
  if (!draft.value) return;
  if (draft.value.segments.some((segment) => segment.segmentType === 'SEQUENCE'))
    draft.value.sequencePolicy ??= inactiveSequencePolicy ?? newCodeSequence();
  else {
    inactiveSequencePolicy = draft.value.sequencePolicy ?? inactiveSequencePolicy;
    draft.value.sequencePolicy = null;
  }
}
function changeSegment(index: number, type: CodeSegmentType) {
  if (!draft.value) return;
  const current = draft.value.segments[index]!;
  if (current.segmentType === type) return;
  const history = segmentDrafts.get(toRaw(current)) ?? new Map<CodeSegmentType, CodeSegment>();
  history.set(current.segmentType, JSON.parse(JSON.stringify(current)) as CodeSegment);
  const replacement = {
    ...(history.get(type) ?? newCodeSegment(type)),
    id: current.id,
    version: current.version,
  };
  segmentKeys.set(replacement, segmentKey(current));
  segmentDrafts.set(replacement, history);
  draft.value.segments[index] = replacement;
  syncPolicy();
}
function addSegment() {
  if (draft.value) {
    draft.value.segments.push(newCodeSegment());
    syncPolicy();
  }
}
function moveSegment(index: number, delta: number) {
  const segments = draft.value?.segments;
  if (!segments || index + delta < 0 || index + delta >= segments.length) return;
  const [segment] = segments.splice(index, 1);
  if (segment) segments.splice(index + delta, 0, segment);
}
function removeSegment(index: number) {
  draft.value?.segments.splice(index, 1);
  syncPolicy();
}
</script>
<template>
  <section class="code-format">
    <RecordContentSectionHeading :title="t('format', '编码格式')" />
    <ol class="code-format__segments" role="list">
      <li v-for="(segment, index) in draft.segments" :key="segmentKey(segment)">
        <div class="code-format__segment-heading">
          <span class="code-format__segment-number">{{ index + 1 }}</span>
          <UiSelect
            :aria-label="t('segment-type', '片段类型')"
            :value="segment.segmentType"
            :disabled="!editable"
            :options="segmentOptions"
            @update:value="changeSegment(index, $event as CodeSegmentType)"
          />
          <UiHelp
            v-if="['FORMULA', 'CONTEXT_VAR'].includes(segment.segmentType)"
            :label="t('bounded-help-title', '片段长度说明')"
          >
            {{ t('bounded-help', '为公式或外部上下文设置长度与截取方式，确保编码不超出目标字段长度。') }}
          </UiHelp>
          <div class="code-format__segment-actions">
            <UiButton
              type="text"
              icon-name="up"
              icon-only
              size="small"
              :title="t('up', '上移')"
              :aria-label="t('up', '上移')"
              :disabled="!editable || index === 0"
              @click="moveSegment(index, -1)"
            />
            <UiButton
              type="text"
              icon-name="down"
              icon-only
              size="small"
              :title="t('down', '下移')"
              :aria-label="t('down', '下移')"
              :disabled="!editable || index === draft.segments.length - 1"
              @click="moveSegment(index, 1)"
            />
            <UiButton
              type="text"
              icon-name="delete"
              icon-only
              size="small"
              :title="t('remove', '删除')"
              :aria-label="t('remove', '删除')"
              :disabled="!editable"
              @click="removeSegment(index)"
            />
          </div>
        </div>
        <div v-if="segment.segmentType !== 'SEQUENCE'" class="code-format__grid">
          <label class="code-format__field" v-if="segment.segmentType === 'CONSTANT'"
            >{{ t('constant', '固定文字') }}<UiInput v-model:value="segment.fixedValue" :disabled="!editable"
          /></label>
          <label
            class="code-format__field"
            v-if="['FIELD_VALUE', 'VALUE_MAPPING'].includes(segment.segmentType)"
            >{{ t('source', '来源字段')
            }}<UiSelect v-model:value="segment.sourceRef" :disabled="!editable" :options="sourceOptions"
          /></label>
          <label class="code-format__field" v-if="segment.segmentType === 'CONTEXT_VAR'"
            >{{ t('context', '动作传入的上下文名称')
            }}<UiSelect
              v-if="(props.trigger ?? draft.fieldTrigger ?? 'SAVE') !== 'EXPLICIT'"
              v-model:value="segment.sourceRef"
              :disabled="!editable"
              :options="sourceOptions"
            />
            <UiInput v-else v-model:value="segment.sourceRef" :disabled="!editable" />
          </label>
          <label class="code-format__field" v-if="segment.segmentType === 'SYSTEM_TIME'"
            >{{ t('date-format', '时间格式')
            }}<UiSelect
              v-model:value="segment.dateFormat"
              :disabled="!editable"
              :options="dateFormats.map((value) => ({ value, label: value }))"
          /></label>
          <label class="code-format__field code-format__wide" v-if="segment.segmentType === 'FORMULA'"
            >{{ t('formula', '返回编码片段的公式')
            }}<FormulaExpressionEditor
              :value="segment.formulaExpr ?? ''"
              @update:value="segment.formulaExpr = $event"
              :disabled="!editable"
              :fields="formulaFields"
          /></label>
          <label v-if="['FORMULA', 'CONTEXT_VAR'].includes(segment.segmentType)" class="code-format__field">
            {{ t('length', '片段长度') }}
            <UiInput
              :value="segment.length ?? undefined"
              type="number"
              :disabled="!editable"
              @update:value="segment.length = numeric($event)"
            />
          </label>
        </div>
        <details class="code-format__advanced">
          <summary>
            {{ t('advanced-format', '高级设置')
            }}<span v-if="hasAdvancedSettings(segment)"> · {{ advancedSummary(segment) }}</span>
          </summary>
          <div class="code-format__grid">
            <p
              v-if="segment.segmentType !== 'SEQUENCE' && !segment.length"
              class="code-format__help code-format__wide"
            >
              {{ t('length-dependent-hint', '设置片段长度后，补位与超长截取才会生效。') }}
            </p>
            <label v-if="!['FORMULA', 'CONTEXT_VAR'].includes(segment.segmentType)" class="code-format__field"
              >{{
                segment.segmentType === 'SEQUENCE'
                  ? t('sequence-length-override', '片段长度（覆盖流水长度）')
                  : t('length', '片段长度')
              }}<UiInput
                :value="segment.length ?? undefined"
                :placeholder="segment.segmentType === 'SEQUENCE' ? inheritedSequenceLength : undefined"
                type="number"
                :disabled="!editable"
                @update:value="segment.length = numeric($event)"
            /></label>
            <label
              v-if="segment.segmentType !== 'SEQUENCE' || (segment.padMode ?? 'NONE') !== 'NONE'"
              class="code-format__field"
              >{{ t('padding', '补位')
              }}<UiSelect
                v-model:value="segment.padMode"
                :disabled="!editable"
                :options="enumOptions('padding', { NONE: '不补位', LEFT: '左侧补位', RIGHT: '右侧补位' })"
            /></label>
            <label class="code-format__field" v-if="(segment.padMode ?? 'NONE') !== 'NONE'"
              >{{ t('pad-char', '补位字符') }}<UiInput v-model:value="segment.padChar" :disabled="!editable"
            /></label>
            <label
              v-if="segment.segmentType !== 'SEQUENCE' || (segment.truncatePolicy ?? 'NONE') !== 'NONE'"
              class="code-format__field"
              >{{ t('truncate', '超长截取')
              }}<UiSelect
                v-model:value="segment.truncatePolicy"
                :disabled="!editable"
                :options="enumOptions('truncate', { NONE: '保留原值', LEFT: '截去左侧', RIGHT: '截去右侧' })"
            /></label>
            <label class="code-format__field"
              >{{ t('separator', '片段后分隔符')
              }}<UiInput v-model:value="segment.separator" :disabled="!editable"
            /></label>
            <label
              v-if="hasNullableSource(segment) || (segment.nullPolicy ?? 'ERROR') !== 'ERROR'"
              class="code-format__field"
              >{{ t('null-policy', '来源为空时')
              }}<UiSelect
                v-model:value="segment.nullPolicy"
                :disabled="!editable"
                :options="
                  enumOptions('null', {
                    ERROR: '拒绝生成',
                    SKIP_SEGMENT: '跳过该片段',
                    USE_DEFAULT: '使用默认文字',
                  }).map((option) =>
                    segment.segmentType === 'VALUE_MAPPING' && option.value === 'USE_DEFAULT'
                      ? { ...option, label: t('use-default-source', '使用来源默认值') }
                      : option,
                  )
                "
            /></label>
            <label
              class="code-format__field"
              v-if="segment.nullPolicy === 'USE_DEFAULT' && segment.segmentType !== 'CONSTANT'"
              >{{
                segment.segmentType === 'VALUE_MAPPING'
                  ? t('mapping-fallback', '来源默认值')
                  : t('default-text', '默认文字')
              }}<UiInput v-model:value="segment.fixedValue" :disabled="!editable" />
              <span v-if="segment.segmentType === 'VALUE_MAPPING'" class="code-format__help">{{
                t('mapping-fallback-hint', '默认值仍需匹配下方映射。')
              }}</span>
            </label>
            <UiCheckbox
              v-if="canGroupSequence(segment) || segment.sequenceBasis"
              class="code-format__basis code-format__wide"
              v-model:checked="segment.sequenceBasis"
              :disabled="!editable"
              >{{ t('basis', '按该片段分别计数') }}</UiCheckbox
            >
          </div>
        </details>
        <div v-if="segment.segmentType === 'VALUE_MAPPING'" class="code-format__mapping">
          <div
            v-for="(mapping, mappingIndex) in segment.mappings"
            :key="mappingIndex"
            class="code-format__mapping-row"
          >
            <UiInput
              v-model:value="mapping.sourceValue"
              :placeholder="t('mapping-source', '原值')"
              :aria-label="t('mapping-source', '原值')"
              :disabled="!editable || mapping.defaultMapping"
            /><span>→</span
            ><UiInput
              v-model:value="mapping.targetValue"
              :placeholder="t('mapping-target', '编码文字')"
              :aria-label="t('mapping-target', '编码文字')"
              :disabled="!editable"
            /><UiCheckbox
              class="code-format__mapping-default"
              v-model:checked="mapping.defaultMapping"
              :disabled="!editable"
              >{{ t('mapping-default', '其他值') }}</UiCheckbox
            ><UiButton
              class="code-format__mapping-remove"
              :disabled="!editable"
              @click="segment.mappings.splice(mappingIndex, 1)"
              >{{ t('remove', '删除') }}</UiButton
            >
          </div>
          <UiButton
            :disabled="!editable"
            @click="
              segment.mappings.push({
                sourceValue: '',
                targetValue: '',
                defaultMapping: false,
                enabled: true,
              })
            "
            >{{ t('add-mapping', '增加映射') }}</UiButton
          >
        </div>
      </li>
    </ol>
    <UiButton
      type="dashed"
      icon-name="plus"
      :aria-label="t('add-segment', '增加片段')"
      :disabled="!editable"
      @click="addSegment"
      >{{ t('add-segment', '增加片段') }}</UiButton
    >
    <div v-if="draft.sequencePolicy" class="code-format__grid">
      <RecordContentSectionHeading class="code-format__wide" :title="t('sequence', '流水策略')" />
      <label class="code-format__field"
        >{{ t('sequence-length', '流水长度')
        }}<UiInput
          :value="draft.sequencePolicy.sequenceLength ?? undefined"
          type="number"
          :disabled="!editable"
          @update:value="draft!.sequencePolicy!.sequenceLength = numeric($event)"
      /></label>
      <label class="code-format__field"
        >{{ t('reset', '计数周期')
        }}<UiSelect
          v-model:value="draft.sequencePolicy.resetPolicy"
          :disabled="!editable"
          :options="
            enumOptions('reset', { NONE: '持续计数', DAY: '每天重置', MONTH: '每月重置', YEAR: '每年重置' })
          "
      /></label>
      <details class="code-format__sequence-advanced code-format__wide">
        <summary>
          {{ t('sequence-advanced', '流水高级设置') }}<span> · {{ sequenceSummary }}</span>
        </summary>
        <div class="code-format__grid">
          <label class="code-format__field"
            >{{ t('start', '起始值')
            }}<UiInput
              :value="draft.sequencePolicy.startValue ?? undefined"
              type="number"
              :disabled="!editable"
              @update:value="draft!.sequencePolicy!.startValue = numeric($event)"
          /></label>
          <label class="code-format__field"
            >{{ t('step', '递增步长')
            }}<UiInput
              :value="draft.sequencePolicy.stepValue ?? undefined"
              type="number"
              :disabled="!editable"
              @update:value="draft!.sequencePolicy!.stepValue = numeric($event)"
          /></label>
          <label class="code-format__field"
            >{{ t('maximum', '流水上限')
            }}<UiInput
              :value="draft.sequencePolicy.maxValue ?? undefined"
              type="number"
              :disabled="!editable"
              @update:value="draft!.sequencePolicy!.maxValue = numeric($event)"
            />
            <span v-if="sequenceLimit" class="code-format__help">{{ sequenceLimit }}</span>
          </label>
          <label class="code-format__field"
            >{{ t('overflow', '达到上限后')
            }}<UiSelect
              v-model:value="draft.sequencePolicy.overflowPolicy"
              :disabled="!editable"
              :options="enumOptions('overflow', { ERROR: '拒绝生成', RESET: '从起点重新尝试' })"
          /></label>
        </div>
      </details>
    </div>
  </section>
</template>
<style scoped>
.code-format {
  container: code-rule-format / inline-size;
}
.code-format__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin: 10px 0;
}
.code-format__field {
  grid-template-columns: minmax(0, 1fr);
  line-height: 20px;
  display: grid;
  gap: 6px;
  font-size: 13px;
  min-width: 0;
}
.code-format__basis {
  align-self: center;
  align-items: center;
}
.code-format__wide {
  grid-column: 1 / -1;
}
.code-format__segments {
  display: grid;
  gap: 12px;
  padding: 0;
  margin: 12px 0;
  list-style: none;
}
.code-format__segments > li > .code-format__grid > .code-format__field:only-child {
  grid-column: 1 / -1;
}
.code-format__segments > li {
  padding: 12px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 8px;
}
.code-format__segment-heading {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.code-format__segment-heading :deep(.ui-select-pass-through > .ant-select) {
  flex: 1;
  min-width: 120px;
}
.code-format__segment-number {
  display: inline-grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--muyun-hover-subtle);
  color: var(--muyun-text-muted);
  font-size: 12px;
}
.code-format__segment-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-inline-start: auto;
}
.code-format__advanced summary,
.code-format__sequence-advanced summary {
  cursor: pointer;
  color: var(--muyun-text-secondary);
  font-size: 13px;
}
.code-format__mapping {
  display: grid;
  gap: 8px;
}
.code-format__mapping-row {
  display: grid;
  grid-template-columns: minmax(80px, 1fr) auto minmax(80px, 1fr) auto auto;
  align-items: center;
  gap: 8px;
}
.code-format__mapping-default,
.code-format__mapping-remove {
  white-space: nowrap;
}
.code-format__help {
  color: var(--muyun-text-secondary);
  font-size: 12px;
  margin: 0;
}
@container code-rule-format (max-width: 520px) {
  .code-format__mapping-row {
    grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  }
  .code-format__mapping-default {
    grid-column: 1/3;
  }
  .code-format__mapping-remove {
    justify-self: end;
  }
}
@container code-rule-format (max-width: 380px) {
  .code-format__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
