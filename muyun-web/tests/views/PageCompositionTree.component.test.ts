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
    expect(actionsAt('ui:groups:form')).toEqual(['add-group']);
    expect(actionsAt('ui:relation:form:participants')).toEqual(['remove']);
    expect(actionsAt('ui:relation-field:form:participants:exam-date')).toEqual(['remove']);
    for (const key of ['ui:root', 'ui:slot:list', 'ui:slot:list:fields']) {
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
    const root = findNode(tree.props('nodes'), 'ui:root')!;
    tree.vm.$emit('action', remove, root);
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

    tree.vm.$emit('update:expandedKeys', ['ui:root']);
    await wrapper.vm.$nextTick();
    expect(tree.props('expandedKeys')).toEqual(['ui:root']);
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
    expect(wrapper.emitted('reorder-group')).toEqual([['group_2', 0]]);
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

  it('accepts a validated metadata payload into an empty group', () => {
    const wrapper = mountTree({
      formGroups: [{ id: 'group_1', groupCode: 'group_1', title: '分组', fields: [] }],
    });
    const tree = uiTree(wrapper);
    const group = findNode(tree.props('nodes'), 'ui:group:form:group_1')!;
    const event = metadataEvent(group, 'inside');
    expect(tree.props('allowDrop')(event)).toBe(true);
    tree.vm.$emit('drop', event);
    expect(wrapper.emitted('metadata-drop')).toEqual([
      [{ kind: 'group', groupId: 'group_1' }, event.source.payload],
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
      expect(wrapper.emitted('metadata-drop')).toBeUndefined();
      for (const position of ['before', 'after'] as const) {
        const event = metadataEvent(field, position);
        expect(tree.props('allowDrop')(event)).toBe(true);
        tree.vm.$emit('drop', event);
      }
      expect(
        wrapper.emitted('metadata-drop')!.map(([target]) => (target as { index: number }).index),
      ).toEqual([0, 1]);
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
      expect(wrapper.emitted('metadata-drop')).toEqual([
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
    expect(wrapper.emitted('metadata-drop')).toBeUndefined();
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
  expect(wrapper.emitted('metadata-drop')).toEqual([
    [{ kind: 'group', groupId: 'group_1', index: 1 }, event.source.payload],
  ]);
});
