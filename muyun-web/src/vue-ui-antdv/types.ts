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

export interface UiConfirmOptions {
  title: string;
  content?: string;
  okText?: string;
  cancelText?: string;
  danger?: boolean;
  requiredText?: string;
}
