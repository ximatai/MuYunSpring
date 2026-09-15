<script setup lang="ts">
import { computed, ref } from 'vue';
import { UiButton } from '@muyun/vue-ui-antdv';
import type {
  Option,
  QueryCriteriaCondition,
  QueryCriteriaGroup,
  QueryCriteriaNode,
  QuerySchemaField,
} from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';
import QueryCriteriaGroupEditor from './QueryCriteriaGroupEditor.vue';
import type {
  QueryCriteriaConditionDraft,
  QueryCriteriaDraftNode,
  QueryCriteriaGroupDraft,
} from './queryCriteriaDraft';
import {
  isValueLessQueryOperator,
  QUERY_CRITERIA_MAXIMUM_COLLECTION_VALUES,
  QUERY_CRITERIA_MAXIMUM_DEPTH,
  QUERY_CRITERIA_MAXIMUM_NODES,
} from './queryCriteriaDraft';
import type { ReferencePickerConfig, ReferencePickerValidity } from './referencePickerModel';
import type { RecordPickerRecord } from './recordPickerConstraints';

defineOptions({ name: 'QueryCriteriaComposer' });

const props = defineProps<{
  fields: QuerySchemaField[];
  optionItemsByField: Record<string, Option[]>;
  referenceContexts: Record<string, ModuleContext<RecordPickerRecord>>;
  referencePickerOf?: (field: QuerySchemaField) => ReferencePickerConfig | undefined;
  disabled: boolean;
  composition: 'FLAT_AND' | 'TREE';
  /** Fields already owned by a persistent control on a flat query surface. */
  excludedFieldNames?: string[];
}>();

const emit = defineEmits<{
  apply: [criteria: QueryCriteriaGroup | undefined];
  clear: [];
  validation: [message: string];
  draftChange: [pending: boolean];
  /** Query surfaces retain this by condition ID while the editor is hidden. */
  'validity-change': [validity: Record<number, ReferencePickerValidity>];
}>();

let sequence = 0;
const selectableFields = computed(() =>
  props.fields.filter((field) => !props.excludedFieldNames?.includes(field.name)),
);
const root = ref<QueryCriteriaGroupDraft>(createRoot());
const validationErrors = ref<Record<number, string>>({});
const referenceValidityByNode = ref<Record<number, ReferencePickerValidity>>({});
const referenceDraftValid = computed(() =>
  Object.values(referenceValidityByNode.value).every((item) => item.valid),
);

function apply() {
  const errors: Record<number, string> = {};
  for (const [id, validity] of Object.entries(referenceValidityByNode.value)) {
    if (!validity.valid) errors[Number(id)] = validity.message ?? '请完成引用选择';
  }
  if (Object.keys(errors).length > 0) {
    validationErrors.value = errors;
    emit('validation', '请完成引用选择后再应用');
    return;
  }
  validateDraftComplexity(root.value, 1, errors, { count: 0 });
  const result = resolveGroup(root.value, true, errors);
  if (Object.keys(errors).length > 0 || !result) {
    validationErrors.value = errors;
    emit('validation', '请修正标记的筛选条件后再应用');
    return;
  }
  validationErrors.value = {};
  emit('draftChange', false);
  emit('apply', result);
}

function validateDraftComplexity(
  group: QueryCriteriaGroupDraft,
  depth: number,
  errors: Record<number, string>,
  state: { count: number },
) {
  state.count += 1;
  if (depth > QUERY_CRITERIA_MAXIMUM_DEPTH) {
    errors[group.id] = `最多支持 ${QUERY_CRITERIA_MAXIMUM_DEPTH} 层括号组`;
  }
  if (state.count > QUERY_CRITERIA_MAXIMUM_NODES) {
    errors[group.id] = `最多支持 ${QUERY_CRITERIA_MAXIMUM_NODES} 个条件和括号组`;
  }
  for (const child of group.children) {
    state.count += 1;
    if (state.count > QUERY_CRITERIA_MAXIMUM_NODES) {
      errors[child.id] = `最多支持 ${QUERY_CRITERIA_MAXIMUM_NODES} 个条件和括号组`;
    }
    if (child.kind === 'GROUP') {
      // The recursive call accounts for the nested group itself, so undo the
      // child increment above before continuing through that group.
      state.count -= 1;
      validateDraftComplexity(child, depth + 1, errors, state);
    } else if (child.values.length > QUERY_CRITERIA_MAXIMUM_COLLECTION_VALUES) {
      errors[child.id] = `一个条件最多支持 ${QUERY_CRITERIA_MAXIMUM_COLLECTION_VALUES} 个值`;
    }
  }
}

function clear() {
  root.value = createRoot();
  validationErrors.value = {};
  updateReferenceValidity(undefined, undefined);
  emit('draftChange', false);
  emit('clear');
}

function updateRoot(group: QueryCriteriaGroupDraft) {
  root.value = group;
  pruneReferenceValidity(group);
  validationErrors.value = {};
  emit('draftChange', true);
}

