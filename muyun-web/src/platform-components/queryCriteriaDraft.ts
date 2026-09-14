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

/**
 * Mirrors the source-neutral execution limits in QueryCriteria.  The server
 * remains authoritative; keeping the limits here lets the composer identify
 * the affected draft row before it sends a request that must be rejected.
 */
export const QUERY_CRITERIA_MAXIMUM_DEPTH = 4;
export const QUERY_CRITERIA_MAXIMUM_NODES = 50;
export const QUERY_CRITERIA_MAXIMUM_COLLECTION_VALUES = 100;

export function isValueLessQueryOperator(operator: QueryOperator | undefined) {
  return operator === 'NULL' || operator === 'NOT_NULL' || operator === 'EMPTY' || operator === 'NOT_EMPTY';
}
