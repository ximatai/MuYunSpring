import { expect, it } from 'vitest';
import { indexTree, mergeTreePage, treeChanges, treeSnapshot } from '@/vue-ui-antdv/treeStructure';
const node = (key: string, children?: ReturnType<typeof leaf>[]) => ({ key, title: key, children });
const leaf = (key: string) => ({ key, title: key });
it('rejects empty or globally duplicate identities', () => {
  expect(() => indexTree([node('x', [leaf('x')])])).toThrow();
  expect(() => indexTree([leaf('')])).toThrow();
  expect(indexTree([node('parent', [leaf('child')])]).get('child')).toMatchObject({
    parent: 'parent',
    index: 0,
  });
});
it('merges repeated page identities without duplicating or reordering existing nodes', () => {
  expect(mergeTreePage([leaf('a'), leaf('b')], [{ key: 'a', title: 'Updated' }, leaf('c')])).toEqual([
    { key: 'a', title: 'Updated' },
    leaf('b'),
    leaf('c'),
  ]);
  expect(() => mergeTreePage([], [leaf('x'), leaf('x')])).toThrow();
});
it('recognizes all five structural changes separately from visibility', () => {
  const before = treeSnapshot([
    node('parent', [leaf('child')]),
    leaf('gone'),
    leaf('moved'),
    leaf('updated'),
  ]);
  const after = treeSnapshot([
    leaf('moved'),
    node('parent', []),
    leaf('child'),
    { key: 'updated', title: 'New' },
    leaf('new'),
  ]);
  const changes = treeChanges(before, after);
  expect(changes).toEqual(
    expect.arrayContaining([
      { key: 'gone', kind: 'leave' },
      { key: 'new', kind: 'enter' },
      { key: 'child', kind: 'reparent' },
      { key: 'moved', kind: 'move' },
      { key: 'updated', kind: 'update' },
    ]),
  );
  expect(
    treeChanges(
      after,
      treeSnapshot([
        leaf('moved'),
        node('parent', []),
        leaf('child'),
        { key: 'updated', title: 'New' },
        leaf('new'),
      ]),
    ),
  ).toEqual([]);
});
