<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  UiTree,
  type UiTreeDropEvent,
  type UiTreeExternalDropEvent,
  type UiTreeNode,
} from '@muyun/vue-ui-antdv';
import type { PageComposerField, PageComposerGroup, PageComposerRelation } from './pageCompositionDraftState';
import { PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE, isPageCompositionDrag } from './pageCompositionDragPayload';

defineOptions({ name: 'PageCompositionTree' });

const props = withDefaults(
  defineProps<{
    listFields: PageComposerField[];
    formFields: PageComposerField[];
    formGroups: PageComposerGroup[];
    formRelations: PageComposerRelation[];
    selectedKey?: string;
    disabled?: boolean;
  }>(),
  { selectedKey: undefined, disabled: false },
);

const emit = defineEmits<{
  select: [key: string];
  'double-click': [key: string];
  'reorder-list-field': [fieldId: string, targetIndex: number];
  'reorder-form-field': [fieldId: string, targetIndex: number];
  'move-form-field-to-group': [fieldId: string, groupId: string, targetIndex: number];
  'move-group-field-to-form': [groupId: string, fieldId: string, targetIndex: number];
  'reorder-group-field': [groupId: string, fieldId: string, targetIndex: number];
  'move-group-field-to-group': [
    sourceGroupId: string,
    fieldId: string,
    targetGroupId: string,
    targetIndex: number,
  ];
  'reorder-group': [groupId: string, targetIndex: number];
  'reorder-relation-field': [relationId: string, fieldId: string, targetIndex: number];
  'metadata-drop': [target: ComposerDropTarget, nativeEvent: DragEvent];
}>();

export type ComposerDropTarget = { kind: 'list' } | { kind: 'form' } | { kind: 'group'; groupId: string };

type ComposerNodeRef =
  | { kind: 'root' }
  | { kind: 'slot'; slot: 'list' | 'form' }
  | { kind: 'template' }
  | { kind: 'fieldGroup'; slot: 'list' | 'form' }
  | { kind: 'field'; slot: 'list' | 'form'; fieldId: string }
  | { kind: 'groups' }
  | { kind: 'group'; groupId: string }
  | { kind: 'groupField'; groupId: string; fieldId: string }
  | { kind: 'relation'; relationId: string }
  | { kind: 'relationField'; relationId: string; fieldId: string };

const expandedKeys = ref<string[]>([]);

const treeNodes = computed<UiTreeNode[]>(() => {
  const listFieldNodes = props.listFields.map((field) => fieldNode('list', field));
  const formFieldNodes = props.formFields.map((field) => fieldNode('form', field));
  const groupNodes = props.formGroups.map((group) => ({
    key: `ui:group:form:${group.id}`,
    title: group.title,
    secondary: group.fields.length ? `${group.fields.length} 个字段` : '空分组 · 可拖入字段',
    isLeaf: false,
    children: group.fields.map((field) => groupFieldNode(group.id, field)),
  }));
  const relationNodes = props.formRelations.map((relation) => ({
    key: `ui:relation:form:${relation.id}`,
    title: relation.title,
    secondary: relation.fields.length ? `${relation.fields.length} 个展示字段` : '尚未选择字段',
    isLeaf: relation.fields.length === 0,
    children: relation.fields.map((field) => relationFieldNode(relation.id, field)),
  }));

  return [
    {
      key: 'ui:root',
      title: '页面',
      isLeaf: false,
      children: [
        {
          key: 'ui:slot:list',
          title: '列表',
          secondary: '标准列表',
          isLeaf: false,
          children: [
            {
              key: 'ui:template:list:quick-search',
              title: '快速查询',
              secondary: '可配置占位提示',
              isLeaf: true,
            },
            {
              key: 'ui:slot:list:fields',
              title: '列表展示字段',
              secondary: props.listFields.length ? '拖拽调整顺序' : '拖动字段到此处',
              isLeaf: false,
              children: listFieldNodes,
            },
          ],
        },
        {
          key: 'ui:slot:form',
          title: '详情 / 表单',
          isLeaf: false,
          children: [
            ...formFieldNodes,
            ...(props.formGroups.length
              ? [
                  {
                    key: 'ui:groups:form',
                    title: '表单分组',
                    secondary: '拖拽字段到分组',
                    isLeaf: false,
                    children: groupNodes,
                  },
                ]
              : []),
            ...relationNodes,
          ],
        },
      ],
    },
  ];
});