function updateReferenceValidity(nodeId: number | undefined, validity: ReferencePickerValidity | undefined) {
  if (nodeId === undefined) {
    referenceValidityByNode.value = {};
  } else {
    const next = { ...referenceValidityByNode.value };
    if (validity) next[nodeId] = validity;
    else delete next[nodeId];
    referenceValidityByNode.value = next;
  }
  emit('validity-change', { ...referenceValidityByNode.value });
}

function pruneReferenceValidity(group: QueryCriteriaGroupDraft) {
  const currentNodeIds = new Set<number>();
  const collect = (node: QueryCriteriaDraftNode) => {
    if (node.kind === 'CONDITION') {
      currentNodeIds.add(node.id);
      return;
    }
    for (const child of node.children) collect(child);
  };
  collect(group);
  const next = Object.fromEntries(
    Object.entries(referenceValidityByNode.value).filter(([id]) => currentNodeIds.has(Number(id))),
  ) as Record<number, ReferencePickerValidity>;
  if (Object.keys(next).length === Object.keys(referenceValidityByNode.value).length) return;
  referenceValidityByNode.value = next;
  emit('validity-change', { ...next });
}

function createRoot(): QueryCriteriaGroupDraft {
  return {
    kind: 'GROUP',
    id: nextId(),
    operator: 'AND',
    children: [createCondition()],
  };
}

function createCondition(): QueryCriteriaConditionDraft {
  const field = selectableFields.value[0];
  return {
    kind: 'CONDITION',
    id: nextId(),
    fieldName: field?.name,
    operator: field?.defaultOperator ?? field?.operators[0],
    values: [],
  };
}

function nextId() {
  sequence += 1;
  return sequence;
}

function resolveGroup(
  group: QueryCriteriaGroupDraft,
  rootGroup: boolean,
  errors: Record<number, string>,
): QueryCriteriaGroup | undefined {
  if (props.composition === 'FLAT_AND' && (group.operator !== 'AND' || !rootGroup)) {
    errors[group.id] = '更多筛选只支持单层 AND 条件';
    return undefined;
  }
  const children: QueryCriteriaNode[] = [];
  for (const child of group.children) {
    const resolved = resolveNode(child, errors);
    if (resolved) children.push(resolved);
  }
  if (children.length === 0) {
    if (group.children.length === 0) {
      errors[group.id] = '请至少保留一个完整条件';
    }
    return undefined;
  }
  return { kind: 'GROUP', operator: group.operator, children };
}

function resolveNode(
  node: QueryCriteriaDraftNode,
  errors: Record<number, string>,
): QueryCriteriaNode | undefined {
  if (node.kind === 'GROUP') {
    if (props.composition === 'FLAT_AND') {
      errors[node.id] = '更多筛选不支持括号组';
      return undefined;
    }
    return resolveGroup(node, false, errors);
  }
  const field = props.fields.find((item) => item.name === node.fieldName);
  if (!field) {
    errors[node.id] = '请选择字段';
    return undefined;
  }
  const operator = node.operator;
  if (!operator) {
    errors[node.id] = '请选择关系';
    return undefined;
  }
  if (!field.operators.includes(operator)) {
    errors[node.id] = `${field.title ?? field.name} 不支持该关系`;
    return undefined;
  }
  if (operator === 'BETWEEN' && node.values.length !== 2) {
    errors[node.id] = `${field.title ?? field.name} 需要填写起始和结束两个值`;
    return undefined;
  }
  if (!isValueLessQueryOperator(operator) && node.values.length === 0) {
    errors[node.id] = `${field.title ?? field.name} 需要填写条件值`;
    return undefined;
  }
  const condition: QueryCriteriaCondition = {
    kind: 'CONDITION',
    fieldName: field.name,
    operator,
    values: node.values,
  };
  return condition;
}
</script>

<template>
  <section class="query-criteria-composer">
    <QueryCriteriaGroupEditor
      :group="root"
      :fields="selectableFields"
      :option-items-by-field="optionItemsByField"
      :reference-contexts="referenceContexts"
      :reference-picker-of="referencePickerOf"
      :reference-validity-by-node="referenceValidityByNode"
      :next-id="nextId"
      :disabled="disabled"
      :composition="composition"
      :depth="1"
      :validation-errors="validationErrors"
      @update:group="updateRoot"
      @validity-change="updateReferenceValidity"
      @submit="apply"
    />
    <footer class="query-criteria-composer-actions">
      <UiButton type="primary" :disabled="disabled || !referenceDraftValid" @click="apply">应用条件</UiButton>
      <UiButton type="text" :disabled="disabled" @click="clear">重置</UiButton>
    </footer>
  </section>
</template>

<style scoped>
.query-criteria-composer {
  display: grid;
  gap: 10px;
}

.query-criteria-composer-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
</style>
