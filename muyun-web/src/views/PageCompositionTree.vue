<script setup lang="ts">
import { pageActionEntryTitle, pageActionIntent } from '@muyun/web-core';
import {
  canPlaceActionInAnchor,
  actionButtons,
  actionButtonKey,
  actionButtonMembers,
  type CompositionSkeleton,
  type PageCompositionActionPlacement,
} from './pageCompositionMode';
import { computed, ref, watch } from 'vue';
import {
  UiTree,
  type UiRecordInlineAction,
  type UiTreeDropEvent,
  type UiTreeNode,
} from '@muyun/vue-ui-antdv';
import {
  orderedFormItems,
  type PageComposerFormItem,
  type PageComposerField,
  type PageComposerGroup,
  type PageComposerRelation,
  type PageQuerySummary,
} from './pageCompositionDraftState';
import {
  PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE,
  type PageCompositionDragPayload,
  parsePageCompositionDragPayload,
} from './pageCompositionDragPayload';

import {
  resolveCompositionPlacement,
  type CompositionPlacementSource,
  type CompositionPlacementTarget,
} from './pageCompositionPlacement';

defineOptions({ name: 'PageCompositionTree' });

const props = withDefaults(
  defineProps<{
    skeleton?: CompositionSkeleton;
    explorerTitle?: string;
    searchableFieldIds?: string[];
    quickSearchFields?: { fieldName: string; title: string }[];
    querySummaries?: PageQuerySummary[];
    summariesSupported?: boolean;
    summaryDescriptions?: Record<string, string>;
    summaryIssues?: Record<string, string>;
    explorerSecondary?: string;
    actionPlacements?: PageCompositionActionPlacement[];
    moduleActions?: {
      actionCode: string;
      title?: string;
      bindingPending?: boolean;
      category?: string;
      executorType?: string;
      actionLevel?: 'LIST' | 'RECORD' | 'BATCH' | 'ANY' | 'DEFAULT';
    }[];
    editorMode?: 'fields' | 'actions';
    listFields: PageComposerField[];
    formFields: PageComposerField[];
    formGroups: PageComposerGroup[];
    formOrder?: PageComposerFormItem[];
    formRelations: PageComposerRelation[];
    selectedKey?: string;
    disabled?: boolean;
  }>(),
  { selectedKey: undefined, disabled: false, editorMode: 'fields', formOrder: undefined },
);

const emit = defineEmits<{
  select: [key: string];
  'double-click': [key: string];
  'node-action': [action: ComposerNodeAction, key: string];
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
  'reorder-query-summary': [summaryKey: string, targetIndex: number];
  'source-drop': [target: ComposerDropTarget, payload: PageCompositionDragPayload];
  'action-drop': [
    source: { actionCode: string; sourceAnchor?: PageCompositionActionPlacement['anchor'] },
    target: { anchor: PageCompositionActionPlacement['anchor']; index: number },
  ];
  /** Kept for field/relation callers while actions use the broader source contract. */
}>();

type ComposerNodeAction = 'configure' | 'remove' | 'add-group' | 'toggle-visibility';

export type ComposerDropTarget = (
  | { kind: 'explorer-title' | 'explorer-secondary' | 'quick-search' }
  | { kind: 'list' }
  | { kind: 'form' }
  | { kind: 'group'; groupId: string }
  | { kind: 'relation'; relationId: string }
  | { kind: 'action-anchor'; anchor: PageCompositionActionPlacement['anchor'] }
) & { index?: number };

type ComposerNodeRef =
  | { kind: 'explorer-title' | 'explorer-secondary' | 'quick-search' }
  | { kind: 'binding'; role: 'explorer-title' | 'explorer-secondary' | 'quick-search'; fieldName: string }
  | { kind: 'slot'; slot: 'list' | 'form' }
  | { kind: 'template' }
  | { kind: 'summary'; summaryKey: string }
  | { kind: 'fieldGroup'; slot: 'list' | 'form' }
  | { kind: 'field'; slot: 'list' | 'form'; fieldId: string }
  | { kind: 'group'; groupId: string }
  | { kind: 'groupField'; groupId: string; fieldId: string }
  | { kind: 'relation'; relationId: string }
  | { kind: 'relationField'; relationId: string; fieldId: string }
  | { kind: 'action-anchor'; anchor: PageCompositionActionPlacement['anchor'] }
  | { kind: 'action'; anchor: PageCompositionActionPlacement['anchor']; actionCode: string };

