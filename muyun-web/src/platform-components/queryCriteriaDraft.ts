import type { QueryOperator } from '@muyun/web-contracts';

export interface QueryCriteriaConditionDraft {
  kind: 'CONDITION';
  id: number;
  fieldName?: string;
  operator?: QueryOperator;
  values: unknown[];
}

export interface QueryCriteriaGroupDraft {
  kind: 'GROUP';
  id: number;
  operator: 'AND' | 'OR';
  children: QueryCriteriaDraftNode[];
}

export type QueryCriteriaDraftNode = QueryCriteriaConditionDraft | QueryCriteriaGroupDraft;

export function isValueLessQueryOperator(operator: QueryOperator | undefined) {
  return operator === 'NULL' || operator === 'NOT_NULL' || operator === 'EMPTY' || operator === 'NOT_EMPTY';
}
