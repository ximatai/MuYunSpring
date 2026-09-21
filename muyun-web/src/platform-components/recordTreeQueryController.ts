import type { QuerySettlementController } from './querySettlementController';

export interface RecordTreeQueryNode {
  selectionKey: string;
  title: string;
  secondary?: string;
}

export interface RecordTreeQuerySnapshot {
  status: 'loading' | 'ready' | 'error';
  selectedTitle?: string;
  nodes: RecordTreeQueryNode[];
  truncated: boolean;
}

/** Public tree-query port used by orchestration adapters without owning tree state. */
export interface RecordTreeQueryController extends QuerySettlementController<void> {
  snapshot(): RecordTreeQuerySnapshot;
  /** Selects one node through an opaque key from the current snapshot. */
  select(selectionKey: string): RecordTreeQueryNode;
}
