import { expect, it } from 'vitest';
import { conjoinQueryCriteria, queryCriteriaComplexity } from '@/platform-components/queryCriteria';
import type { QueryCriteriaGroup, QueryCriteriaNode } from '@/web-contracts';
const condition: QueryCriteriaNode = {
  kind: 'CONDITION',
  fieldName: 'state',
  operator: 'EQ',
  values: ['OPEN'],
};
function nested(depth: number): QueryCriteriaGroup {
  return { kind: 'GROUP', operator: 'OR', children: [depth === 1 ? condition : nested(depth - 1)] };
}
it('keeps a maximum-depth standalone tree valid without adding a root', () => {
  const tree = nested(4);
  expect(conjoinQueryCriteria([tree])).toBe(tree);
  expect(queryCriteriaComplexity(conjoinQueryCriteria([tree]))).toBeUndefined();
  expect(queryCriteriaComplexity(conjoinQueryCriteria([condition, tree]))).toBe('depth');
});
it('keeps a maximum-node tree valid and counts other criteria in the final budget', () => {
  const tree: QueryCriteriaGroup = {
    kind: 'GROUP',
    operator: 'AND',
    children: Array.from({ length: 49 }, () => condition),
  };
  expect(queryCriteriaComplexity(conjoinQueryCriteria([tree]))).toBeUndefined();
  expect(queryCriteriaComplexity(conjoinQueryCriteria([tree, condition]))).toBe('nodes');
});
it('flattens conjunction only, preserving disjunction and input ownership', () => {
  const disjunction = nested(1);
  const tree: QueryCriteriaGroup = { kind: 'GROUP', operator: 'AND', children: [condition, disjunction] };
  expect(conjoinQueryCriteria([tree, condition])).toEqual({
    kind: 'GROUP',
    operator: 'AND',
    children: [condition, disjunction, condition],
  });
  expect(tree.children).toHaveLength(2);
  expect(conjoinQueryCriteria([undefined])).toBeUndefined();
});