watch(
  treeNodes,
  (nodes) => {
    const available = new Set(flattenNodes(nodes).map((node) => node.key));
    const defaults = [
      'ui:root',
      'ui:slot:list',
      'ui:slot:list:fields',
      'ui:slot:form',
      ...(props.formGroups.length ? ['ui:groups:form'] : []),
      ...props.formGroups.map((group) => `ui:group:form:${group.id}`),
    ];
    const current = expandedKeys.value.filter((key) => available.has(key));
    expandedKeys.value = [...new Set([...current, ...defaults.filter((key) => available.has(key))])];
  },
  { immediate: true },
);

function fieldNode(slot: 'list' | 'form', field: PageComposerField): UiTreeNode {
  return {
    key: `ui:field:${slot}:${field.id}`,
    title: field.properties?.label ?? field.title,
    secondary: field.fieldName,
    isLeaf: true,
  };
}

function groupFieldNode(groupId: string, field: PageComposerField): UiTreeNode {
  return {
    key: `ui:group-field:form:${groupId}:${field.id}`,
    title: field.properties?.label ?? field.title,
    secondary: field.fieldName,
    isLeaf: true,
  };
}

function relationFieldNode(relationId: string, field: PageComposerField): UiTreeNode {
  return {
    key: `ui:relation-field:form:${relationId}:${field.id}`,
    title: field.title,
    secondary: field.fieldName,
    isLeaf: true,
  };
}

function flattenNodes(nodes: UiTreeNode[]): UiTreeNode[] {
  return nodes.flatMap((node) => [node, ...(node.children ? flattenNodes(node.children) : [])]);
}

function parseNode(key: string): ComposerNodeRef | undefined {
  if (key === 'ui:root') return { kind: 'root' };
  if (key === 'ui:slot:list') return { kind: 'slot', slot: 'list' };
  if (key === 'ui:slot:form') return { kind: 'slot', slot: 'form' };
  if (key === 'ui:template:list:quick-search') return { kind: 'template' };
  if (key === 'ui:slot:list:fields') return { kind: 'fieldGroup', slot: 'list' };
  if (key === 'ui:groups:form') return { kind: 'groups' };
  const listField = /^ui:field:list:(.+)$/.exec(key);
  if (listField) return { kind: 'field', slot: 'list', fieldId: listField[1] };
  const formField = /^ui:field:form:(.+)$/.exec(key);
  if (formField) return { kind: 'field', slot: 'form', fieldId: formField[1] };
  const groupField = /^ui:group-field:form:(.+):([^:]+)$/.exec(key);
  if (groupField) return { kind: 'groupField', groupId: groupField[1], fieldId: groupField[2] };
  const group = /^ui:group:form:(.+)$/.exec(key);
  if (group) return { kind: 'group', groupId: group[1] };
  const relationField = /^ui:relation-field:form:(.+):([^:]+)$/.exec(key);
  if (relationField)
    return { kind: 'relationField', relationId: relationField[1], fieldId: relationField[2] };
  const relation = /^ui:relation:form:(.+)$/.exec(key);
  if (relation) return { kind: 'relation', relationId: relation[1] };
  return undefined;
}

function select(node: UiTreeNode) {
  if (!props.disabled) emit('select', node.key);
}

function doubleClick(event: { node: UiTreeNode }) {
  if (!props.disabled) emit('double-click', event.node.key);
}

function canDragNode(node: UiTreeNode) {
  if (props.disabled) return false;
  const parsed = parseNode(node.key);
  return Boolean(parsed && ['field', 'groupField', 'group', 'relationField'].includes(parsed.kind));
}