const expandedKeys = ref<string[]>([]);

const treeNodes = computed<UiTreeNode[]>(() => {
  const listFieldNodes = props.listFields.map((field) => fieldNode('list', field));
  const formFieldNodes = props.formFields.map((field) => fieldNode('form', field));
  const groupNodes = props.formGroups.map((group) => ({
    key: `ui:group:form:${group.id}`,
    title: group.title,
    secondary: group.fields.length ? `${group.fields.length} 个字段` : '空分组 · 可拖入字段',
    isLeaf: group.fields.length === 0,
    actions: nodeActions('configure', 'remove'),
    children: group.fields.map((field) => groupFieldNode(group.id, field)),
  }));
  const relationNodes = props.formRelations.map((relation) => ({
    key: `ui:relation:form:${relation.id}`,
    title: relation.title,
    secondary: relation.fields.length ? `${relation.fields.length} 个展示字段` : '尚未选择字段',
    tag: relation.unavailable ? '来源失效' : undefined,
    muted: relation.unavailable,
    isLeaf: relation.fields.length === 0,
    actions: nodeActions('remove'),
    children: relation.fields.map((field) => relationFieldNode(relation.id, field)),
  }));

  const nodes: UiTreeNode[] = [
    {
      key: 'ui:slot:list',
      title: props.skeleton?.navigationTitle ?? '列表',
      isLeaf: false,
      children: [
        {
          key: 'ui:template:list:quick-search',
          title: '快速查询',
          secondary: '拖入文本字段 · 双击编辑占位提示',
          actions: nodeActions('configure'),
          isLeaf: !props.quickSearchFields?.length,
          children: (props.quickSearchFields ?? []).map((field) => ({
            key: `ui:binding:quick-search:${field.fieldName}`,
            title: field.title,
            secondary: field.fieldName,
            isLeaf: true,
            actions: nodeActions('remove'),
          })),
        },
        ...(props.summariesSupported
          ? [
              {
                key: 'ui:template:list:query-summaries',
                title: '汇总统计',
                secondary: props.querySummaries?.length
                  ? `${props.querySummaries.length} 项 · 完整筛选结果`
                  : '配置记录数、数值合计或按字段分组统计',
                actions: nodeActions('configure'),
                isLeaf: !props.querySummaries?.length,
                children: (props.querySummaries ?? []).map((summary) => ({
                  key: `ui:summary:${summary.key}`,
                  title: summary.label,
                  secondary:
                    props.summaryDescriptions?.[summary.key] ??
                    (summary.source === 'MATCHED_COUNT'
                      ? '记录数'
                      : summary.source === 'SUM'
                        ? `合计 · ${summary.fieldName ?? '未选择数值字段'}`
                        : summary.source === 'GROUPED'
                          ? `按${summary.groupByField ?? '未选择分组字段'}分组${summary.fieldName ? ` · ${summary.fieldName}合计` : ' · 记录数'}`
                          : `业务指标 · ${summary.contributorKey ?? '未选择'}`),
                  tag: props.summaryIssues?.[summary.key] ? '需修正' : undefined,
                  actions: nodeActions('configure'),
                  isLeaf: true,
                })),
              },
            ]
          : []),
        {
          key: 'ui:slot:list:fields',
          title: props.skeleton?.fieldGroupTitle ?? '列表展示字段',
          secondary:
            props.skeleton?.columns === false
              ? undefined
              : props.listFields.length
                ? '拖拽调整顺序'
                : '拖动字段到此处',
          isLeaf: props.skeleton?.columns === false ? false : listFieldNodes.length === 0,
          children:
            props.skeleton?.columns === false
              ? [
                  {
                    key: 'ui:explorer-title',
                    title: '标题',
                    secondary: props.explorerTitle ? '必填 · 拖入替换' : '必填 · 拖入字段',
                    isLeaf: !props.explorerTitle,
                    children: props.explorerTitle
                      ? [{ key: 'ui:binding:explorer-title:field', title: props.explorerTitle, isLeaf: true }]
                      : [],
                  },
                  {
                    key: 'ui:explorer-secondary',
                    title: '辅助信息',
                    secondary: '可选 · 拖入替换',
                    isLeaf: !props.explorerSecondary,
                    children: props.explorerSecondary
                      ? [
                          {
                            key: 'ui:binding:explorer-secondary:field',
                            title: props.explorerSecondary,
                            isLeaf: true,
                            actions: nodeActions('remove'),
                          },
                        ]
                      : [],
                  },
                ]
              : listFieldNodes,
        },
      ],
    },
    {
      key: 'ui:slot:form',
      title: '详情 / 表单',
      actions: nodeActions('add-group'),
      isLeaf: false,
      children: [
        ...orderedFormItems(props.formFields, props.formGroups, props.formOrder).map((item) =>
          item.kind === 'field'
            ? formFieldNodes.find((node) => node.key === `ui:field:form:${item.id}`)!
            : groupNodes.find((node) => node.key === `ui:group:form:${item.id}`)!,
        ),
        ...relationNodes,
      ],
    },
    ...(['page', 'detail', 'form'] as const).map((anchor) => ({
      key: `ui:action-anchor:${anchor}`,
      title: actionAnchorTitle(anchor),
      secondary: props.actionPlacements?.some((placement) => placement.anchor === anchor)
        ? '拖拽调整顺序'
        : '拖入模块动作',
      isLeaf:
        anchor !== 'form' && !(props.actionPlacements ?? []).some((placement) => placement.anchor === anchor),
      children: [
        ...(anchor === 'form'
          ? [{ key: 'ui:fixed:cancel', title: '取消', secondary: '模板固定 · 放弃编辑', isLeaf: true }]
          : []),
        ...actionButtons(props.actionPlacements ?? [])
          .filter((placement) => placement.anchor === anchor)
          .map((placement) => actionNode(anchor, placement.actionCode)),
      ],
    })),
  ];
  return props.editorMode === 'actions'
    ? nodes.filter((node) => node.key.startsWith('ui:action-anchor:'))
    : nodes.filter((node) => !node.key.startsWith('ui:action-anchor:'));
});

