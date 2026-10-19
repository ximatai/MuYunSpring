<script setup lang="ts">
import { computed } from 'vue';
import { useLanguageContext } from '@muyun/web-core';
import { FormulaExpressionEditor, RecordContentSectionHeading } from '@muyun/platform-components';
import { UiButton, UiCheckbox, UiInput, UiSelect } from '@muyun/vue-ui-antdv';
import {
  codeSegmentTypes,
  codeSourceTitle,
  newCodeSegment,
  newCodeSequence,
  type CodeRule,
  type CodeSegment,
  type CodeSegmentType,
  type CodeSource,
} from './codeConfiguration';
const props = defineProps<{ sources: CodeSource[]; editable: boolean }>();
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
function segmentKey(segment: CodeSegment) {
  if (!segmentKeys.has(segment)) segmentKeys.set(segment, ++keySequence);
  return segmentKeys.get(segment)!;
}
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
function numeric(value: unknown) {
  return value === '' || value == null ? null : Number(value);
}
function syncPolicy() {
  if (!draft.value) return;
  if (draft.value.segments.some((segment) => segment.segmentType === 'SEQUENCE'))
    draft.value.sequencePolicy ??= newCodeSequence();
  else draft.value.sequencePolicy = null;
}
function changeSegment(index: number, type: CodeSegmentType) {
  if (!draft.value) return;
  draft.value.segments[index] = {
    ...newCodeSegment(type),
    id: draft.value.segments[index]?.id,
    version: draft.value.segments[index]?.version,
  };
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
    <ol class="code-format__segments">
      <li v-for="(segment, index) in draft.segments" :key="segmentKey(segment)">
        <div class="code-format__segment-heading">
          <UiSelect
            :aria-label="t('segment-type', '片段类型')"
            :value="segment.segmentType"
            :disabled="!editable"
            :options="segmentOptions"
            @update:value="changeSegment(index, $event as CodeSegmentType)"
          />
          <UiButton :disabled="!editable || index === 0" @click="moveSegment(index, -1)">{{
            t('up', '上移')
          }}</UiButton
          ><UiButton
            :disabled="!editable || index === draft.segments.length - 1"
            @click="moveSegment(index, 1)"
            >{{ t('down', '下移') }}</UiButton
          ><UiButton :disabled="!editable" @click="removeSegment(index)">{{ t('remove', '删除') }}</UiButton>
        </div>
        <p v-if="['FORMULA', 'CONTEXT_VAR'].includes(segment.segmentType)" class="code-format__help">
          {{ t('bounded-help', '为公式或外部上下文设置长度与截取方式，确保编码不超出目标字段长度。') }}
        </p>
        <div class="code-format__grid">
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
            }}<UiInput v-model:value="segment.sourceRef" :disabled="!editable"
          /></label>
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
        <details class="code-format__advanced" :open="hasAdvancedSettings(segment)">
          <summary>
            {{ t('advanced-format', '高级设置')
            }}<span v-if="hasAdvancedSettings(segment)"> · {{ t('configured', '已配置') }}</span>
          </summary>
          <div class="code-format__grid">
            <label v-if="!['FORMULA', 'CONTEXT_VAR'].includes(segment.segmentType)" class="code-format__field"
              >{{
                segment.segmentType === 'SEQUENCE'
                  ? t('sequence-length-override', '片段长度（覆盖流水长度）')
                  : t('length', '片段长度')
              }}<UiInput
                :value="segment.length ?? undefined"
                type="number"
                :disabled="!editable"
                @update:value="segment.length = numeric($event)"
            /></label>
            <label class="code-format__field"
              >{{ t('separator', '片段分隔符')
              }}<UiInput v-model:value="segment.separator" :disabled="!editable"
            /></label>
            <label class="code-format__field"
              >{{ t('padding', '补位')
              }}<UiSelect
                v-model:value="segment.padMode"
                :disabled="!editable"
                :options="enumOptions('padding', { NONE: '不补位', LEFT: '左侧补位', RIGHT: '右侧补位' })"
            /></label>
            <label class="code-format__field" v-if="segment.padMode !== 'NONE'"
              >{{ t('pad-char', '补位字符') }}<UiInput v-model:value="segment.padChar" :disabled="!editable"
            /></label>
            <label class="code-format__field"
              >{{ t('truncate', '超长截取')
              }}<UiSelect
                v-model:value="segment.truncatePolicy"
                :disabled="!editable"
                :options="enumOptions('truncate', { NONE: '保留原值', LEFT: '截去左侧', RIGHT: '截去右侧' })"
            /></label>
            <label class="code-format__field"
              >{{ t('null-policy', '来源为空时')
              }}<UiSelect
                v-model:value="segment.nullPolicy"
                :disabled="!editable"
                :options="
                  enumOptions('null', {
                    ERROR: '拒绝生成',
                    SKIP_SEGMENT: '跳过该片段',
                    USE_DEFAULT: '使用默认文字',
                  })
                "
            /></label>
            <label
              class="code-format__field"
              v-if="segment.nullPolicy === 'USE_DEFAULT' && segment.segmentType !== 'CONSTANT'"
              >{{ t('default-text', '默认文字')
              }}<UiInput v-model:value="segment.fixedValue" :disabled="!editable"
            /></label>
            <UiCheckbox
              v-if="segment.segmentType !== 'SEQUENCE'"
              class="code-format__basis"
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
    <UiButton :disabled="!editable" @click="addSegment">{{ t('add-segment', '增加片段') }}</UiButton>
    <div v-if="draft.sequencePolicy" class="code-format__grid">
      <h3 class="code-format__wide">{{ t('sequence', '流水策略') }}</h3>
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
        >{{ t('sequence-length', '流水长度')
        }}<UiInput
          :value="draft.sequencePolicy.sequenceLength ?? undefined"
          type="number"
          :disabled="!editable"
          @update:value="draft!.sequencePolicy!.sequenceLength = numeric($event)"
      /></label>
      <label class="code-format__field"
        >{{ t('maximum', '流水上限')
        }}<UiInput
          :value="draft.sequencePolicy.maxValue ?? undefined"
          type="number"
          :disabled="!editable"
          @update:value="draft!.sequencePolicy!.maxValue = numeric($event)"
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
      <label class="code-format__field"
        >{{ t('overflow', '达到上限后')
        }}<UiSelect
          v-model:value="draft.sequencePolicy.overflowPolicy"
          :disabled="!editable"
          :options="enumOptions('overflow', { ERROR: '拒绝生成', RESET: '从起点重新尝试' })"
      /></label>
    </div>
  </section>
</template>
<style scoped>
.code-format__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin: 14px 0;
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
.code-format h3 {
  font-size: 14px;
  margin: 8px 0;
}
.code-format__segments {
  display: grid;
  gap: 12px;
  padding-left: 20px;
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
.code-format__segment-heading > :first-child {
  flex: 1;
  min-width: 140px;
}
.code-format__advanced summary {
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
}
@media (max-width: 760px) {
  .code-format__mapping-row {
    grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  }
  .code-format__mapping-default {
    grid-column: 1/3;
  }
  .code-format__mapping-remove {
    justify-self: end;
  }
  .code-format__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
