<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  DetailRelationListPanel,
  RecordDetailExtensionSection,
  RecordPanelButton,
  evaluateUiFormula,
  type QueryListRecord,
} from '@muyun/platform-components';
import type { ResolvedDetailRelationDescriptor, ResolvedModuleUiDescriptor } from '@muyun/web-contracts';
import { hasExecutableDetailRelationQueryContract } from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';
import ManagedDetailRelationSurface from './ManagedDetailRelationSurface.vue';
import ManagedDetailRelationInlineSurface from './ManagedDetailRelationInlineSurface.vue';

defineOptions({ name: 'ModulePageDetailRelations' });

const props = defineProps<{
  sourceContext: ModuleContext<QueryListRecord>;
  uiDescriptor: ResolvedModuleUiDescriptor;
  relations: ResolvedDetailRelationDescriptor[];
  parentRecord: QueryListRecord;
  mutationEnabled?: boolean;
  reloadKey?: number;
  validationRequestKey?: number;
}>();

const emit = defineEmits<{
  'validity-change': [valid: boolean];
  'children-change': [relationCode: string, records: QueryListRecord[]];
}>();

const relationValidity = ref<Record<string, boolean>>({});
const addRequestKeys = ref<Record<string, number>>({});
const removeRequestKeys = ref<Record<string, number>>({});
const selectedCounts = ref<Record<string, number>>({});
const removedCounts = ref<Record<string, number>>({});
const undoRemoveRequestKeys = ref<Record<string, number>>({});
const recycleBinRequestKeys = ref<Record<string, number>>({});
const recycleBinAvailability = ref<Record<string, boolean>>({});

function aggregateInlineEditing(relation: ResolvedDetailRelationDescriptor) {
  return Boolean(
    relation.embeddedField &&
    props.mutationEnabled &&
    relation.editing?.mode === 'INLINE' &&
    relation.editing.saveMode === 'AGGREGATE_DRAFT',
  );
}

function relationCreateAllowed(relation: ResolvedDetailRelationDescriptor) {
  return Boolean(relation.embeddedField && aggregateInlineEditing(relation));
}

function relationDeleteAllowed(relation: ResolvedDetailRelationDescriptor) {
  return Boolean(relation.embeddedField && aggregateInlineEditing(relation));
}

function requestAdd(relationCode: string) {
  addRequestKeys.value = {
    ...addRequestKeys.value,
    [relationCode]: (addRequestKeys.value[relationCode] ?? 0) + 1,
  };
}

function requestRemove(relationCode: string) {
  removeRequestKeys.value = {
    ...removeRequestKeys.value,
    [relationCode]: (removeRequestKeys.value[relationCode] ?? 0) + 1,
  };
}

function requestUndoRemove(relationCode: string) {
  undoRemoveRequestKeys.value = {
    ...undoRemoveRequestKeys.value,
    [relationCode]: (undoRemoveRequestKeys.value[relationCode] ?? 0) + 1,
  };
}

function relationRecycleBinAllowed(relation: ResolvedDetailRelationDescriptor) {
  return Boolean(
    aggregateInlineEditing(relation) &&
    relation.editing?.recycleBinEnabled &&
    props.parentRecord.id != null &&
    recycleBinAvailability.value[relation.code] === true,
  );
}

function requestRecycleBin(relationCode: string) {
  recycleBinRequestKeys.value = {
    ...recycleBinRequestKeys.value,
    [relationCode]: (recycleBinRequestKeys.value[relationCode] ?? 0) + 1,
  };
}

function updateRelationValidity(relationCode: string, valid: boolean) {
  relationValidity.value = { ...relationValidity.value, [relationCode]: valid };
  emit('validity-change', Object.values(relationValidity.value).every(Boolean));
}

watch([() => props.parentRecord.id, () => props.mutationEnabled], () => {
  relationValidity.value = {};
  recycleBinAvailability.value = {};
  emit('validity-change', true);
});

const visibleRelations = computed(() =>
  props.relations.filter((relation) => {
    const visible = relation.visible;
    if (visible?.constant === false) return false;
    if (visible?.formula && !evaluateUiFormula(visible.formula, props.parentRecord)) return false;
    if (relation.embeddedField) return true;
    if (props.parentRecord.id == null) return false;
    if (!hasExecutableDetailRelationQueryContract(relation)) return false;
    const actionCode = relation.queryContract.actionCode;
    if (
      relation.queryContract.managedGateway &&
      (!actionCode || props.sourceContext.can(actionCode) !== true)
    )
      return false;
    const constraint = relation.parentConstraint;
    return (
      constraint == null ||
      String(props.parentRecord[constraint.fieldName] ?? '') === constraint.expectedValue
    );
  }),
);

