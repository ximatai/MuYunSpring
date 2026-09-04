import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import { VueDraggable } from 'vue-draggable-plus';
import PageCompositionTree from '@/views/PageCompositionTree.vue';

const subject = { id: 'subject', title: '科目', fieldName: 'subject' };
const examDate = { id: 'exam-date', title: '考试日期', fieldName: 'examDate' };

describe('PageCompositionTree', () => {
  it('collapses and expands page branches without changing the draft tree', async () => {
    const wrapper = mount(PageCompositionTree, {
      props: {
        listFields: [subject],
        formFields: [examDate],
        formGroups: [],
        formRelations: [],
      },
    });

    expect(wrapper.text()).toContain('快速查询');
    await wrapper.get('.page-composition-tree__node--branch-list').trigger('click');
    expect(wrapper.text()).not.toContain('快速查询');
    expect(wrapper.get('.page-composition-tree__node--branch-list').attributes('aria-expanded')).toBe(
      'false',
    );
    expect(wrapper.text()).toContain('详情 / 表单');

    await wrapper.get('.page-composition-tree__node--root').trigger('click');
    expect(wrapper.text()).not.toContain('列表');
    expect(wrapper.get('.page-composition-tree__node--root').attributes('aria-expanded')).toBe('false');
  });

  it('collapses a form group without changing its field placement', async () => {
    const wrapper = mount(PageCompositionTree, {
      props: {
        listFields: [],
        formFields: [],
        formGroups: [{ id: 'group_1', groupCode: 'group_1', title: '基础信息', fields: [subject] }],
        formRelations: [],
      },
    });

    const group = wrapper.get('.page-composition-tree__node--group');
    expect(wrapper.text()).toContain('科目');
    await group.trigger('click');
    expect(group.attributes('aria-expanded')).toBe('false');
    expect(wrapper.text()).not.toContain('科目');
    await group.trigger('click');
    expect(wrapper.text()).toContain('科目');
  });

  it('maps a Sortable cross-container drop to a semantic move-into-group command', async () => {
    const wrapper = mount(PageCompositionTree, {
      props: {
        listFields: [],
        formFields: [subject, examDate],
        formGroups: [{ id: 'group_1', groupCode: 'group_1', title: '基础信息', fields: [] }],
        formRelations: [],
      },
    });
    const draggables = wrapper.findAllComponents(VueDraggable);
    const formFields = draggables[1];
    const groupFields = draggables[3];
    const item = document.createElement('button');
    item.dataset.fieldId = 'subject';
    const source = document.createElement('div');
    source.dataset.composerFieldContainer = 'form';
    const target = document.createElement('div');
    target.dataset.composerFieldContainer = 'group';
    target.dataset.groupId = 'group_1';

    await formFields.vm.$emit('end', { item, from: source, to: target, newIndex: 0 });

    expect(wrapper.emitted('move-form-field-to-group')).toEqual([['subject', 'group_1', 0]]);
    expect(groupFields.exists()).toBe(true);
  });

  it('maps group sorting and field move-out to explicit editor commands', async () => {
    const wrapper = mount(PageCompositionTree, {
      props: {
        listFields: [],
        formFields: [],
        formGroups: [
          { id: 'group_1', groupCode: 'group_1', title: '基础信息', fields: [subject] },
          { id: 'group_2', groupCode: 'group_2', title: '补充信息', fields: [examDate] },
        ],
        formRelations: [],
      },
    });
    const draggables = wrapper.findAllComponents(VueDraggable);
    const groups = draggables[2];
    const firstGroupFields = draggables[3];
    const groupItem = document.createElement('section');
    groupItem.dataset.groupId = 'group_2';
    const fieldItem = document.createElement('button');
    fieldItem.dataset.fieldId = 'subject';
    const source = document.createElement('div');
    source.dataset.composerFieldContainer = 'group';
    source.dataset.groupId = 'group_1';
    const target = document.createElement('div');
    target.dataset.composerFieldContainer = 'form';

    await groups.vm.$emit('end', { item: groupItem, from: groups.element, to: groups.element, newIndex: 0 });
    await firstGroupFields.vm.$emit('end', { item: fieldItem, from: source, to: target, newIndex: 0 });

    expect(wrapper.emitted('reorder-group')).toEqual([['group_2', 0]]);
    expect(wrapper.emitted('move-group-field-to-form')).toEqual([['group_1', 'subject', 0]]);
  });

  it('maps child-table field sorting to a relation-specific command', async () => {
    const wrapper = mount(PageCompositionTree, {
      props: {
        listFields: [],
        formFields: [],
        formGroups: [],
        formRelations: [
          {
            id: 'participants',
            relationCode: 'participants',
            title: '参考学生',
            fields: [subject, examDate],
          },
        ],
      },
    });
    const relationFields = wrapper.findAllComponents(VueDraggable).at(-1)!;
    const item = document.createElement('button');
    item.dataset.fieldId = 'exam-date';

    await relationFields.vm.$emit('end', {
      item,
      from: relationFields.element,
      to: relationFields.element,
      newIndex: 0,
    });

    expect(wrapper.emitted('reorder-relation-field')).toEqual([['participants', 'exam-date', 0]]);
  });

  it('accepts metadata dropped onto an empty group body, not only its heading', async () => {
    const wrapper = mount(PageCompositionTree, {
      props: {
        listFields: [],
        formFields: [],
        formGroups: [{ id: 'group_1', groupCode: 'group_1', title: '基础信息', fields: [] }],
        formRelations: [],
      },
    });

    await wrapper.find('[data-composer-field-container="group"]').trigger('drop', {
      dataTransfer: { dropEffect: 'none' },
    });

    expect(wrapper.emitted('metadata-drop')?.[0]?.[0]).toEqual({ kind: 'group', groupId: 'group_1' });
  });

  it('highlights the active external drop branch and clears it when the drag leaves', async () => {
    const wrapper = mount(PageCompositionTree, {
      props: {
        listFields: [],
        formFields: [],
        formGroups: [],
        formRelations: [],
      },
    });
    const listBranch = wrapper.get('[data-composer-drop-target="list"]');
    const dataTransfer = { dropEffect: 'none' } as unknown as DataTransfer;

    await listBranch.trigger('dragover', { dataTransfer });
    expect(listBranch.classes()).toContain('is-drop-target');

    await wrapper.get('.page-composition-tree').trigger('dragleave', { relatedTarget: document.body });
    expect(listBranch.classes()).not.toContain('is-drop-target');
  });
});
