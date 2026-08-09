import { assert, it } from 'vitest';
import type { WebTreeNode } from '@/web-contracts/index.ts';
import {
  firstConstraintMessage,
  parentRecordConstraints,
  type RecordPickerRecord,
} from '@/platform-components/recordPickerConstraints.ts';
import { flattenTreeRecords } from '@/platform-components/treeRecordModel.ts';

it('parentRecordConstraints prevents selecting current record and descendants', () => {
  const tree: WebTreeNode<RecordPickerRecord>[] = [
    {
      record: { id: 'root', title: '总部' },
      children: [
        {
          record: { id: 'east', parentId: 'root', title: '华东' },
          children: [{ record: { id: 'shanghai', parentId: 'east', title: '上海' }, children: [] }],
        },
        { record: { id: 'west', parentId: 'root', title: '西区' }, children: [] },
      ],
    },
  ];
  const records = flattenTreeRecords(tree);
  const constraints = parentRecordConstraints<RecordPickerRecord>('east');
  const context = { records };

  assert.equal(firstConstraintMessage(records[1], context, constraints), '不能选择当前记录');
  assert.equal(firstConstraintMessage(records[2], context, constraints), '不能选择当前记录的下级');
  assert.equal(firstConstraintMessage(records[0], context, constraints), undefined);
  assert.equal(firstConstraintMessage(records[3], context, constraints), undefined);
});