watch(
  () => visibleRelations.value.map((relation) => relation.code).join('|'),
  () => {
    const visible = new Set(visibleRelations.value.map((relation) => relation.code));
    const validity = Object.fromEntries(
      Object.entries(relationValidity.value).filter(([relationCode]) => visible.has(relationCode)),
    );
    relationValidity.value = validity;
    emit('validity-change', Object.values(validity).every(Boolean));
  },
);
</script>

<template>
  <RecordDetailExtensionSection
    v-for="relation in visibleRelations"
    :key="`relation:${relation.code}`"
    :title="relation.title ?? relation.code"
    kind="relation"
  >
    <template
      v-if="
        relationCreateAllowed(relation) ||
        relationDeleteAllowed(relation) ||
        relationRecycleBinAllowed(relation)
      "
      #actions
    >
      <span v-if="(removedCounts[relation.code] ?? 0) > 0" class="managed-relation-pending-removal">
        已移除 {{ removedCounts[relation.code] }} 项
        <RecordPanelButton type="link" @click="requestUndoRemove(relation.code)">撤销</RecordPanelButton>
      </span>
      <RecordPanelButton
        v-if="relationCreateAllowed(relation)"
        class="managed-relation-add-button"
        type="text"
        icon-name="plus"
        :title="`新增${relation.title ?? relation.code}`"
        :aria-label="`新增${relation.title ?? relation.code}`"
        @click="requestAdd(relation.code)"
      />
      <RecordPanelButton
        v-if="relationDeleteAllowed(relation) || relationCreateAllowed(relation)"
        class="managed-relation-add-button"
        type="text"
        danger
        icon-name="minus"
        :disabled="(selectedCounts[relation.code] ?? 0) === 0"
        :title="`移除选中的${relation.title ?? relation.code}`"
        :aria-label="`移除选中的${relation.title ?? relation.code}`"
        @click="requestRemove(relation.code)"
      />
      <RecordPanelButton
        v-if="relationRecycleBinAllowed(relation)"
        class="managed-relation-add-button"
        type="text"
        icon-name="delete"
        :title="`${relation.title ?? relation.code}回收站`"
        :aria-label="`${relation.title ?? relation.code}回收站`"
        @click="requestRecycleBin(relation.code)"
      />
    </template>
    <ManagedDetailRelationInlineSurface
      v-if="relation.embeddedField"
      :source-context="sourceContext"
      :ui-descriptor="uiDescriptor"
      :relation="relation"
      :parent-record="parentRecord"
      :reload-key="reloadKey"
      :add-request-key="addRequestKeys[relation.code] ?? 0"
      :remove-request-key="removeRequestKeys[relation.code] ?? 0"
      :undo-remove-request-key="undoRemoveRequestKeys[relation.code] ?? 0"
      :recycle-bin-request-key="recycleBinRequestKeys[relation.code] ?? 0"
      :validation-request-key="validationRequestKey"
      :mutation-enabled="aggregateInlineEditing(relation)"
      @records-change="emit('children-change', relation.embeddedField ?? relation.code, $event)"
      @validity-change="updateRelationValidity(relation.code, $event)"
      @selection-change="selectedCounts[relation.code] = $event"
      @removed-count-change="removedCounts[relation.code] = $event"
      @recycle-bin-availability-change="recycleBinAvailability[relation.code] = $event"
    />
    <ManagedDetailRelationSurface
      v-else-if="relation.queryContract?.managedGateway"
      :source-context="sourceContext"
      :ui-descriptor="uiDescriptor"
      :relation="relation"
      :parent-record="parentRecord"
      :mutation-enabled="mutationEnabled"
      :reload-key="reloadKey"
    />
    <DetailRelationListPanel
      v-else
      :source-context="sourceContext"
      :relation="relation"
      :record-id="parentRecord.id == null ? undefined : String(parentRecord.id)"
      :reload-key="reloadKey"
    />
  </RecordDetailExtensionSection>
</template>

<style scoped>
.managed-relation-pending-removal {
  align-items: center;
  color: var(--muyun-text-muted);
  display: inline-flex;
  font-size: 11px;
  gap: 2px;
  margin-right: 4px;
  white-space: nowrap;
}

.managed-relation-pending-removal :deep(.ant-btn) {
  font-size: 11px;
  height: 20px;
  padding: 0 2px;
}

.managed-relation-add-button :deep(.ant-btn),
:deep(.managed-relation-add-button.ant-btn) {
  align-items: center;
  border-radius: 50%;
  display: inline-flex;
  height: 24px;
  justify-content: center;
  min-width: 24px;
  padding: 0;
  width: 24px;
}

.managed-relation-add-button :deep(.anticon),
:deep(.managed-relation-add-button.ant-btn .anticon) {
  font-size: 12px;
}
</style>
