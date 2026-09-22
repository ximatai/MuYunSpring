import type { QueryValueType, QueryOperator, QueryCriteriaCondition, WebSort } from '@muyun/web-contracts';
import type { QuerySettlementController } from './querySettlementController';

export interface RecordQueryListQueryField {
  name: string;
  title: string;
  valueType: QueryValueType;
}

export interface RecordQueryListResultRow {
  id?: string;
  cells: Array<{
    fieldName: string;
    title: string;
    value: unknown;
  }>;
}

export interface RecordQueryListQuerySnapshot {
  mode: 'normal' | 'recycleBin';
  status: 'waiting' | 'loading' | 'ready' | 'error';
  quickSearchEnabled: boolean;
  quickSearchFields: RecordQueryListQueryField[];
  appliedQuickSearch?: string;
  pageNum: number;
  pageSize: number;
  total: number;
  totalKnown: boolean;
  rows: RecordQueryListResultRow[];
  truncated: boolean;
  standardQuery?: {
    fields: RecordQueryListFilterField[];
    sorts: WebSort[];
    conditions: QueryCriteriaCondition[];
  };
}

export interface RecordQueryListFilterField extends RecordQueryListQueryField {
  operators: QueryOperator[];
  sortable: boolean;
  options?: Array<string | number | boolean>;
}

export interface RecordQueryListStandardQuery {
  conditions: QueryCriteriaCondition[];
  sorts: WebSort[];
}

/** Public list-query port used by orchestration adapters without owning list state. */
export interface RecordQueryListQueryController extends QuerySettlementController<RecordQueryListQuerySnapshot> {
  /** Stable signature of user-controlled scope, filters, sorting and pagination. */
  interactionRevision?(): string;
  applyStandardQuery?(query: RecordQueryListStandardQuery): Promise<RecordQueryListQuerySnapshot>;
  snapshot(): RecordQueryListQuerySnapshot;
  /** Waits until the current reactive query transition and any active load have settled. */
  applyQuickSearch(keyword: string): Promise<RecordQueryListQuerySnapshot>;
}
