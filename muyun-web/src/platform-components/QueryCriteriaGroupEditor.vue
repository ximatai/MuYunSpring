<script setup lang="ts">
import { UiButton, UiSelect } from '@muyun/vue-ui-antdv';
import type { Option, OptionValue, OptionValueList, QuerySchemaField } from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';
import QueryValueEditor from './QueryValueEditor.vue';
import type {
  QueryCriteriaConditionDraft,
  QueryCriteriaDraftNode,
  QueryCriteriaGroupDraft,
} from './queryCriteriaDraft';
import type { RecordPickerRecord } from './recordPickerConstraints';

defineOptions({ name: 'QueryCriteriaGroupEditor' });

const props = defineProps<{
  group: QueryCriteriaGroupDraft;
  fields: QuerySchemaField[];
  optionItemsByField: Record<string, Option[]>;
  referenceContexts: Record<string, ModuleContext<RecordPickerRecord>>;
  nextId: () => number;
  disabled: boolean;
  /** Flat query surfaces keep the root as a plain AND list. */
  composition?: 'FLAT_AND' | 'TREE';
  validationErrors?: Record<number, string>;
  nested?: boolean;
}>();

const emit = defineEmits<{
  'update:group': [group: QueryCriteriaGroupDraft];
  remove: [id: number];
  submit: [];
}>();

const groupOperatorOptions: Option[] = [
  { label: '同时满足（AND）', value: 'AND' },
  { label: '满足任一（OR）', value: 'OR' },
];

const fieldOptions = () =>
  props.fields.map((field) => ({
    label: field.title ?? field.name,
    value: field.name,
  }));

function updateGroupOperator(value: OptionValue | OptionValueList | null) {
  const operator = singleValue(value);
  if (operator !== 'AND' && operator !== 'OR') return;
  updateGroup((group) => {
    group.operator = operator;
  });
}

function updateField(node: QueryCriteriaConditionDraft, value: OptionValue | OptionValueList | null) {
  const field = fieldByName(singleValue(value));
  updateCondition(node.id, (condition) => {
    condition.fieldName = field?.name;
    condition.operator = field?.defaultOperator ?? field?.operators[0];
    condition.values = [];
  });
}

function updateOperator(node: QueryCriteriaConditionDraft, value: OptionValue | OptionValueList | null) {
  updateCondition(node.id, (condition) => {
    condition.operator = singleValue(value) as QueryCriteriaConditionDraft['operator'];
    condition.values = [];
  });
}

function updateValues(node: QueryCriteriaConditionDraft, values: unknown[]) {
  updateCondition(node.id, (condition) => {
    condition.values = values;
  });
}

function addCondition() {
  updateGroup((group) => {
    group.children.push({
      kind: 'CONDITION',
      id: props.nextId(),
      fieldName: props.fields[0]?.name,
      operator: props.fields[0]?.defaultOperator ?? props.fields[0]?.operators[0],
      values: [],
    });
  });
}

function addGroup() {
  if (props.composition !== 'TREE') return;
  updateGroup((group) => {
    group.children.push({ kind: 'GROUP', id: props.nextId(), operator: 'AND', children: [] });
  });
}

function removeChild(id: number) {
  updateGroup((group) => {
    group.children = group.children.filter((child) => child.id !== id);
  });
}

function updateChild(updated: QueryCriteriaGroupDraft) {
  updateGroup((group) => {
    group.children = group.children.map((child) => (child.id === updated.id ? updated : child));
  });
}

function updateCondition(id: number, updater: (condition: QueryCriteriaConditionDraft) => void) {
  updateGroup((group) => {
    const condition = group.children.find(
      (child): child is QueryCriteriaConditionDraft => child.id === id && child.kind === 'CONDITION',
    );
    if (condition) updater(condition);
  });
}

function updateGroup(updater: (group: QueryCriteriaGroupDraft) => void) {
  const next = cloneGroup(props.group);
  updater(next);
  emit('update:group', next);
}

function cloneGroup(group: QueryCriteriaGroupDraft): QueryCriteriaGroupDraft {
  return {
    ...group,
    children: group.children.map((child) =>
      child.kind === 'GROUP' ? cloneGroup(child) : { ...child, values: [...child.values] },
    ),
  };
}

function fieldByName(name: string | undefined) {
  return props.fields.find((field) => field.name === name);
}

function operatorOptions(node: QueryCriteriaConditionDraft): Option[] {
  return (fieldByName(node.fieldName)?.operators ?? []).map((operator) => ({
    label: operatorLabel(operator),
    value: operator,
  }));
}

function referenceContext(field: QuerySchemaField) {
  const target = field.reference?.targetModuleAlias;
  return target ? props.referenceContexts[target] : undefined;
}

function nodeIsCondition(node: QueryCriteriaDraftNode): node is QueryCriteriaConditionDraft {
  return node.kind === 'CONDITION';
}

