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
});