function allowDrop(event: Pick<UiTreeDropEvent, 'dragNode' | 'dropNode' | 'dropPosition' | 'dropToGap'>) {
  if (props.disabled) return false;
  const source = parseNode(event.dragNode.key);
  const target = parseNode(event.dropNode.key);
  if (!source || !target || source.kind === 'root' || source.kind === 'template') return false;
  if (event.dragNode.key === event.dropNode.key) return false;
  if (source.kind === 'field' && source.slot === 'list') {
    return (target.kind === 'fieldGroup' && target.slot === 'list') || isFieldTarget(target, 'list');
  }
  if (source.kind === 'field' && source.slot === 'form') {
    return (
      (target.kind === 'slot' && target.slot === 'form') ||
      isFieldTarget(target, 'form') ||
      target.kind === 'group' ||
      target.kind === 'groupField'
    );
  }
  if (source.kind === 'groupField') {
    return (
      (target.kind === 'slot' && target.slot === 'form') ||
      isFieldTarget(target, 'form') ||
      target.kind === 'group' ||
      target.kind === 'groupField'
    );
  }
  if (source.kind === 'group') return target.kind === 'groups' || target.kind === 'group';
  if (source.kind === 'relationField') {
    return (
      (target.kind === 'relation' && target.relationId === source.relationId) ||
      (target.kind === 'relationField' && target.relationId === source.relationId)
    );
  }
  return false;
}

function isFieldTarget(target: ComposerNodeRef, slot: 'list' | 'form') {
  return target.kind === 'field' && target.slot === slot;
}

function handleDrop(event: UiTreeDropEvent) {
  if (!allowDrop(event)) return;
  const source = parseNode(event.dragNode.key);
  const target = parseNode(event.dropNode.key);
  if (!source || !target) return;

  if (source.kind === 'field' && source.slot === 'list') {
    const targetIndex = insertionIndex(
      props.listFields.map((field) => field.id),
      source.fieldId,
      targetFieldId(target),
      event,
    );
    if (targetIndex !== undefined) emit('reorder-list-field', source.fieldId, targetIndex);
    return;
  }
  if (source.kind === 'field' && source.slot === 'form') {
    if (target.kind === 'group' || target.kind === 'groupField') {
      const groupId = target.groupId;
      const group = props.formGroups.find((candidate) => candidate.id === groupId);
      if (group) {
        const targetIndex = insertionIndex(
          group.fields.map((field) => field.id),
          source.fieldId,
          targetFieldId(target),
          event,
        );
        emit('move-form-field-to-group', source.fieldId, groupId, targetIndex ?? group.fields.length);
      }
      return;
    }
    const targetIndex = insertionIndex(
      props.formFields.map((field) => field.id),
      source.fieldId,
      targetFieldId(target),
      event,
    );
    if (targetIndex !== undefined) emit('reorder-form-field', source.fieldId, targetIndex);
    return;
  }
  if (source.kind === 'groupField') {
    if (target.kind === 'group' || target.kind === 'groupField') {
      const targetGroupId = target.groupId;
      const targetGroup = props.formGroups.find((group) => group.id === targetGroupId);
      if (!targetGroup) return;
      const targetIndex = insertionIndex(
        targetGroup.fields.map((field) => field.id),
        source.fieldId,
        targetFieldId(target),
        event,
      );
      if (source.groupId === targetGroupId)
        emit('reorder-group-field', source.groupId, source.fieldId, targetIndex ?? targetGroup.fields.length);
      else
        emit(
          'move-group-field-to-group',
          source.groupId,
          source.fieldId,
          targetGroupId,
          targetIndex ?? targetGroup.fields.length,
        );
      return;
    }
    const targetIndex = insertionIndex(
      props.formFields.map((field) => field.id),
      source.fieldId,
      targetFieldId(target),
      event,
    );
    if (targetIndex !== undefined)
      emit('move-group-field-to-form', source.groupId, source.fieldId, targetIndex);
    return;
  }
  if (source.kind === 'group') {
    const targetIndex = insertionIndex(
      props.formGroups.map((group) => group.id),
      source.groupId,
      target.kind === 'group' ? target.groupId : undefined,
      event,
    );
    if (targetIndex !== undefined) emit('reorder-group', source.groupId, targetIndex);
    return;
  }
  if (source.kind === 'relationField') {
    const relation = props.formRelations.find((candidate) => candidate.id === source.relationId);
    if (!relation) return;
    const targetIndex = insertionIndex(
      relation.fields.map((field) => field.id),
      source.fieldId,
      targetFieldId(target),
      event,
    );
    if (targetIndex !== undefined)
      emit('reorder-relation-field', source.relationId, source.fieldId, targetIndex);
  }
}

