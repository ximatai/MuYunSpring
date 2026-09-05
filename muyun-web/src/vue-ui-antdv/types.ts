import type { RecordInlineAction } from '@muyun/web-contracts';

export type UiDataTableKey = string | number;
export type UiDataTableRecord = Record<string, unknown>;

export interface UiDataTableColumn {
  key: string;
  title: string;
  dataIndex?: string;
  width?: string | number;
  align?: 'left' | 'center' | 'right';
  fixed?: 'left' | 'right' | boolean;
}

export interface UiDataTablePagination {
  pageSize: number;
  current?: number;
  total?: number;
  showSizeChanger?: boolean;
  showQuickJumper?: boolean;
  onChange?: (page: number, pageSize: number) => void;
}

export interface UiDataTableSelection {
  selectedRowKeys: UiDataTableKey[];
  preserveSelectedRowKeys?: boolean;
  disabledOf?: (record: UiDataTableRecord) => boolean;
  onChange?: (keys: UiDataTableKey[]) => void;
}

export interface UiMenuItem {
  key: string;
  title: string;
  disabled?: boolean;
  children?: UiMenuItem[];
}

export interface UiTabItem {
  key: string;
  title: string;
  closable?: boolean;
  pinned?: boolean;
}

export interface UiDropdownItem {
  key: string;
  title: string;
  disabled?: boolean;
  danger?: boolean;
}

/** @deprecated Use the adapter-neutral RecordInlineAction contract. */
export type UiRecordInlineAction = RecordInlineAction;

export type UiTreeNodeAction = UiRecordInlineAction;

export interface UiTreeNode {
  key: string;
  title: string;
  /** False keeps the node expandable before its children are loaded. */
  isLeaf?: boolean;
  disabled?: boolean;
  secondary?: string;
  tag?: string;
  muted?: boolean;
  actions?: UiRecordInlineAction[];
  children?: UiTreeNode[];
}

/** Adapter-neutral node activation event. The node remains a UI contract, not an Ant Tree node. */
export interface UiTreeNodeEvent {
  node: UiTreeNode;
  nativeEvent?: Event;
}

export type UiTreeDisplayMode = 'tree' | 'flat';

export interface UiTreeCheckEvent {
  node: UiTreeNode;
  checked: boolean;
  checkedKeys: string[];
  halfCheckedKeys: string[];
  nativeEvent?: Event;
}

export type UiTreeLoadStrategy = 'managed' | 'controlled';
export type UiTreeLoadReason = 'expand' | 'refresh' | 'load-more';

export interface UiTreeLoadIntent {
  node: UiTreeNode;
  reason: UiTreeLoadReason;
  cursor?: string;
}

/** Only the owner of the request creates and releases its cancellation signal. */
export interface UiTreeLoadRequest extends UiTreeLoadIntent {
  requestId: string;
  signal: AbortSignal;
}

export interface UiTreeLoadResult {
  mode: 'replace' | 'append';
  nodes: UiTreeNode[];
  nextCursor?: string;
  hasMore: boolean;
}

export interface UiTreeBranchState {
  status: 'idle' | 'loading' | 'loaded' | 'error';
  hasMore?: boolean;
  cursor?: string;
  error?: string;
  failedRequest?: UiTreeLoadIntent;
}

export type UiDropPosition = 'before' | 'after' | 'inside';
export type UiDropOperation = 'copy' | 'move';
export interface UiDragSource {
  instanceId: string;
  node: UiTreeNode;
  operations: readonly UiDropOperation[];
  payload?: unknown;
  payloadType?: string;
}
export type UiDropTarget = {
  instanceId: string;
} & ({ kind: 'node'; node: UiTreeNode; position: UiDropPosition } | { kind: 'root'; position: 'inside' });
export interface UiTreeDropEvent {
  source: UiDragSource;
  target: UiDropTarget;
  operation: UiDropOperation;
  nativeEvent?: Event;
}
export type UiTreeChangeReason = 'interaction' | 'filter' | 'reset';

export interface UiConfirmOptions {
  title: string;
  content?: string;
  okText?: string;
  cancelText?: string;
  danger?: boolean;
  requiredText?: string;
}
