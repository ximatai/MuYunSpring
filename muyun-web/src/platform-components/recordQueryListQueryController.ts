import type { QueryValueType } from '@muyun/web-contracts';

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
}

/** Public list-query port used by orchestration adapters without owning list state. */
export interface RecordQueryListQueryController {
  revision(): number;
  snapshot(): RecordQueryListQuerySnapshot;
  applyQuickSearch(keyword: string): Promise<RecordQueryListQuerySnapshot>;
}