function targetFieldId(target: ComposerNodeRef) {
  return target.kind === 'field' || target.kind === 'groupField' || target.kind === 'relationField'
    ? target.fieldId
    : undefined;
}

function insertionIndex(
  ids: string[],
  sourceId: string,
  targetId: string | undefined,
  event: Pick<UiTreeDropEvent, 'dropPosition' | 'dropToGap'>,
) {
  const sourceIndex = ids.indexOf(sourceId);
  const targetIndex = targetId === undefined ? ids.length : ids.indexOf(targetId);
  if (targetIndex < 0) return undefined;
  if (!event.dropToGap || event.dropPosition === 0) return ids.length;
  let index = targetIndex + (event.dropPosition === 1 ? 1 : 0);
  if (sourceIndex >= 0 && sourceIndex < index) index -= 1;
  return Math.max(0, Math.min(index, ids.length));
}

function allowExternalDrop(event: Omit<UiTreeExternalDropEvent, 'nativeEvent'>) {
  const target = composerDropTarget(event.dropNode);
  if (!target || props.disabled) return false;
  return !event.payloadType || event.payloadType === PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE;
}

function composerDropTarget(node: UiTreeNode): ComposerDropTarget | undefined {
  const parsed = parseNode(node.key);
  if (!parsed) return undefined;
  if (parsed.kind === 'fieldGroup' && parsed.slot === 'list') return { kind: 'list' };
  if (
    (parsed.kind === 'slot' && parsed.slot === 'list') ||
    (parsed.kind === 'field' && parsed.slot === 'list')
  )
    return { kind: 'list' };
  if (
    (parsed.kind === 'slot' && parsed.slot === 'form') ||
    (parsed.kind === 'field' && parsed.slot === 'form') ||
    parsed.kind === 'groups' ||
    parsed.kind === 'relation' ||
    parsed.kind === 'relationField'
  )
    return { kind: 'form' };
  if (parsed.kind === 'group' || parsed.kind === 'groupField')
    return { kind: 'group', groupId: parsed.groupId };
  return undefined;
}

function handleExternalDrop(event: UiTreeExternalDropEvent) {
  const target = composerDropTarget(event.dropNode);
  if (!target || props.disabled) return;
  if (
    (event.payloadType && event.payloadType !== PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE) ||
    !isPageCompositionDrag(event.nativeEvent.dataTransfer)
  )
    return;
  emit('metadata-drop', target, event.nativeEvent);
}
</script>

<template>
  <div class="page-composition-tree" data-testid="page-composition-sortable-tree">
    <UiTree
      v-model:expanded-keys="expandedKeys"
      class="page-composition-tree__ui-tree"
      :nodes="treeNodes"
      :selected-key="selectedKey"
      :draggable="!disabled"
      :can-drag="canDragNode"
      :allow-drop="allowDrop"
      :allow-external-drop="allowExternalDrop"
      :drag-payload-type="PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE"
      @select="select"
      @double-click="doubleClick"
      @drop="handleDrop"
      @external-drop="handleExternalDrop"
    />
  </div>
</template>

<style scoped>
.page-composition-tree {
  min-height: 260px;
  color: var(--ant-color-text);
  font-size: 14px;
}

.page-composition-tree__ui-tree {
  min-height: 260px;
}

.page-composition-tree__ui-tree :deep(.ant-tree) {
  min-width: 0;
}

.page-composition-tree__ui-tree :deep(.ant-tree-treenode) {
  min-width: 100%;
  padding: 2px 0;
}

.page-composition-tree__ui-tree :deep(.ant-tree-node-content-wrapper) {
  min-width: 0;
  border-radius: 5px;
}

.page-composition-tree__ui-tree :deep(.ui-record-explorer-item) {
  min-width: 0;
}

@media (prefers-reduced-motion: reduce) {
  .page-composition-tree__ui-tree :deep(.ant-tree-treenode-switcher-open),
  .page-composition-tree__ui-tree :deep(.ant-tree-treenode-switcher-close) {
    transition: none;
  }
}
</style>
