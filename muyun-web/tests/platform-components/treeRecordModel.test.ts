import { assert, it } from 'vitest';
import type { WebTreeNode } from '@/web-contracts/index.ts';
import {
  defaultTreeRecordMatches,
  defaultTreeRecordTitle,
  expandAllTreeRecords,
  filterTreeRecords,
  firstTwoTreeLevels,
  flattenTreeRecords,
  type TreeRecordBase,
} from '@/platform-components/treeRecordModel.ts';

it('tree record model filters matched nodes with ancestors', () => {
  const tree = sampleTree();

  const filtered = filterTreeRecords(tree, '上海', defaultTreeRecordMatches);

  assert.deepEqual(
    flattenTreeRecords(filtered).map((record) => record.id),
    ['root', 'east', 'shanghai'],
  );
});

it('tree record model builds stable titles and expansion keys', () => {
  const tree = sampleTree();

  assert.equal(defaultTreeRecordTitle({ code: 'NO_TITLE' }), 'NO_TITLE');
  assert.deepEqual(firstTwoTreeLevels(tree), ['root', 'east', 'west']);
  assert.deepEqual(expandAllTreeRecords(tree[0]), ['root', 'east', 'shanghai', 'west']);
});

function sampleTree(): WebTreeNode<TreeRecordBase>[] {
  return [
    {
      record: { id: 'root', title: '总部' },
      children: [
        {
          record: { id: 'east', title: '华东' },
          children: [{ record: { id: 'shanghai', title: '上海' }, children: [] }],
        },
        { record: { id: 'west', title: '西区' }, children: [] },
      ],
    },
  ];
}
