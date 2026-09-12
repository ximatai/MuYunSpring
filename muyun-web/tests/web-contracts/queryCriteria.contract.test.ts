import { assert, it } from 'vitest';
import type {
  QueryCriteriaCondition,
  QueryCriteriaGroup,
  QueryCriteriaNode,
  QueryOperator,
  QuerySchema,
  WebQueryRequest,
  WebReferenceResolveRequest,
} from '@/web-contracts/index.ts';

it('models advanced query criteria as ordered recursive discriminated nodes', () => {
  const tagCondition: QueryCriteriaCondition = {
    kind: 'CONDITION',
    fieldName: 'tags',
    operator: 'CONTAINS_ANY',
    values: ['vip', 'trial'],
  };
  const criteria: QueryCriteriaGroup = {
    kind: 'GROUP',
    operator: 'AND',
    children: [
      { kind: 'CONDITION', fieldName: 'status', operator: 'EQ', values: ['ACTIVE'] },
      {
        kind: 'GROUP',
        operator: 'OR',
        children: [tagCondition, { kind: 'CONDITION', fieldName: 'ownerId', values: ['u-1'] }],
      },
    ],
  };

  const request: WebQueryRequest = { criteria };
  const referenceRequest: WebReferenceResolveRequest = { criteria };
  const nestedChildren: QueryCriteriaNode[] = criteria.children;

  assert.equal(request.criteria?.kind, 'GROUP');
  assert.equal(referenceRequest.criteria?.kind, 'GROUP');
  assert.equal(nestedChildren[1]?.kind, 'GROUP');
});

it('exposes every query operator supported by the backend query schema', () => {
  const collectionOperators: QueryOperator[] = [
    'CONTAINS',
    'CONTAINS_ANY',
    'CONTAINS_ALL',
    'EMPTY',
    'NOT_EMPTY',
  ];

  assert.deepEqual(collectionOperators, ['CONTAINS', 'CONTAINS_ANY', 'CONTAINS_ALL', 'EMPTY', 'NOT_EMPTY']);
});

it('models the server-declared query composition levels without a legacy boolean switch', () => {
  const logSchema: QuerySchema = {
    scopeName: 'platform.login-log',
    quickSearch: { enabled: false, fields: [], fieldSchemas: [] },
    fields: [],
    externalCriteria: [],
    defaultSorts: [],
    criteriaComposition: 'FLAT_AND',
  };

  assert.equal(logSchema.criteriaComposition, 'FLAT_AND');
});
