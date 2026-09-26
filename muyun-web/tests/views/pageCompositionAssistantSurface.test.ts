import { describe, expect, it, vi } from 'vitest';
import {
  parsePageCompositionCandidateInput,
  createPageCompositionAssistantSurface,
} from '@/views/pageCompositionAssistantSurface';
import {
  preparePageCompositionCandidate,
  pageCompositionChangeLines,
  type PageCompositionCandidateState,
} from '@/views/pageCompositionCandidate';

function fixture(): PageCompositionCandidateState {
  const title = { id: 'title-id', fieldName: 'title', title: '名称' };
  const note = { id: 'note-id', fieldName: 'note', title: '备注' };
  const system = { id: 'system-id', fieldName: 'createdAt', title: '创建时间', platformReadOnly: true };
  return {
    list: [{ ...title, properties: { label: '人工标题', width: '180px' } }],
    form: {
      fields: [title],
      groups: [{ id: 'group', groupCode: 'group', title: '说明', fields: [note] }],
      order: [
        { kind: 'field', id: title.id },
        { kind: 'group', id: 'group' },
      ],
    },
    detail: { fields: [], groups: [], order: [] },
    separateDetail: false,
    columns: true,
    quickSearchFields: ['title'],
    searchableFields: ['title', 'note'],
    fields: [title, note, system],
  };
}

describe('template constrained page candidates', () => {
  it('resolves every placement without changing metadata, groups or omitted manual properties', () => {
    const current = fixture();
    const before = JSON.stringify(current);
    const result = preparePageCompositionCandidate(
      current,
      parsePageCompositionCandidateInput({
        list: [{ fieldName: 'note' }, { fieldName: 'title', properties: { align: 'right' } }],
        form: [{ fieldName: 'createdAt' }, { fieldName: 'title', properties: { label: '新标题' } }],
      }),
    );
    expect(JSON.stringify(current)).toBe(before);
    expect(result.list[1]?.properties).toEqual({ label: '人工标题', width: '180px', align: 'right' });
    expect(result.form.groups).toEqual(current.form.groups);
    expect(result.form.fields[0]?.properties?.readOnly).toBe(true);
    expect(result.form.order).toEqual([
      { kind: 'field', id: 'system-id' },
      { kind: 'group', id: 'group' },
      { kind: 'field', id: 'title-id' },
    ]);
    expect(result.quickSearchFields).toEqual(['title']);
  });

  it('rejects unknown fields, grouped duplication, readonly downgrades and unavailable template regions atomically', () => {
    const current = fixture();
    const before = JSON.stringify(current);
    for (const input of [
      { list: [{ fieldName: 'title' }, { fieldName: 'missing' }] },
      { form: [{ fieldName: 'note' }] },
      { form: [{ fieldName: 'createdAt', properties: { readOnly: false } }] },
      { detail: [] },
      { quickSearchFields: ['createdAt'] },
    ])
      expect(() => preparePageCompositionCandidate(current, input)).toThrow();
    expect(() => preparePageCompositionCandidate({ ...current, columns: false }, { list: [] })).toThrow(
      'no list columns',
    );
    expect(JSON.stringify(current)).toBe(before);
  });

  it.each([
    {},
    { uiTreeJson: '{}' },
    { form: [{ fieldName: 'title', properties: { script: 'alert(1)' } }] },
    { list: [{ fieldName: 'title', properties: { readOnly: true } }] },
    { form: [{ fieldName: 'title', properties: { columnSpan: 3 } }] },
    { list: [{ fieldName: 'title', properties: { width: 'calc(100%)' } }] },
    { list: [{ fieldName: 'title' }, { fieldName: 'title' }] },
    { list: Array.from({ length: 41 }, (_, index) => ({ fieldName: `field${index}` })) },
  ])('rejects malformed or unconstrained changes: %j', (input) => {
    expect(() => parsePageCompositionCandidateInput(input)).toThrow();
  });

  it('compares manual presentation changes and removal against the saved revision', () => {
    const before = JSON.stringify({
      nodes: [{ slot: 'list', fields: ['title', 'note'] }],
      quickSearchFields: ['title'],
    });
    const after = JSON.stringify({
      nodes: [{ slot: 'list', fields: [{ field: 'title', props: { label: '人工标题' } }] }],
      quickSearchFields: [],
    });
    expect(pageCompositionChangeLines(before, after).join('\n')).toContain('人工标题');
    expect(pageCompositionChangeLines(before, after).join('\n')).toContain('快速查询：title → 无');
  });

  it('exposes only local candidate effects and rejects a stale preview result', async () => {
    const commit = vi.fn(() => ({ saved: false }));
    const prepare = vi.fn(() => commit);
    const adapter = {
      describe: () => ({ moduleAlias: 'education.exam', editable: true }),
      candidate: () => ({ changes: ['列表：无 → title'] }),
      prepare,
      preview: vi.fn(async () => ({ valid: true, errors: [] })),
    };
    const surface = createPageCompositionAssistantSurface(adapter, vi.fn());
    expect(
      surface
        .capabilities()
        .map((tool) => tool.descriptor.code)
        .join(' '),
    ).not.toMatch(/publish|save/);
    const effect = vi.fn();
    const context = {
      signal: new AbortController().signal,
      isCurrent: () => false,
      applyEffect<T>(callback: () => T) {
        effect();
        return callback();
      },
      commitInternalState<T>(callback: () => T) {
        return callback();
      },
    };
    const preview = surface
      .capabilities()
      .find((tool) => tool.descriptor.code === 'configuration.preview-page-candidate')!;
    await expect(preview.execute({}, context)).rejects.toThrow('no longer current');
    expect(prepare).not.toHaveBeenCalled();
    const tool = surface
      .capabilities()
      .find((entry) => entry.descriptor.code === 'configuration.revise-page-candidate')!;
    await tool.execute(tool.parseInput({ list: [] }), { ...context, isCurrent: () => true });
    expect(effect).toHaveBeenCalledOnce();
    expect(prepare).toHaveBeenCalledWith({ list: [] });
    expect(commit).toHaveBeenCalledOnce();
    prepare.mockImplementationOnce(() => {
      throw new Error('Field is unavailable: missing');
    });
    await expect(
      tool.execute(tool.parseInput({ list: [{ fieldName: 'missing' }] }), context),
    ).rejects.toMatchObject({ code: 'CAPABILITY_USAGE_INVALID' });
    expect(effect).toHaveBeenCalledOnce();
    expect(commit).toHaveBeenCalledOnce();
  });
});