function actionAnchorTitle(anchor: PageCompositionActionPlacement['anchor']) {
  return anchor === 'page' ? '页面动作' : anchor === 'detail' ? '详情动作' : '表单动作';
}

function actionNode(anchor: PageCompositionActionPlacement['anchor'], actionCode: string): UiTreeNode {
  const action = props.moduleActions?.find((candidate) => candidate.actionCode === actionCode);
  const entry = props.actionPlacements?.find(
    (entry) => entry.anchor === anchor && entry.actionCode === actionCode,
  ) ?? { anchor, actionCode };
  const members = actionButtonMembers(props.actionPlacements ?? [], entry);
  const hidden = members.every((member) => member.hidden);
  const standard = !!pageActionIntent(actionCode, anchor);
  const button = actionButtonKey(entry);
  return {
    key: `ui:action:${anchor}:${actionCode}`,
    title:
      entry.title ??
      (button === 'status'
        ? '状态切换'
        : !standard && action?.title
          ? action.title
          : pageActionEntryTitle(entry)),
    secondary: hidden
      ? '已隐藏'
      : button === 'save'
        ? '新建 / 编辑'
        : button === 'status'
          ? '随记录状态显示启用或停用'
          : undefined,
    muted: hidden,
    tag: !action ? '来源失效' : action.bindingPending ? '待绑定' : undefined,
    actions: standard
      ? [
          ...(hidden ? [] : nodeActions('configure')),
          {
            key: 'toggle-visibility',
            title: hidden ? '显示' : '隐藏',
            iconName: hidden ? 'eye-off' : 'eye',
            disabled: props.disabled,
          },
        ]
      : nodeActions('configure', 'remove'),
    isLeaf: true,
  };
}

let previousKeys = new Set<string>();
watch(
  treeNodes,
  (nodes) => {
    const available = new Set(flattenNodes(nodes).map((node) => node.key));
    const defaults = [
      'ui:action-anchor:page',
      'ui:action-anchor:detail',
      'ui:action-anchor:form',
      'ui:slot:list',
      'ui:slot:list:fields',
      'ui:template:list:quick-search',
      'ui:template:list:query-summaries',
      'ui:explorer-title',
      'ui:explorer-secondary',
      'ui:slot:form',
      ...props.formGroups.map((group) => `ui:group:form:${group.id}`),
    ];
    const current = expandedKeys.value.filter((key) => available.has(key));
    expandedKeys.value = [
      ...new Set([...current, ...defaults.filter((key) => available.has(key) && !previousKeys.has(key))]),
    ];
    previousKeys = available;
  },
  { immediate: true },
);