function singleValue(value: OptionValue | OptionValueList | null) {
  return Array.isArray(value) || value === null ? undefined : String(value);
}

function operatorLabel(operator: string) {
  const labels: Record<string, string> = {
    EQ: '等于',
    NOT_EQUAL: '不等于',
    LIKE: '包含',
    IN: '属于',
    NOT_IN: '不属于',
    GT: '大于',
    GTE: '大于等于',
    LT: '小于',
    LTE: '小于等于',
    BETWEEN: '介于',
    NULL: '为空',
    NOT_NULL: '不为空',
    CONTAINS: '包含元素',
    CONTAINS_ANY: '包含任一元素',
    CONTAINS_ALL: '包含全部元素',
    EMPTY: '为空集合',
    NOT_EMPTY: '非空集合',
  };
  return labels[operator] ?? operator;
}
</script>

<template>
  <section class="query-criteria-group" :class="{ 'is-nested': nested }">
    <header class="query-criteria-group-header">
      <strong v-if="nested">括号组</strong>
      <span v-else>组合关系</span>
      <UiSelect
        class="query-criteria-group-operator"
        :value="group.operator"
        :options="groupOperatorOptions"
        :disabled="disabled"
        @update:value="updateGroupOperator"
      />
      <UiButton
        v-if="nested"
        type="text"
        icon-name="delete"
        danger
        :disabled="disabled"
        @click="emit('remove', group.id)"
      />
      <span v-if="validationErrors?.[group.id]" class="query-criteria-group-error" role="alert">
        {{ validationErrors[group.id] }}
      </span>
    </header>
    <div class="query-criteria-group-children">
      <template v-for="node in group.children" :key="node.id">
        <div v-if="nodeIsCondition(node)" class="query-criteria-condition-row">
          <UiSelect
            class="query-criteria-condition-field"
            :value="node.fieldName"
            :options="fieldOptions()"
            :disabled="disabled"
            placeholder="字段"
            @update:value="updateField(node, $event)"
          />
          <UiSelect
            class="query-criteria-condition-operator"
            :value="node.operator"
            :options="operatorOptions(node)"
            :disabled="disabled"
            placeholder="关系"
            @update:value="updateOperator(node, $event)"
          />
          <QueryValueEditor
            v-if="fieldByName(node.fieldName) && node.operator"
            class="query-criteria-condition-value"
            :field="fieldByName(node.fieldName)!"
            :operator="node.operator"
            :values="node.values"
            :options="optionItemsByField[node.fieldName!] ?? []"
            :reference-context="referenceContext(fieldByName(node.fieldName)!)"
            :disabled="disabled"
            @submit="emit('submit')"
            @update:values="updateValues(node, $event)"
          />
          <UiButton
            type="text"
            icon-name="delete"
            danger
            :disabled="disabled"
            @click="removeChild(node.id)"
          />
          <span v-if="validationErrors?.[node.id]" class="query-criteria-condition-error" role="alert">
            {{ validationErrors[node.id] }}
          </span>
        </div>
        <QueryCriteriaGroupEditor
          v-else
          :group="node"
          :fields="fields"
          :option-items-by-field="optionItemsByField"
          :reference-contexts="referenceContexts"
          :next-id="nextId"
          :disabled="disabled"
          :composition="composition"
          :validation-errors="validationErrors"
          nested
          @update:group="updateChild"
          @remove="removeChild"
          @submit="emit('submit')"
        />
      </template>
    </div>
    <footer class="query-criteria-group-actions">
      <UiButton type="dashed" icon-name="plus" :disabled="disabled" @click="addCondition">添加条件</UiButton>
      <UiButton
        v-if="composition === 'TREE'"
        type="text"
        icon-name="plus"
        :disabled="disabled"
        @click="addGroup"
      >
        添加括号组
      </UiButton>
    </footer>
  </section>
</template>

<style scoped>
.query-criteria-group {
  display: grid;
  gap: 8px;
}

.query-criteria-group.is-nested {
  padding: 8px;
  border: 1px dashed var(--muyun-border);
  border-radius: 6px;
  background: var(--muyun-support-surface);
}

.query-criteria-group-header,
.query-criteria-group-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.query-criteria-group-header {
  color: var(--muyun-text-muted);
  font-size: 13px;
}

.query-criteria-group-error {
  color: var(--muyun-danger);
}

.query-criteria-group-operator {
  width: 156px;
}

.query-criteria-group-children {
  display: grid;
  gap: 8px;
}

.query-criteria-condition-row {
  display: grid;
  grid-template-columns: minmax(140px, 0.8fr) minmax(120px, 0.6fr) minmax(180px, 1fr) 32px;
  gap: 8px;
  align-items: center;
  min-width: 0;
}

.query-criteria-condition-error {
  grid-column: 1 / -1;
  color: var(--muyun-danger);
  font-size: 13px;
}

.query-criteria-condition-field,
.query-criteria-condition-operator,
.query-criteria-condition-value {
  min-width: 0;
}
</style>
