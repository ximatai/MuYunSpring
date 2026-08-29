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
});
