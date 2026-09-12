<script setup lang="ts">
import { ref } from 'vue';
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
import { isValueLessQueryOperator } from './queryCriteriaDraft';
import type { RecordPickerRecord } from './recordPickerConstraints';

defineOptions({ name: 'QueryCriteriaComposer' });

const props = defineProps<{
  fields: QuerySchemaField[];
  optionItemsByField: Record<string, Option[]>;
  referenceContexts: Record<string, ModuleContext<RecordPickerRecord>>;
  disabled: boolean;
  composition: 'FLAT_AND' | 'TREE';
}>();

const emit = defineEmits<{
  apply: [criteria: QueryCriteriaGroup | undefined];
  clear: [];
  validation: [message: string];
  draftChange: [pending: boolean];
}>();

let sequence = 0;
const root = ref<QueryCriteriaGroupDraft>(createRoot());
const validationErrors = ref<Record<number, string>>({});

function apply() {
  const errors: Record<number, string> = {};
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

function clear() {
  root.value = createRoot();
  validationErrors.value = {};
  emit('draftChange', false);
  emit('clear');
}

function updateRoot(group: QueryCriteriaGroupDraft) {
  root.value = group;
  validationErrors.value = {};
  emit('draftChange', true);
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
  const field = props.fields[0];
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
      :fields="fields"
      :option-items-by-field="optionItemsByField"
      :reference-contexts="referenceContexts"
      :next-id="nextId"
      :disabled="disabled"
      :composition="composition"
      :validation-errors="validationErrors"
      @update:group="updateRoot"
      @submit="apply"
    />
    <footer class="query-criteria-composer-actions">
      <UiButton type="primary" :disabled="disabled" @click="apply">应用条件</UiButton>
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
