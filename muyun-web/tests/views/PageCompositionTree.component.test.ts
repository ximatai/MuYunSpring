import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import type { UiTreeNode } from '@muyun/vue-ui-antdv';
import PageCompositionTree from '@/views/PageCompositionTree.vue';
import {
  PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE,
  parseMetadataDragPayload,
} from '@/views/pageCompositionDragPayload';

const subject = { id: 'subject', title: '科目', fieldName: 'subject' };
const examDate = { id: 'exam-date', title: '考试日期', fieldName: 'examDate' };

const UiTreeStub = {
  name: 'UiTree',
  props: [
    'nodes',
    'expandedKeys',
    'selectedKey',
    'draggable',
    'canDrag',
    'allowDrop',
    'allowExternalDrop',
    'dragPayloadType',
    'dropOperation',
  ],
  template: '<div data-testid="ui-tree-stub" />',
};

function mountTree(props: Record<string, unknown>) {
  return mount(PageCompositionTree, {
    props: {
      listFields: [],
      formFields: [],
      formGroups: [],
      formRelations: [],
      ...props,
    },
    global: { stubs: { UiTree: UiTreeStub } },
  });
}

function uiTree(wrapper: ReturnType<typeof mountTree>) {
  return wrapper.findComponent({ name: 'UiTree' });
}

type TestNode = UiTreeNode;

function findNode(nodes: TestNode[], key: string): TestNode | undefined {
  for (const node of nodes) {
    if (node.key === key) return node;
    const child = node.children ? findNode(node.children, key) : undefined;
    if (child) return child;
  }
  return undefined;
}

function dropEvent(
  dragNode: { key: string; title: string },
  dropNode: { key: string; title: string },
  dropPosition: -1 | 0 | 1,
  dropToGap = true,
) {
  return {
    source: { instanceId: 'tree', node: dragNode, operations: ['move'] as const },
    target: {
      instanceId: 'tree',
      kind: 'node' as const,
      node: dropNode,
      position: (!dropToGap || dropPosition === 0 ? 'inside' : dropPosition < 0 ? 'before' : 'after') as
        | 'inside'
        | 'before'
        | 'after',
    },
    operation: 'move' as const,
  };
}

