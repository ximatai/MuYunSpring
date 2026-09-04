import { expect, it } from 'vitest';
import type { MetadataField, ModuleMetadataRelation } from '@/web-contracts';
import {
  buildMetadataModelTree,
  canReorderMetadataModelTree,
  reorderedIds,
  type MetadataModelTreeNode,
} from '@/views/metadataModelTree';

const main: ModuleMetadataRelation = { id: 'main', metadataId: 'metadata-main', relationRole: 'MAIN' };
const child: ModuleMetadataRelation = {
  id: 'child',
  metadataId: 'metadata-child',
  relationRole: 'CHILD',
  parentMetadataId: 'metadata-main',
};

it('projects metadata branches and field leaves directly below the explorer title', () => {
  const tree = buildMetadataModelTree({
    relations: [main, child],
    metadataById: {
      'metadata-main': { id: 'metadata-main', title: '考试', alias: 'exam' },
      'metadata-child': { id: 'metadata-child', title: '参考学生', alias: 'exam_student' },
    },
    fieldsByRelation: {
      main: [{ id: 'title', title: '考试名称', fieldName: 'title' }],
      child: [{ id: 'student', title: '学生', fieldName: 'studentId' }],
    },
    fieldLocked: (_relation, field) => field.id === 'student',
  });

  expect(tree[0]).toMatchObject({ key: 'metadata-model:relation:main', modelKind: 'METADATA' });
  expect(tree[0].children).toEqual([
    expect.objectContaining({ key: 'metadata-model:field:main:title', modelKind: 'FIELD', draggable: true }),
    expect.objectContaining({ key: 'metadata-model:relation:child', modelKind: 'METADATA' }),
  ]);
  expect(tree[0].children?.[1].children?.[0]).toMatchObject({ draggable: false, muted: true });
});

it('admits only same-parent same-kind gap reorders', () => {
  const fieldLeft: MetadataModelTreeNode = {
    key: 'field-left',
    title: '左',
    modelKind: 'FIELD',
    relationId: 'main',
    parentRelationId: 'main',
    draggable: true,
  };
  const fieldRight: MetadataModelTreeNode = {
    key: 'field-right',
    title: '右',
    modelKind: 'FIELD',
    relationId: 'main',
    parentRelationId: 'main',
    draggable: true,
  };
  const metadata: MetadataModelTreeNode = {
    key: 'metadata',
    title: '实体',
    modelKind: 'METADATA',
    relationId: 'child',
    parentRelationId: 'main',
    draggable: true,
  };
  expect(canReorderMetadataModelTree({ dragNode: fieldLeft, dropNode: fieldRight, dropToGap: true })).toBe(
    true,
  );
  expect(canReorderMetadataModelTree({ dragNode: fieldLeft, dropNode: metadata, dropToGap: true })).toBe(
    false,
  );
  expect(canReorderMetadataModelTree({ dragNode: fieldLeft, dropNode: fieldRight, dropToGap: false })).toBe(
    false,
  );
});

it('moves an identifier only within its semantic sibling sequence', () => {
  const fields: MetadataField[] = [{ id: 'first' }, { id: 'second' }, { id: 'third' }];
  expect(reorderedIds(fields, 'third', 'first', -1)).toEqual(['third', 'first', 'second']);
  expect(reorderedIds(fields, 'first', 'third', 1)).toEqual(['second', 'third', 'first']);
});
