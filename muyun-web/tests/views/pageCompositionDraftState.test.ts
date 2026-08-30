import { describe, expect, it } from 'vitest';
import { createPageCompositionDraftState } from '@/views/pageCompositionDraftState';

const title = { id: 'title', title: '考试名称', fieldName: 'title' };
const date = { id: 'date', title: '考试日期', fieldName: 'examDate' };

describe('pageCompositionDraftState', () => {
  it('keeps list and form placements independent and selects the new component', () => {
    const state = createPageCompositionDraftState();
    state.addField(title, 'list');
    state.addField(title, 'form');

    expect(state.listFields.value).toEqual([title]);
    expect(state.formFields.value).toEqual([title]);
    expect(state.selectedNodeId.value).toBe('form:title');
    expect(state.previewMode.value).toBe('edit');
  });

  it('does not duplicate a field in the same template slot and supports ordering/removal', () => {
    const state = createPageCompositionDraftState();
    state.addField(title);
    state.addField(title);
    state.addField(date);
    state.moveSelectedField(-1);

    expect(state.listFields.value.map((field) => field.id)).toEqual(['date', 'title']);
    state.removeSelectedField();
    expect(state.listFields.value.map((field) => field.id)).toEqual(['title']);
    expect(state.selectedNodeId.value).toBe('slot:list');
  });

  it('serializes the management v1 tree with independent list and form field order', () => {
    const state = createPageCompositionDraftState();
    state.addField(date, 'list');
    state.addField(title, 'form');
    state.addField(date, 'form');

    expect(state.toManagementUiTree()).toEqual({
      template: 'management',
      templateVersion: 1,
      nodes: [
        { slot: 'list', title: '列表', fields: ['examDate'] },
        { slot: 'form', title: '详情 / 表单', fields: ['title', 'examDate'] },
      ],
    });
  });

  it('serializes only the dedicated quick-search placeholder in root props and supports discard restoration', () => {
    const state = createPageCompositionDraftState();
    state.updateQuickSearchPlaceholder('搜索考试名称');
    const savedTree = JSON.stringify(state.toManagementUiTree());

    expect(state.toManagementUiTree().props).toEqual({ list: { searchPlaceholder: '搜索考试名称' } });
    state.updateQuickSearchPlaceholder('搜索考试名称、科目');
    expect(JSON.stringify(state.toManagementUiTree())).not.toBe(savedTree);

    state.updateQuickSearchPlaceholder('搜索考试名称');
    expect(JSON.stringify(state.toManagementUiTree())).toBe(savedTree);
    state.updateQuickSearchPlaceholder('');
    expect(state.toManagementUiTree().props).toBeUndefined();
  });

  it('selects the template-owned quick-search component without treating it as a field', () => {
    const state = createPageCompositionDraftState();
    const quickSearch = state.nodes.value.find((node) => node.id === 'template:list:quick-search');

    expect(quickSearch).toMatchObject({ kind: 'template', slot: 'list' });
    expect(quickSearch?.field).toBeUndefined();
    state.selectNode(quickSearch!);
    expect(state.selectedNodeId.value).toBe('template:list:quick-search');
    expect(state.previewMode.value).toBe('query');
  });

  it('switches form fields to edit while association components stay in detail', () => {
    const state = createPageCompositionDraftState();
    state.addField(date, 'form');
    expect(state.previewMode.value).toBe('edit');

    state.addFormRelation({
      id: 'participants',
      relationCode: 'participants',
      title: '参考学生',
      fields: [],
    });
    expect(state.previewMode.value).toBe('detail');
  });

  it('reorders explicitly placed child-table fields and serializes their display order', () => {
    const state = createPageCompositionDraftState();
    const relation = { id: 'participants', relationCode: 'participants', title: '参考学生', fields: [] };
    state.addFormRelationField(relation, title);
    state.addFormRelationField(relation, date);
    state.previewMode.value = 'edit';
    state.moveFormRelationField('participants', 'date', 0);

    expect(state.formRelations.value[0].fields.map((field) => field.id)).toEqual(['date', 'title']);
    expect(state.previewMode.value).toBe('edit');
    expect(state.toManagementUiTree().nodes[1].relations).toEqual([
      { relation: 'participants', title: '参考学生', fields: ['examDate', 'title'] },
    ]);
  });

  it('moves form fields into, within, and back out of a typed form group', () => {
    const state = createPageCompositionDraftState();
    state.addField(title, 'form');
    state.addField(date, 'form');
    state.addFormGroup();
    const group = state.formGroups.value[0];
    state.moveFormFieldToGroup('title', group.id);
    state.moveFormFieldToGroup('date', group.id);
    state.moveGroupField(group.id, 'date', 0);

    expect(group.groupCode).toBe('group_1');
    expect(state.formGroups.value[0].fields.map((field) => field.id)).toEqual(['date', 'title']);
    expect(state.toManagementUiTree().nodes[1].groups).toEqual([
      { group: 'group_1', title: '分组 1', fields: ['examDate', 'title'] },
    ]);

    state.moveGroupFieldToForm(group.id, 'title');
    expect(state.formFields.value.map((field) => field.id)).toEqual(['title']);
  });

  it('reorders form groups as first-class page components', () => {
    const state = createPageCompositionDraftState();
    state.addFormGroup();
    state.addFormGroup();
    state.addFormGroup();
    state.moveFormGroup('group_3', 0);

    expect(state.formGroups.value.map((group) => group.groupCode)).toEqual(['group_3', 'group_1', 'group_2']);
  });

  it('assigns a fresh group code after a group is deleted', () => {
    const state = createPageCompositionDraftState();
    state.addFormGroup();
    state.addFormGroup();
    state.selectNode(state.nodes.value.find((node) => node.id === 'form:group:group_1')!);
    state.removeSelectedField();
    state.addFormGroup();

    expect(state.formGroups.value.map((group) => group.groupCode)).toEqual(['group_2', 'group_1']);
    expect(new Set(state.formGroups.value.map((group) => group.groupCode)).size).toBe(2);
  });

  it('moves a group field directly into another group at the dropped position', () => {
    const state = createPageCompositionDraftState();
    state.addField(title, 'form');
    state.addField(date, 'form');
    state.addFormGroup();
    state.addFormGroup();
    const [first, second] = state.formGroups.value;
    state.moveFormFieldToGroup('title', first.id);
    state.moveFormFieldToGroup('date', second.id);

    state.moveGroupFieldToGroup(second.id, 'date', first.id, 0);

    expect(state.formGroups.value[0].fields.map((field) => field.id)).toEqual(['date', 'title']);
    expect(state.formGroups.value[1].fields).toEqual([]);
  });

  it('keeps every form field in one placement when metadata is added again after grouping', () => {
    const state = createPageCompositionDraftState();
    state.addField(date, 'form');
    state.addFormGroup();
    state.addFormGroup();
    const [first, second] = state.formGroups.value;
    state.moveFormFieldToGroup('date', first.id);

    state.addField(date, 'form');
    state.moveFormFieldToGroup('date', second.id);

    expect(state.formFields.value).toEqual([]);
    expect(state.formGroups.value[0].fields.map((field) => field.id)).toEqual(['date']);
    expect(state.formGroups.value[1].fields).toEqual([]);
    expect(state.toManagementUiTree().nodes[1].groups).toEqual([
      { group: 'group_1', title: '分组 1', fields: ['examDate'] },
      { group: 'group_2', title: '分组 2', fields: [] },
    ]);
  });

  it('updates and serializes properties for a field placed inside a form group', () => {
    const state = createPageCompositionDraftState();
    state.addField(title, 'form');
    state.addFormGroup();
    const group = state.formGroups.value[0];
    state.moveFormFieldToGroup(title.id, group.id);
    state.selectNode(
      state.nodes.value.find((node) => node.id === `form:group:${group.id}:field:${title.id}`)!,
    );
    state.updateSelectedFieldProperties({ label: '考试标题', columnSpan: 2 });

    expect(state.formGroups.value[0].fields[0].properties).toEqual({ label: '考试标题', columnSpan: 2 });
    expect(state.toManagementUiTree().nodes[1].groups).toEqual([
      {
        group: 'group_1',
        title: '分组 1',
        fields: [{ field: 'title', props: { label: '考试标题', columnSpan: 2 } }],
      },
    ]);
  });

  it('switches a selected group field to the editable form preview', () => {
    const state = createPageCompositionDraftState();
    state.addField(title, 'form');
    state.addFormGroup();
    const group = state.formGroups.value[0];
    state.moveFormFieldToGroup(title.id, group.id);

    state.selectNode(
      state.nodes.value.find((node) => node.id === `form:group:${group.id}:field:${title.id}`)!,
    );

    expect(state.previewMode.value).toBe('edit');
  });

  it('repairs a legacy draft that placed one form field in more than one group', () => {
    const state = createPageCompositionDraftState();
    state.replaceFields({
      list: [],
      form: [],
      groups: [
        { id: 'group_1', groupCode: 'group_1', title: '基础信息', fields: [date] },
        { id: 'group_2', groupCode: 'group_2', title: '科目信息', fields: [date, title] },
      ],
    });

    expect(state.formGroups.value.map((group) => group.fields.map((field) => field.id))).toEqual([
      ['date'],
      ['title'],
    ]);
  });

  it('moves a UI tree component between slots or to a precise sibling position', () => {
    const state = createPageCompositionDraftState();
    state.addField(title, 'list');
    state.addField(date, 'list');
    state.moveField('date', 'list', 'list', 0);
    state.moveField('date', 'list', 'form');

    expect(state.listFields.value).toEqual([title]);
    expect(state.formFields.value).toEqual([date]);
    expect(state.selectedNodeId.value).toBe('form:date');
  });

  it('inserts an external metadata field at the explicit UI tree drop position', () => {
    const state = createPageCompositionDraftState();
    state.addField(title, 'list');
    state.addField(date, 'list', 0);

    expect(state.listFields.value).toEqual([date, title]);
  });
});