describe('PageCompositionTree', () => {
  it('offers actions at their owning nodes without allowing fixed template removal', () => {
    const wrapper = mountTree({
      listFields: [subject],
      formFields: [examDate],
      formGroups: [{ id: 'group_1', groupCode: 'group_1', title: '基础信息', fields: [subject] }],
      formRelations: [
        { id: 'participants', relationCode: 'participants', title: '参考学生', fields: [examDate] },
      ],
    });
    const tree = uiTree(wrapper);
    const nodes = tree.props('nodes') as TestNode[];
    const actionsAt = (key: string) => findNode(nodes, key)?.actions?.map((action) => action.key) ?? [];

    for (const key of [
      'ui:field:list:subject',
      'ui:field:form:exam-date',
      'ui:group:form:group_1',
      'ui:group-field:form:group_1:subject',
    ]) {
      expect(actionsAt(key)).toEqual(['configure', 'remove']);
    }
    expect(actionsAt('ui:template:list:quick-search')).toEqual(['configure']);
    expect(actionsAt('ui:slot:form')).toEqual(['add-group']);
    expect(findNode(nodes, 'ui:groups:form')).toBeUndefined();
    expect(actionsAt('ui:relation:form:participants')).toEqual(['remove']);
    expect(actionsAt('ui:relation-field:form:participants:exam-date')).toEqual(['configure', 'remove']);
    for (const key of ['ui:slot:list', 'ui:slot:list:fields']) {
      expect(actionsAt(key)).toEqual([]);
    }

    for (const [key, actionKey] of [
      ['ui:field:list:subject', 'configure'],
      ['ui:relation:form:participants', 'remove'],
      ['ui:slot:form', 'add-group'],
    ]) {
      const node = findNode(nodes, key)!;
      tree.vm.$emit(
        'action',
        node.actions!.find((action) => action.key === actionKey),
        node,
      );
    }
    expect(wrapper.emitted('node-action')).toEqual([
      ['configure', 'ui:field:list:subject'],
      ['remove', 'ui:relation:form:participants'],
      ['add-group', 'ui:slot:form'],
    ]);
    expect(wrapper.emitted('select')).toBeUndefined();
    expect(wrapper.emitted('double-click')).toBeUndefined();
  });

  it('guards stale, unsupported and disabled node actions', async () => {
    const wrapper = mountTree({ listFields: [subject] });
    const tree = uiTree(wrapper);
    const field = findNode(tree.props('nodes'), 'ui:field:list:subject')!;
    const remove = field.actions!.find((action) => action.key === 'remove')!;
    const list = findNode(tree.props('nodes'), 'ui:slot:list')!;
    tree.vm.$emit('action', remove, list);
    tree.vm.$emit('action', { ...remove, disabled: true }, field);

    await wrapper.setProps({ disabled: true });
    expect(findNode(tree.props('nodes'), field.key)?.actions?.every((action) => action.disabled)).toBe(true);
    tree.vm.$emit('action', remove, field);

    await wrapper.setProps({ disabled: false, listFields: [] });
    tree.vm.$emit('action', remove, field);
    expect(wrapper.emitted('node-action')).toBeUndefined();
  });

  it('projects the page draft into one shared tree contract with stable branches', () => {
    const wrapper = mountTree({
      listFields: [subject],
      formFields: [examDate],
      formGroups: [{ id: 'group_1', groupCode: 'group_1', title: '基础信息', fields: [subject] }],
      formRelations: [
        { id: 'participants', relationCode: 'participants', title: '参考学生', fields: [examDate] },
      ],
    });
    const tree = uiTree(wrapper);
    const nodes = tree.props('nodes') as TestNode[];

    expect(tree.props('draggable')).toBe(true);
    expect(tree.props('dragPayloadType')).toBeUndefined();
    const operation = tree.props('dropOperation') as (source: { payloadType?: string }) => string;
    expect(operation({ payloadType: PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE })).toBe('copy');
    expect(operation({})).toBe('move');
    expect(findNode(nodes, 'ui:template:list:quick-search')).toBeTruthy();
    expect(findNode(nodes, 'ui:slot:list:fields')).toMatchObject({
      title: '列表展示字段',
      children: [{ key: 'ui:field:list:subject' }],
    });
    expect(findNode(nodes, 'ui:group:form:group_1')).toMatchObject({
      title: '基础信息',
      children: [{ key: 'ui:group-field:form:group_1:subject' }],
    });
    expect(findNode(nodes, 'ui:relation:form:participants')).toMatchObject({
      children: [{ key: 'ui:relation-field:form:participants:exam-date' }],
    });
  });

  it('keeps expansion state in the shared tree instead of owning nested sortable lists', async () => {
    const wrapper = mountTree({ listFields: [subject] });
    const tree = uiTree(wrapper);
    const initialExpanded = tree.props('expandedKeys') as string[];
    expect(initialExpanded).toContain('ui:slot:list');
    expect(initialExpanded).toContain('ui:slot:form');

    tree.vm.$emit('update:expandedKeys', ['ui:slot:list']);
    await wrapper.vm.$nextTick();
    expect(tree.props('expandedKeys')).toEqual(['ui:slot:list']);
    expect(wrapper.findAllComponents({ name: 'UiTree' })).toHaveLength(1);
  });

  it('maps same-list and same-form drops to typed reorder commands', async () => {
    const wrapper = mountTree({ listFields: [subject, examDate], formFields: [subject, examDate] });
    const tree = uiTree(wrapper);
    const listSubject = findNode(tree.props('nodes') as TestNode[], 'ui:field:list:subject')!;
    const listExamDate = findNode(tree.props('nodes') as TestNode[], 'ui:field:list:exam-date')!;
    const formSubject = findNode(tree.props('nodes') as TestNode[], 'ui:field:form:subject')!;
    const formExamDate = findNode(tree.props('nodes') as TestNode[], 'ui:field:form:exam-date')!;

    tree.vm.$emit('drop', dropEvent(listExamDate, listSubject, -1));
    tree.vm.$emit('drop', dropEvent(formSubject, formExamDate, 1));
    await wrapper.vm.$nextTick();

    expect(wrapper.emitted('reorder-list-field')).toEqual([['exam-date', 0]]);
    expect(wrapper.emitted('reorder-form-field')).toEqual([['subject', 1]]);
  });

  it('maps form/group drops, group sorting, and relation sorting to semantic commands', async () => {
    const wrapper = mountTree({
      formFields: [examDate],
      formGroups: [
        { id: 'group_1', groupCode: 'group_1', title: '基础信息', fields: [subject] },
        { id: 'group_2', groupCode: 'group_2', title: '补充信息', fields: [examDate] },
      ],
      formRelations: [
        { id: 'participants', relationCode: 'participants', title: '参考学生', fields: [subject, examDate] },
      ],
    });
    const tree = uiTree(wrapper);
    const formExamDate = findNode(tree.props('nodes') as TestNode[], 'ui:field:form:exam-date')!;
    const groupSubject = findNode(tree.props('nodes') as TestNode[], 'ui:group-field:form:group_1:subject')!;
    const groupTwo = findNode(tree.props('nodes') as TestNode[], 'ui:group:form:group_2')!;
    const groupOne = findNode(tree.props('nodes') as TestNode[], 'ui:group:form:group_1')!;
    const relationSubject = findNode(
      tree.props('nodes') as TestNode[],
      'ui:relation-field:form:participants:subject',
    )!;
    const relationExamDate = findNode(
      tree.props('nodes') as TestNode[],
      'ui:relation-field:form:participants:exam-date',
    )!;

    tree.vm.$emit('drop', dropEvent(formExamDate, groupOne, 0, false));
    tree.vm.$emit('drop', dropEvent(groupSubject, formExamDate, 1));
    tree.vm.$emit('drop', dropEvent(groupTwo, groupOne, -1));
    tree.vm.$emit('drop', dropEvent(relationExamDate, relationSubject, -1));
    await wrapper.vm.$nextTick();

    expect(wrapper.emitted('move-form-field-to-group')).toEqual([['exam-date', 'group_1', 1]]);
    expect(wrapper.emitted('move-group-field-to-form')).toEqual([['group_1', 'subject', 1]]);
    expect(wrapper.emitted('reorder-group')).toEqual([['group_2', 1]]);
    expect(wrapper.emitted('reorder-relation-field')).toEqual([['participants', 'exam-date', 0]]);
  });

  it('moves a grouped field between groups and appends onto empty containers', async () => {
    const wrapper = mountTree({
      formGroups: [
        { id: 'group_1', groupCode: 'group_1', title: '基础信息', fields: [subject] },
        { id: 'group_2', groupCode: 'group_2', title: '补充信息', fields: [] },
      ],
    });
    const tree = uiTree(wrapper);
    const groupedSubject = findNode(
      tree.props('nodes') as TestNode[],
      'ui:group-field:form:group_1:subject',
    )!;
    const emptyGroup = findNode(tree.props('nodes') as TestNode[], 'ui:group:form:group_2')!;

    tree.vm.$emit('drop', dropEvent(groupedSubject, emptyGroup, 0, false));
    await wrapper.vm.$nextTick();

    expect(wrapper.emitted('move-group-field-to-group')).toEqual([['group_1', 'subject', 'group_2', 0]]);
  });

  it('does not allow a group to become a child of another group', () => {
    const wrapper = mountTree({
      formGroups: [
        { id: 'group_1', groupCode: 'group_1', title: '基础信息', fields: [] },
        { id: 'group_2', groupCode: 'group_2', title: '补充信息', fields: [] },
      ],
    });
    const tree = uiTree(wrapper);
    const allowDrop = tree.props('allowDrop') as (event: ReturnType<typeof dropEvent>) => boolean;
    const groupOne = findNode(tree.props('nodes') as TestNode[], 'ui:group:form:group_1')!;
    const groupTwo = findNode(tree.props('nodes') as TestNode[], 'ui:group:form:group_2')!;

    expect(allowDrop(dropEvent(groupTwo, groupOne, 0, false))).toBe(false);
    expect(allowDrop(dropEvent(groupTwo, groupOne, -1))).toBe(true);
  });

  it('exposes only sortable page nodes to the shared drag predicate', () => {
    const wrapper = mountTree({ listFields: [subject] });
    const tree = uiTree(wrapper);
    const canDrag = tree.props('canDrag') as (node: { key: string; title: string }) => boolean;

    expect(canDrag({ key: 'ui:field:list:subject', title: '科目' })).toBe(true);
    expect(canDrag({ key: 'ui:slot:list', title: '列表' })).toBe(false);
    expect(canDrag({ key: 'ui:template:list:quick-search', title: '快速查询' })).toBe(false);
  });

  it('uses action titles and emits ordered moves between action anchors', () => {
    const wrapper = mountTree({
      editorMode: 'actions',
      moduleActions: [
        { actionCode: 'create', title: '新建', actionLevel: 'LIST' },
        { actionCode: 'delete', title: '删除', actionLevel: 'RECORD' },
        { actionCode: 'update', title: '编辑', actionLevel: 'RECORD' },
      ],
      actionPlacements: [
        { actionCode: 'create', anchor: 'page' },
        { actionCode: 'delete', anchor: 'detail' },
        { actionCode: 'update', anchor: 'detail' },
      ],
    });
    const tree = uiTree(wrapper);
    const nodes = tree.props('nodes') as TestNode[];
    const canDrag = tree.props('canDrag') as (node: TestNode) => boolean;
    const allowDrop = tree.props('allowDrop') as (event: ReturnType<typeof dropEvent>) => boolean;
    const deleteAction = findNode(nodes, 'ui:action:detail:delete')!;
    const updateAction = findNode(nodes, 'ui:action:detail:update')!;
    const formAnchor = findNode(nodes, 'ui:action-anchor:form')!;

    expect(deleteAction).toMatchObject({ title: '删除', secondary: 'delete' });
    expect(canDrag(deleteAction)).toBe(true);
    expect(allowDrop(dropEvent(updateAction, deleteAction, -1))).toBe(true);
    expect(allowDrop(dropEvent(updateAction, formAnchor, 0, false))).toBe(true);
    expect(allowDrop(dropEvent(deleteAction, formAnchor, 0, false))).toBe(false);

    tree.vm.$emit('drop', dropEvent(updateAction, deleteAction, -1));
    tree.vm.$emit('drop', dropEvent(updateAction, formAnchor, 0, false));

    expect(wrapper.emitted('action-drop')).toEqual([
      [
        { actionCode: 'update', sourceAnchor: 'detail' },
        { anchor: 'detail', index: 0 },
      ],
      [
        { actionCode: 'update', sourceAnchor: 'detail' },
        { anchor: 'form', index: 0 },
      ],
    ]);
  });

  it('accepts a validated metadata payload into an empty group', () => {
    const wrapper = mountTree({
      formGroups: [{ id: 'group_1', groupCode: 'group_1', title: '分组', fields: [] }],
    });
    const tree = uiTree(wrapper);
    const group = findNode(tree.props('nodes'), 'ui:group:form:group_1')!;
    const event = metadataEvent(group, 'inside');
    expect(tree.props('allowDrop')(event)).toBe(true);
    tree.vm.$emit('drop', event);
    expect(wrapper.emitted('source-drop')).toEqual([
      [{ kind: 'group', groupId: 'group_1', index: 0 }, event.source.payload],
    ]);
  });
  it('rejects malformed and unrelated external payloads', () => {
    const tree = uiTree(mountTree({}));
    const list = findNode(tree.props('nodes'), 'ui:slot:list')!;
    const event = metadataEvent(list, 'inside');
    expect(
      tree.props('allowDrop')({ ...event, source: { ...event.source, payloadType: 'text/plain' } }),
    ).toBe(false);
    expect(
      tree.props('allowDrop')({
        ...event,
        source: { ...event.source, payload: { kind: 'field', fieldId: {} } },
      }),
    ).toBe(false);
    expect(parseMetadataDragPayload({ kind: 'field', fieldId: {} })).toBeUndefined();
  });

  it.each(['ui:field:list:subject', 'ui:field:form:subject', 'ui:group-field:form:group_1:subject'])(
    'treats metadata dropped onto %s as an adjacent placement, never a field interior',
    (key) => {
      const wrapper = mountTree({
        listFields: [subject, examDate],
        formFields: [subject, examDate],
        formGroups: [{ id: 'group_1', groupCode: 'group_1', title: '分组', fields: [subject, examDate] }],
      });
      const tree = uiTree(wrapper);
      const field = findNode(tree.props('nodes'), key)!;
      const inside = metadataEvent(field, 'inside');
      expect(tree.props('allowDrop')(inside)).toBe(false);
      tree.vm.$emit('drop', inside);
      expect(wrapper.emitted('source-drop')).toBeUndefined();
      for (const position of ['before', 'after'] as const) {
        const event = metadataEvent(field, position);
        expect(tree.props('allowDrop')(event)).toBe(true);
        tree.vm.$emit('drop', event);
      }
      expect(wrapper.emitted('source-drop')!.map(([target]) => (target as { index: number }).index)).toEqual([
        0, 1,
      ]);
    },
  );

  it.each([
    ['new-field', 'exam-date', 'before', 1],
    ['new-field', 'exam-date', 'after', 2],
    ['subject', 'exam-date', 'after', 1],
    ['exam-date', 'subject', 'before', 0],
    ['subject', undefined, 'inside', 1],
  ] as const)(
    'places child metadata %s %s %s at its owning table index %s',
    (fieldId, anchorId, position, index) => {
      const wrapper = mountTree({
        formRelations: [
          {
            id: 'participants',
            relationCode: 'participants',
            title: '参考学生',
            fields: [subject, examDate],
          },
        ],
      });
      const tree = uiTree(wrapper);
      const key = anchorId
        ? `ui:relation-field:form:participants:${anchorId}`
        : 'ui:relation:form:participants';
      const node = findNode(tree.props('nodes'), key)!;
      const event = metadataEvent(node, position);
      const payload = { kind: 'relationField', relationId: 'participants', fieldId };
      const childEvent = { ...event, source: { ...event.source, payload } };

      expect(tree.props('allowDrop')(childEvent)).toBe(true);
      tree.vm.$emit('drop', childEvent);
      expect(wrapper.emitted('source-drop')).toEqual([
        [{ kind: 'relation', relationId: 'participants', index }, payload],
      ]);
    },
  );

  it('rejects cross-table and main-field drops into child tables and field interiors', () => {
    const wrapper = mountTree({
      formRelations: [
        { id: 'participants', relationCode: 'participants', title: '参考学生', fields: [subject] },
      ],
    });
    const tree = uiTree(wrapper);
    const relation = findNode(tree.props('nodes'), 'ui:relation:form:participants')!;
    const field = findNode(tree.props('nodes'), 'ui:relation-field:form:participants:subject')!;
    for (const payload of [
      { kind: 'field', fieldId: 'new-main-field' },
      { kind: 'relationField', relationId: 'another-table', fieldId: 'new-child-field' },
    ]) {
      for (const [target, position] of [
        [relation, 'inside'],
        [field, 'before'],
        [field, 'after'],
      ] as const) {
        const event = metadataEvent(target, position);
        const invalid = { ...event, source: { ...event.source, payload } };
        expect(tree.props('allowDrop')(invalid)).toBe(false);
        tree.vm.$emit('drop', invalid);
      }
    }
    const event = metadataEvent(field, 'inside');
    const interior = {
      ...event,
      source: {
        ...event.source,
        payload: { kind: 'relationField', relationId: 'participants', fieldId: 'new-child-field' },
      },
    };
    expect(tree.props('allowDrop')(interior)).toBe(false);
    tree.vm.$emit('drop', interior);
    expect(wrapper.emitted('source-drop')).toBeUndefined();
  });
});

