import { expect, it } from 'vitest';
import { resolveCompositionPlacement, type PageCompositionStructure } from '@/views/pageCompositionPlacement';
const field = (id: string) => ({ id, fieldName: id, title: id });
const model: PageCompositionStructure = {
  list: ['a', 'b', 'c'].map(field),
  form: [field('a')],
  groups: [
    { id: 'g', groupCode: 'g', title: '组', fields: [field('b')] },
    { id: 'empty', groupCode: 'empty', title: '空组', fields: [] },
  ],
  relations: [{ id: 'child', relationCode: 'child', title: '子表', fields: ['x', 'y'].map(field) }],
};
it('resolves anchors after removing an existing projection instead of drifting by one', () => {
  expect(
    resolveCompositionPlacement(
      model,
      { kind: 'node', container: { kind: 'list' }, nodeId: 'a' },
      { container: { kind: 'list' }, anchorId: 'c', position: 'after' },
    )?.index,
  ).toBe(2);
  expect(
    resolveCompositionPlacement(
      model,
      { kind: 'metadata', metadata: { kind: 'field', fieldId: 'a' } },
      { container: { kind: 'list' }, anchorId: 'b', position: 'before' },
    )?.index,
  ).toBe(0);
});
it('places a main field in an empty group and moves it out without cloning its properties', () => {
  expect(
    resolveCompositionPlacement(
      model,
      { kind: 'metadata', metadata: { kind: 'field', fieldId: 'c' } },
      { container: { kind: 'group', groupId: 'empty' }, position: 'inside' },
    ),
  ).toEqual({ container: { kind: 'group', groupId: 'empty' }, index: 0 });
  expect(
    resolveCompositionPlacement(
      model,
      { kind: 'node', container: { kind: 'group', groupId: 'g' }, nodeId: 'b' },
      { container: { kind: 'form' }, anchorId: 'a', position: 'after' },
    )?.index,
  ).toBe(1);
});
it('rejects unrelated child fields, cross-slot moves, stale anchors and self-drops', () => {
  expect(
    resolveCompositionPlacement(
      model,
      { kind: 'metadata', metadata: { kind: 'relationField', relationId: 'other', fieldId: 'x' } },
      { container: { kind: 'relation', relationId: 'child' }, position: 'inside' },
    ),
  ).toBeUndefined();
  expect(
    resolveCompositionPlacement(
      model,
      { kind: 'node', container: { kind: 'list' }, nodeId: 'a' },
      { container: { kind: 'form' }, position: 'inside' },
    ),
  ).toBeUndefined();
  for (const anchorId of ['a', 'missing'])
    expect(
      resolveCompositionPlacement(
        model,
        { kind: 'node', container: { kind: 'list' }, nodeId: 'a' },
        { container: { kind: 'list' }, anchorId, position: 'before' },
      ),
    ).toBeUndefined();
});