function fieldNode(slot: 'list' | 'form', field: PageComposerField): UiTreeNode {
  return {
    key: `ui:field:${slot}:${field.id}`,
    title: field.properties?.label ?? field.title,
    secondary: field.fieldName,
    tag: field.unavailable ? '来源失效' : undefined,
    muted: field.unavailable,
    actions: nodeActions('configure', 'remove'),
    isLeaf: true,
  };
}

function groupFieldNode(groupId: string, field: PageComposerField): UiTreeNode {
  return {
    key: `ui:group-field:form:${groupId}:${field.id}`,
    title: field.properties?.label ?? field.title,
    secondary: field.fieldName,
    tag: field.unavailable ? '来源失效' : undefined,
    muted: field.unavailable,
    actions: nodeActions('configure', 'remove'),
    isLeaf: true,
  };
}

function relationFieldNode(relationId: string, field: PageComposerField): UiTreeNode {
  return {
    key: `ui:relation-field:form:${relationId}:${field.id}`,
    title: field.properties?.label ?? field.title,
    secondary: field.fieldName,
    tag: field.unavailable ? '来源失效' : undefined,
    muted: field.unavailable,
    actions: nodeActions('configure', 'remove'),
    isLeaf: true,
  };
}

function flattenNodes(nodes: UiTreeNode[]): UiTreeNode[] {
  return nodes.flatMap((node) => [node, ...(node.children ? flattenNodes(node.children) : [])]);
}

function nodeActions(...keys: ComposerNodeAction[]): UiRecordInlineAction[] {
  const definitions: Record<ComposerNodeAction, UiRecordInlineAction> = {
    configure: { key: 'configure', title: '配置', iconName: 'edit' },
    remove: { key: 'remove', title: '移除', iconName: 'delete', danger: true },
    'toggle-visibility': { key: 'toggle-visibility', title: '隐藏', iconName: 'eye' },
    'add-group': { key: 'add-group', title: '添加分组', iconName: 'plus' },
  };
  return keys.map((key) => ({ ...definitions[key], disabled: props.disabled }));
}

function handleNodeAction(action: UiRecordInlineAction, node: UiTreeNode) {
  if (props.disabled || action.disabled) return;
  const current = flattenNodes(treeNodes.value).find((candidate) => candidate.key === node.key);
  if (!current?.actions?.some((candidate) => candidate.key === action.key && !candidate.disabled)) return;
  emit('node-action', action.key as ComposerNodeAction, node.key);
}

function parseNode(key: string): ComposerNodeRef | undefined {
  const binding = /^ui:binding:(explorer-title|explorer-secondary|quick-search):(.+)$/.exec(key);
  if (binding)
    return {
      kind: 'binding',
      role: binding[1] as 'explorer-title' | 'explorer-secondary' | 'quick-search',
      fieldName: binding[2],
    };
  if (key === 'ui:explorer-title') return { kind: 'explorer-title' };
  if (key === 'ui:explorer-secondary') return { kind: 'explorer-secondary' };
  if (key === 'ui:slot:list') return { kind: 'slot', slot: 'list' };
  if (key === 'ui:slot:form') return { kind: 'slot', slot: 'form' };
  if (key === 'ui:template:list:quick-search') return { kind: 'template' };
  const summary = /^ui:summary:(.+)$/.exec(key);
  if (summary) return { kind: 'summary', summaryKey: summary[1] };
  if (key === 'ui:slot:list:fields') return { kind: 'fieldGroup', slot: 'list' };
  const action = /^ui:action:(page|detail|form):(.+)$/.exec(key);
  if (action)
    return {
      kind: 'action',
      anchor: action[1] as PageCompositionActionPlacement['anchor'],
      actionCode: action[2],
    };
  const actionAnchor = /^ui:action-anchor:(page|detail|form)$/.exec(key);
  if (actionAnchor)
    return { kind: 'action-anchor', anchor: actionAnchor[1] as PageCompositionActionPlacement['anchor'] };
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
  return Boolean(
    parsed &&
    (['field', 'groupField', 'group', 'relationField', 'action'].includes(parsed.kind) ||
      (parsed.kind === 'summary' &&
        props.querySummaries?.some((summary) => summary.key === parsed.summaryKey))),
  );
}

