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
    expect(state.previewMode.value).toBe('detail');
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
    expect(state.previewMode.value).toBe('list');
  });

  it('uses the list field placement as the single source for card preview', () => {
    const state = createPageCompositionDraftState();
    state.addField(title, 'list');
    state.addField(date, 'form');

    expect(state.cardFields.value).toEqual([title]);
    state.moveField('title', 'list', 'form');
    expect(state.cardFields.value).toEqual([]);
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