function metadataEvent(node: TestNode, position: 'before' | 'after' | 'inside') {
  return {
    source: {
      instanceId: 'metadata',
      node: { key: 'new-field', title: 'New' },
      operations: ['copy'],
      payloadType: PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE,
      payload: { kind: 'field', fieldId: 'new-field' },
    },
    target: { instanceId: 'page', kind: 'node', node, position },
    operation: 'copy',
  };
}
it('preserves external field insertion index for a group', () => {
  const wrapper = mountTree({
    formGroups: [{ id: 'group_1', groupCode: 'group_1', title: '分组', fields: [subject, examDate] }],
  });
  const tree = uiTree(wrapper);
  const target = findNode(tree.props('nodes'), 'ui:group-field:form:group_1:exam-date')!;
  const event = metadataEvent(target, 'before');
  tree.vm.$emit('drop', event);
  expect(wrapper.emitted('source-drop')).toEqual([
    [{ kind: 'group', groupId: 'group_1', index: 1 }, event.source.payload],
  ]);
});

it.each(['TREE_CARD', 'MICRO_LIST_CARD'])('fills %s navigation skeletons with field children', (mode) => {
  const wrapper = mountTree({
    skeleton: {
      mode,
      title: '导航 + 卡片',
      navigationTitle: '导航',
      fieldGroupTitle: '节点展示',
      columns: false,
    },
    explorerTitle: '任务名称',
    explorerSecondary: '负责人',
    quickSearchFields: [{ fieldName: 'code', title: '任务编号' }],
    searchableFieldIds: ['new-field'],
  });
  const tree = uiTree(wrapper);
  const nodes = tree.props('nodes');
  expect(findNode(nodes, 'ui:explorer-title')?.children?.[0].title).toBe('任务名称');
  expect(findNode(nodes, 'ui:explorer-secondary')?.children?.[0].title).toBe('负责人');
  expect(findNode(nodes, 'ui:template:list:quick-search')?.children?.[0].title).toBe('任务编号');
  expect(findNode(nodes, 'ui:explorer-title')?.secondary).toBe('必填 · 拖入替换');
  expect(findNode(nodes, 'ui:binding:explorer-title:field')?.actions).toBeUndefined();
  expect(
    findNode(nodes, 'ui:binding:explorer-secondary:field')?.actions?.map((action) => action.key),
  ).toEqual(['remove']);
  for (const [key, kind] of [
    ['ui:explorer-title', 'explorer-title'],
    ['ui:explorer-secondary', 'explorer-secondary'],
    ['ui:template:list:quick-search', 'quick-search'],
  ]) {
    const event = metadataEvent(findNode(nodes, key)!, 'inside');
    expect(tree.props('allowDrop')(event)).toBe(true);
    tree.vm.$emit('drop', event);
    expect(wrapper.emitted('source-drop')?.at(-1)).toEqual([{ kind }, event.source.payload]);
    expect(tree.props('canDrag')(findNode(nodes, key))).toBe(false);
  }
  const invalid = metadataEvent(findNode(nodes, 'ui:template:list:quick-search')!, 'inside');
  invalid.source.payload.fieldId = 'number-field';
  expect(tree.props('allowDrop')(invalid)).toBe(false);
  wrapper.unmount();
});

it('renders mixed root siblings and interprets group edges as root insertions', () => {
  const wrapper = mountTree({
    formFields: [subject, examDate],
    formGroups: [{ id: 'group_1', groupCode: 'group_1', title: '分组', fields: [] }],
    formOrder: [
      { kind: 'field', id: 'subject' },
      { kind: 'group', id: 'group_1' },
      { kind: 'field', id: 'exam-date' },
    ],
  });
  const tree = uiTree(wrapper);
  const nodes = tree.props('nodes');
  expect(findNode(nodes, 'ui:slot:form')!.children!.map((node) => node.key)).toEqual([
    'ui:field:form:subject',
    'ui:group:form:group_1',
    'ui:field:form:exam-date',
  ]);
  const event = metadataEvent(findNode(nodes, 'ui:group:form:group_1')!, 'after');
  expect(tree.props('allowDrop')(event)).toBe(true);
  tree.vm.$emit('drop', event);
  expect(wrapper.emitted('source-drop')).toEqual([[{ kind: 'form', index: 2 }, event.source.payload]]);
  expect(wrapper.emitted('metadata-drop')).toBeUndefined();
});