function formPlacement(event: UiTreeDropEvent) {
  if (event.target.kind !== 'node') return;
  const target = parseNode(event.target.node.key);
  let destination: CompositionPlacementTarget | undefined;
  if (target?.kind === 'slot' && target.slot === 'form' && event.target.position === 'inside')
    destination = { container: { kind: 'form' }, position: 'inside' };
  else if (target?.kind === 'field' && target.slot === 'form' && event.target.position !== 'inside')
    destination = { container: { kind: 'form' }, anchorId: target.fieldId, position: event.target.position };
  else if (target?.kind === 'group')
    destination =
      event.target.position === 'inside'
        ? { container: { kind: 'group', groupId: target.groupId }, position: 'inside' }
        : { container: { kind: 'form' }, anchorId: target.groupId, position: event.target.position };
  else if (target?.kind === 'groupField' && event.target.position !== 'inside')
    destination = {
      container: { kind: 'group', groupId: target.groupId },
      anchorId: target.fieldId,
      position: event.target.position,
    };
  if (!destination) return;
  const node = parseNode(event.source.node.key);
  const metadata = parsePageCompositionDragPayload(event.source.payload);
  let source: CompositionPlacementSource | undefined;
  if (
    event.operation === 'copy' &&
    event.source.payloadType === PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE &&
    metadata?.kind === 'field'
  )
    source = { kind: 'metadata', metadata };
  else if (event.operation !== 'copy') {
    if (node?.kind === 'field' && node.slot === 'form')
      source = { kind: 'node', container: { kind: 'form' }, nodeId: node.fieldId };
    else if (node?.kind === 'groupField')
      source = { kind: 'node', container: { kind: 'group', groupId: node.groupId }, nodeId: node.fieldId };
    else if (node?.kind === 'group')
      source = { kind: 'node', container: { kind: 'groups' }, nodeId: node.groupId };
  }
  if (!source) return;
  const placement = resolveCompositionPlacement(
    {
      list: props.listFields,
      form: props.formFields,
      groups: props.formGroups,
      relations: props.formRelations,
      order: props.formOrder,
    },
    source,
    destination,
  );
  return { source, destination, placement };
}

function emitFormPlacement(event: UiTreeDropEvent) {
  const result = formPlacement(event);
  if (!result) return false;
  if (!result.placement || props.disabled) return true;
  const {
    source,
    placement: { container, index },
  } = result;
  if (source.kind === 'metadata') {
    if (container.kind === 'form' || container.kind === 'group') {
      emit('source-drop', { ...container, index }, source.metadata);
    }
  } else if (source.container.kind === 'groups') emit('reorder-group', source.nodeId, index);
  else if (container.kind === 'form') {
    if (source.container.kind === 'group')
      emit('move-group-field-to-form', source.container.groupId, source.nodeId, index);
    else emit('reorder-form-field', source.nodeId, index);
  } else if (container.kind === 'group') {
    if (source.container.kind === 'group') {
      if (source.container.groupId === container.groupId)
        emit('reorder-group-field', container.groupId, source.nodeId, index);
      else
        emit('move-group-field-to-group', source.container.groupId, source.nodeId, container.groupId, index);
    } else emit('move-form-field-to-group', source.nodeId, container.groupId, index);
  }
  return true;
}

function allowDrop(event: UiTreeDropEvent) {
  const form = formPlacement(event);
  if (form) return !props.disabled && !!form.placement;
  if (event.source.instanceId !== event.target.instanceId) return allowExternalDrop(event);
  if (event.target.kind !== 'node') return false;
  if (props.disabled || event.operation !== 'move') return false;
  const source = parseNode(event.source.node.key);
  const target = parseNode(event.target.node.key);
  if (!source || !target || source.kind === 'template') return false;
  if (source.kind === 'summary') {
    return (
      target.kind === 'summary' &&
      event.target.position !== 'inside' &&
      source.summaryKey !== target.summaryKey &&
      props.querySummaries?.some((summary) => summary.key === source.summaryKey) === true &&
      props.querySummaries?.some((summary) => summary.key === target.summaryKey) === true
    );
  }
  if (source.kind === 'action') {
    if (event.source.node.key === event.target.node.key) return false;
    if (
      (target.kind === 'action' || target.kind === 'action-anchor') &&
      source.anchor !== target.anchor &&
      props.moduleActions?.find((action) => action.actionCode === source.actionCode)?.category !== 'CUSTOM'
    )
      return false;
    if (target.kind === 'action-anchor')
      return event.target.position === 'inside' && actionCanOccupyAnchor(source.actionCode, target.anchor);
    return (
      target.kind === 'action' &&
      event.target.position !== 'inside' &&
      actionCanOccupyAnchor(source.actionCode, target.anchor)
    );
  }
  if (target.kind === 'group' && source.kind !== 'group' && event.target.position !== 'inside') return false;
  const fieldTarget = ['field', 'groupField', 'relationField'].includes(target.kind);
  if (fieldTarget && event.target.position === 'inside') return false;
  if (!fieldTarget && target.kind !== 'group' && event.target.position !== 'inside') return false;
  if (event.source.node.key === event.target.node.key) return false;
  if (source.kind === 'field' && source.slot === 'list') {
    return (target.kind === 'fieldGroup' && target.slot === 'list') || isFieldTarget(target, 'list');
  }
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
  if (emitFormPlacement(event)) return;
  if (event.source.instanceId !== event.target.instanceId) {
    handleExternalDrop(event);
    return;
  }
  if (event.target.kind !== 'node') return;
  if (!allowDrop(event)) return;
  const source = parseNode(event.source.node.key);
  const target = parseNode(event.target.node.key);
  if (!source || !target) return;

  if (source.kind === 'summary' && target.kind === 'summary') {
    const targetIndex = insertionIndex(
      (props.querySummaries ?? []).map((summary) => summary.key),
      source.summaryKey,
      target.summaryKey,
      event,
    );
    if (targetIndex !== undefined) emit('reorder-query-summary', source.summaryKey, targetIndex);
    return;
  }

  if (source.kind === 'action') {
    const anchor = target.kind === 'action' || target.kind === 'action-anchor' ? target.anchor : undefined;
    if (!anchor) return;
    const actionsAtTarget = actionPlacementsWithout(source.actionCode, anchor);
    const targetIndex =
      target.kind === 'action'
        ? Math.max(
            0,
            actionsAtTarget.findIndex((placement) => placement.actionCode === target.actionCode),
          ) + (event.target.position === 'after' ? 1 : 0)
        : actionsAtTarget.length;
    emit(
      'action-drop',
      { actionCode: source.actionCode, sourceAnchor: source.anchor },
      { anchor, index: targetIndex },
    );
    return;
  }

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

function actionPlacementsWithout(actionCode: string, anchor: PageCompositionActionPlacement['anchor']) {
  return actionButtons(props.actionPlacements ?? []).filter(
    (placement) => placement.actionCode !== actionCode && placement.anchor === anchor,
  );
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
  event: UiTreeDropEvent,
) {
  const sourceIndex = ids.indexOf(sourceId);
  const targetIndex = targetId === undefined ? ids.length : ids.indexOf(targetId);
  if (targetIndex < 0) return undefined;
  if (event.target.position === 'inside') return ids.length;
  let index = targetIndex + (event.target.position === 'after' ? 1 : 0);
  if (sourceIndex >= 0 && sourceIndex < index) index -= 1;
  return Math.max(0, Math.min(index, ids.length));
}

function allowExternalDrop(event: UiTreeDropEvent) {
  const form = formPlacement(event);
  if (form) return !props.disabled && !!form.placement;
  if (event.operation !== 'copy') return false;
  if (event.target.kind !== 'node') return false;
  const target = composerDropTarget(event.target.node);
  if (!target || props.disabled) return false;
  const parsed = parseNode(event.target.node.key);
  if (
    parsed &&
    ['field', 'groupField', 'relationField', 'action'].includes(parsed.kind) &&
    event.target.position === 'inside'
  )
    return false;
  if (
    event.target.position !== 'inside' &&
    parsed?.kind !== 'field' &&
    parsed?.kind !== 'groupField' &&
    parsed?.kind !== 'relationField' &&
    parsed?.kind !== 'action'
  )
    return false;
  const payload = parsePageCompositionDragPayload(event.source.payload);
  if (target.kind === 'action-anchor')
    return payload?.kind === 'action' && actionCanOccupyAnchor(payload.actionCode, target.anchor);
  if (
    target.kind === 'quick-search' &&
    payload?.kind === 'field' &&
    props.searchableFieldIds &&
    !props.searchableFieldIds.includes(payload.fieldId)
  )
    return false;
  return (
    event.source.payloadType === PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE &&
    !!payload &&
    (target.kind === 'relation'
      ? payload.kind === 'relationField' && payload.relationId === target.relationId
      : payload.kind === 'field' || target.kind === 'form')
  );
}

function actionCanOccupyAnchor(actionCode: string, anchor: PageCompositionActionPlacement['anchor']) {
  const action = props.moduleActions?.find((candidate) => candidate.actionCode === actionCode);
  return canPlaceActionInAnchor(action, anchor);
}

function composerDropTarget(node: UiTreeNode): ComposerDropTarget | undefined {
  const parsed = parseNode(node.key);
  if (!parsed) return undefined;
  if (parsed.kind === 'template') return { kind: 'quick-search' };
  if (parsed.kind === 'action-anchor' || parsed.kind === 'action')
    return { kind: 'action-anchor', anchor: parsed.anchor };
  if (parsed.kind === 'binding') return { kind: parsed.role };
  if (parsed.kind === 'explorer-title' || parsed.kind === 'explorer-secondary') return { kind: parsed.kind };
  if (
    props.skeleton?.columns === false &&
    (parsed.kind === 'fieldGroup' || parsed.kind === 'slot' || parsed.kind === 'field') &&
    parsed.slot === 'list'
  )
    return undefined;
  if (parsed.kind === 'fieldGroup' && parsed.slot === 'list') return { kind: 'list' };
  if (
    (parsed.kind === 'slot' && parsed.slot === 'list') ||
    (parsed.kind === 'field' && parsed.slot === 'list')
  )
    return { kind: 'list' };
  if (
    (parsed.kind === 'slot' && parsed.slot === 'form') ||
    (parsed.kind === 'field' && parsed.slot === 'form')
  )
    return { kind: 'form' };
  if (parsed.kind === 'group' || parsed.kind === 'groupField')
    return { kind: 'group', groupId: parsed.groupId };
  if (parsed.kind === 'relation' || parsed.kind === 'relationField')
    return { kind: 'relation', relationId: parsed.relationId };
  return undefined;
}

function handleExternalDrop(event: UiTreeDropEvent) {
  if (emitFormPlacement(event)) return;
  if (event.target.kind !== 'node') return;
  const target = composerDropTarget(event.target.node);
  if (!target || props.disabled) return;
  if (!allowExternalDrop(event)) return;
  const parsed = parseNode(event.target.node.key);
  const metadata = parsePageCompositionDragPayload(event.source.payload);
  if (target.kind === 'action-anchor') {
    if (metadata?.kind === 'action') {
      const actions = actionPlacementsWithout(metadata.actionCode, target.anchor);
      target.index = insertionIndex(
        actions.map((action) => action.actionCode),
        metadata.actionCode,
        parsed?.kind === 'action' ? parsed.actionCode : undefined,
        event,
      );
      emit('source-drop', target, metadata);
    }
    return;
  }
  if (target.kind === 'relation' && metadata?.kind === 'relationField') {
    const fields = props.formRelations.find((relation) => relation.id === target.relationId)?.fields ?? [];
    target.index =
      event.target.position === 'inside'
        ? fields.filter((field) => field.id !== metadata.fieldId).length
        : insertionIndex(
            fields.map((field) => field.id),
            metadata.fieldId,
            parsed?.kind === 'relationField' ? parsed.fieldId : undefined,
            event,
          );
  }
  if (
    event.target.position !== 'inside' &&
    metadata?.kind === 'field' &&
    target.kind !== 'relation' &&
    target.kind !== 'explorer-title' &&
    target.kind !== 'explorer-secondary' &&
    target.kind !== 'quick-search' &&
    (parsed?.kind === 'field' || parsed?.kind === 'groupField')
  ) {
    const fields =
      target.kind === 'list'
        ? props.listFields
        : target.kind === 'form'
          ? props.formFields
          : target.kind === 'group'
            ? (props.formGroups.find((group) => group.id === target.groupId)?.fields ?? [])
            : [];
    target.index = insertionIndex(
      fields.map((field) => field.id),
      metadata.fieldId,
      parsed.fieldId,
      event,
    );
  }
  if (metadata) {
    emit('source-drop', target, metadata);
  }
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
      :drop-operation="
        (source) => (source.payloadType === PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE ? 'copy' : 'move')
      "
      @select="select"
      @double-click="doubleClick"
      @action="handleNodeAction"
      @drop="handleDrop"
    />
  </div>
</template>

<style scoped>
.page-composition-tree {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  color: var(--ant-color-text);
  font-size: 14px;
}

.page-composition-tree__ui-tree {
  flex: 1 1 auto;
  min-height: 0;
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
