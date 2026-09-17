<script setup lang="ts">
import { computed, h, ref } from 'vue';
import { Table as ATable } from 'ant-design-vue';
import UiEmpty from './UiEmpty.vue';
import { resolveUiDataTableScroll } from '../dataTableModel';
import type {
  UiDataTableColumn,
  UiDataTableKey,
  UiDataTablePagination,
  UiDataTableRecord,
  UiDataTableSelection,
} from '../types';
import type { TablePaginationConfig, TableProps } from 'ant-design-vue';

defineOptions({ name: 'UiDataTable', inheritAttrs: false });

const props = withDefaults(
  defineProps<{
    columns: UiDataTableColumn[];
    rows: UiDataTableRecord[];
    rowKey?: string | ((record: UiDataTableRecord) => string);
    loading?: boolean;
    pagination?: false | UiDataTablePagination;
    selection?: UiDataTableSelection;
    size?: 'small' | 'middle' | 'large';
    selectedRowKey?: string;
    expandedRowKeys?: string[];
    clickableRows?: boolean;
    fillHeight?: boolean;
    horizontalScroll?: boolean;
    rowMuted?: (record: UiDataTableRecord) => boolean;
    showActionColumn?: boolean;
    actionColumnTitle?: string;
    actionColumnWidth?: string | number;
    /** Compact embedded lists can keep actions in flow instead of forcing horizontal scrolling. */
    actionColumnFixed?: boolean;
    /** Enables the adapter's generic row-reorder interaction. The owner decides persistence. */
    rowDraggable?: boolean;
    rowDragHandleTitle?: string;
    emptyDescription?: string;
  }>(),
  {
    rowKey: 'id',
    loading: false,
    pagination: false,
    selection: undefined,
    size: 'middle',
    selectedRowKey: undefined,
    expandedRowKeys: () => [],
    clickableRows: false,
    fillHeight: false,
    horizontalScroll: false,
    rowMuted: undefined,
    showActionColumn: false,
    actionColumnTitle: '操作',
    actionColumnWidth: 92,
    actionColumnFixed: true,
    rowDraggable: false,
    rowDragHandleTitle: '拖拽排序',
    emptyDescription: '暂无记录',
  },
);

const emit = defineEmits<{
  rowClick: [record: UiDataTableRecord, event: MouseEvent];
  rowDblclick: [record: UiDataTableRecord, event: MouseEvent];
  rowExpand: [record: UiDataTableRecord, expanded: boolean];
  rowDrop: [event: { source: UiDataTableRecord; target: UiDataTableRecord; position: 'before' | 'after' }];
}>();

const slots = defineSlots<{
  header?: (props: { column: UiDataTableColumn }) => unknown;
  cell?: (props: { column: UiDataTableColumn; record: UiDataTableRecord; value: unknown }) => unknown;
  rowActions?: (props: { record: UiDataTableRecord; rowKey: string }) => unknown;
  expandedRow?: (props: { record: UiDataTableRecord; rowKey: string }) => unknown;
}>();

const tableColumns = computed(() => {
  const columns = props.columns.map((column) => ({
    title: slots.header ? () => h('span', slots.header?.({ column }) ?? column.title) : column.title,
    dataIndex: column.dataIndex ?? column.key,
    key: column.key,
    width: column.width,
    align: column.align,
    fixed: normalizedFixed(column.fixed),
    customRender: ({ record, text }: { record: UiDataTableRecord; text: unknown }) =>
      slots.cell?.({ column, record, value: text }) ?? String(text ?? ''),
  }));
  const draggableColumn = props.rowDraggable
    ? [
        {
          title: '',
          key: '__row-drag',
          width: 38,
          align: 'center' as const,
          className: 'ui-data-table-drag-cell',
          customRender: ({ record }: { record: UiDataTableRecord }) =>
            h(
              'span',
              {
                class: 'ui-data-table-drag-handle',
                draggable: canDragRow(record),
                role: 'img',
                'aria-label': props.rowDragHandleTitle,
                title: props.rowDragHandleTitle,
                onDragstart: (event: DragEvent) => handleDragStart(record, event),
                onDragend: handleDragEnd,
              },
              '⠿',
            ),
        },
      ]
    : [];
  const actionColumn = !props.showActionColumn
    ? []
    : [
        {
          title: props.actionColumnTitle,
          key: '__actions',
          width: props.actionColumnWidth,
          align: 'right' as const,
          fixed: props.actionColumnFixed ? ('right' as const) : undefined,
          className: 'ui-data-table-action-cell',
          customRender: ({ record }: { record: UiDataTableRecord }) =>
            slots.rowActions?.({ record, rowKey: resolveRowKey(record) }),
        },
      ];
  return [...draggableColumn, ...columns, ...actionColumn];
});

const tablePagination = computed<TablePaginationConfig | false>(() =>
  props.pagination === false ? false : { ...props.pagination },
);

const tableSelection = computed<TableProps['rowSelection']>(() => {
  if (!props.selection) {
    return undefined;
  }
  return {
    selectedRowKeys: props.selection.selectedRowKeys,
    preserveSelectedRowKeys: props.selection.preserveSelectedRowKeys,
    getCheckboxProps: (record: UiDataTableRecord) => ({ disabled: props.selection?.disabledOf?.(record) }),
    onChange: (keys) => props.selection?.onChange?.(keys as UiDataTableKey[]),
  };
});

const tableScroll = computed<TableProps['scroll']>(() =>
  resolveUiDataTableScroll({
    horizontal: props.horizontalScroll,
    fillHeight: props.fillHeight,
    hasFixedColumn: tableColumns.value.some((column) => 'fixed' in column && Boolean(column.fixed)),
  }),
);

function normalizedFixed(fixed: UiDataTableColumn['fixed']) {
  return fixed === true ? 'left' : fixed || undefined;
}

function resolveRowKey(record: UiDataTableRecord) {
  if (typeof props.rowKey === 'function') {
    return props.rowKey(record);
  }
  return String(record[props.rowKey] ?? record.id ?? '');
}

function rowClassName(record: UiDataTableRecord) {
  const classes: string[] = [];
  if (props.selectedRowKey && resolveRowKey(record) === props.selectedRowKey) {
    classes.push('selected');
  }
  if (props.rowMuted?.(record)) {
    classes.push('muted');
  }
  if (draggingRowKey.value === resolveRowKey(record)) {
    classes.push('dragging');
  }
  if (dragOverRowKey.value === resolveRowKey(record)) {
    classes.push(`drag-over-${dragOverPosition.value}`);
  }
  return classes.join(' ');
}

function customRow(record: UiDataTableRecord) {
  const row: Record<string, unknown> = {};
  if (props.clickableRows) {
    row.onClick = (event: MouseEvent) => {
      if (isExpandTriggerEvent(event) || isRowDragHandleEvent(event)) {
        return;
      }
      emit('rowClick', record, event);
    };
    row.onDblclick = (event: MouseEvent) => {
      if (isExpandTriggerEvent(event) || isRowDragHandleEvent(event)) {
        return;
      }
      emit('rowDblclick', record, event);
    };
  }
  if (props.rowDraggable) {
    row.onDragover = (event: DragEvent) => handleDragOver(record, event);
    row.onDragleave = () => handleDragLeave(record);
    row.onDrop = (event: DragEvent) => handleDrop(record, event);
  }
  return row;
}

function handleExpand(expanded: boolean, record: UiDataTableRecord) {
  emit('rowExpand', record, expanded);
}

function isExpandTriggerEvent(event: MouseEvent) {
  const target = event.target;
  return target instanceof Element && Boolean(target.closest('.ant-table-row-expand-icon'));
}

const draggingRecord = ref<UiDataTableRecord>();
const draggingRowKey = computed(() =>
  draggingRecord.value ? resolveRowKey(draggingRecord.value) : undefined,
);
const dragOverRowKey = ref<string>();
const dragOverPosition = ref<'before' | 'after'>('before');

function canDragRow(record: UiDataTableRecord) {
  return props.rowDraggable && Boolean(resolveRowKey(record));
}

function handleDragStart(record: UiDataTableRecord, event: DragEvent) {
  if (!canDragRow(record)) return;
  draggingRecord.value = record;
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('text/plain', resolveRowKey(record));
  }
}

function handleDragEnd() {
  draggingRecord.value = undefined;
  dragOverRowKey.value = undefined;
}

function handleDragOver(record: UiDataTableRecord, event: DragEvent) {
  if (!draggingRecord.value || !canDragRow(record)) return;
  if (resolveRowKey(draggingRecord.value) === resolveRowKey(record)) return;
  event.preventDefault();
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move';
  dragOverRowKey.value = resolveRowKey(record);
  dragOverPosition.value = dropPositionOf(event);
}

function handleDragLeave(record: UiDataTableRecord) {
  if (dragOverRowKey.value === resolveRowKey(record)) {
    dragOverRowKey.value = undefined;
  }
}

function handleDrop(target: UiDataTableRecord, event: DragEvent) {
  const source = draggingRecord.value;
  event.preventDefault();
  handleDragEnd();
  if (!source || resolveRowKey(source) === resolveRowKey(target)) return;
  emit('rowDrop', { source, target, position: dropPositionOf(event) });
}

function dropPositionOf(event: DragEvent): 'before' | 'after' {
  const row = event.currentTarget;
  if (!(row instanceof HTMLElement)) return 'before';
  const { top, height } = row.getBoundingClientRect();
  return event.clientY - top > height / 2 ? 'after' : 'before';
}

function isRowDragHandleEvent(event: MouseEvent) {
  const target = event.target;
  return target instanceof Element && Boolean(target.closest('.ui-data-table-drag-handle'));
}
</script>

<template>
  <ATable
    :class="['ui-data-table', $attrs.class, { 'is-clickable': clickableRows, 'is-fill-height': fillHeight }]"
    :style="$attrs.style"
    :columns="tableColumns"
    :data-source="rows"
    :row-key="rowKey"
    :loading="loading"
    :pagination="tablePagination"
    :row-selection="tableSelection"
    :size="size"
    :scroll="tableScroll"
    :custom-row="customRow"
    :row-class-name="rowClassName"
    :expanded-row-keys="expandedRowKeys"
    @expand="handleExpand"
  >
    <template #emptyText>
      <UiEmpty :description="emptyDescription" />
    </template>
    <template v-if="$slots.expandedRow" #expandedRowRender="{ record }">
      <slot name="expandedRow" :record="record" :row-key="resolveRowKey(record)" />
    </template>
  </ATable>
</template>

<style scoped>
.ui-data-table {
  min-width: 0;
}

.ui-data-table.is-fill-height {
  display: flex;
  min-height: 0;
  height: 100%;
  flex-direction: column;
}

.ui-data-table.is-fill-height :deep(.ant-spin-nested-loading) {
  flex: 1 1 auto;
  min-height: 0;
}

.ui-data-table.is-fill-height :deep(.ant-spin-container) {
  min-height: 0;
  height: 100%;
}

.ui-data-table.is-fill-height :deep(.ant-spin-container),
.ui-data-table.is-fill-height :deep(.ant-table) {
  display: flex;
  flex-direction: column;
}

.ui-data-table.is-fill-height :deep(.ant-table) {
  flex: 1 1 auto;
  min-height: 0;
  height: auto;
}

.ui-data-table.is-fill-height :deep(.ant-table-container) {
  min-height: 0;
  height: 100%;
}

.ui-data-table.is-fill-height :deep(.ant-table-container) {
  display: flex;
  flex-direction: column;
}

.ui-data-table.is-fill-height :deep(.ant-table-header) {
  flex: 0 0 auto;
}

.ui-data-table.is-fill-height :deep(.ant-table-body) {
  flex: 1 1 auto;
  min-height: 0;
  max-height: none !important;
  overflow-y: auto !important;
}

.ui-data-table :deep(.ant-table) {
  color: var(--muyun-text-body);
  font-size: 13px;
  line-height: 1.5714285714;
}

/*
 * The native table element can otherwise retain a consumer page's inherited
 * typography even when the Ant table wrapper has the platform scale. Keep
 * table header and body text on the same adapter contract in every host.
 */
.ui-data-table :deep(.ant-table-thead > tr > th),
.ui-data-table :deep(.ant-table-tbody > tr > td) {
  font-size: 13px;
  line-height: 1.5714285714;
}

.ui-data-table :deep(.ant-table-thead > tr > th) {
  /* Keep adapter density above Ant's generated physical padding shorthand. */
  padding-block: 8px !important;
  background: var(--muyun-hover-subtle);
  color: var(--muyun-text);
  font-weight: 700;
}

.ui-data-table :deep(.ant-table-tbody > tr > td) {
  padding-block: 6px !important;
}

.ui-data-table :deep(.ant-table-tbody > tr.ant-table-measure-row > td) {
  padding-block: 0 !important;
}

.ui-data-table :deep(.ant-table-thead > tr > th.ui-data-table-action-cell) {
  text-align: center !important;
}

.ui-data-table :deep(.ant-table-thead > tr > th.ui-data-table-drag-cell),
.ui-data-table :deep(.ant-table-tbody > tr > td.ui-data-table-drag-cell) {
  padding-inline: 6px !important;
}

.ui-data-table-drag-handle {
  display: inline-grid;
  width: 22px;
  height: 22px;
  place-items: center;
  border-radius: 4px;
  color: var(--muyun-text-muted);
  cursor: grab;
  font-size: 17px;
  line-height: 1;
  letter-spacing: -3px;
  user-select: none;
}

.ui-data-table-drag-handle:hover {
  background: var(--muyun-hover-subtle);
  color: var(--muyun-theme-base);
}

.ui-data-table-drag-handle:active {
  cursor: grabbing;
}

.ui-data-table :deep(.ant-table-tbody > tr.dragging > td) {
  opacity: 0.52;
}

.ui-data-table :deep(.ant-table-tbody > tr.drag-over-before > td) {
  box-shadow: inset 0 2px 0 var(--muyun-theme-base);
}

.ui-data-table :deep(.ant-table-tbody > tr.drag-over-after > td) {
  box-shadow: inset 0 -2px 0 var(--muyun-theme-base);
}

.ui-data-table.is-clickable :deep(.ant-table-tbody > tr) {
  cursor: pointer;
}

.ui-data-table :deep(.ant-table-tbody > tr.selected > td) {
  background: var(--muyun-selected);
}

.ui-data-table :deep(.ant-table-tbody > tr.muted > td) {
  color: var(--muyun-text-muted);
}

.ui-data-table :deep(.ant-table-row-expand-icon) {
  display: inline-grid;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  place-items: center;
  background: transparent;
  color: var(--muyun-text-muted);
  transform: none;
  transition:
    color 160ms ease,
    background-color 160ms ease;
}

.ui-data-table :deep(.ant-table-row-expand-icon::before) {
  position: static;
  width: 6px;
  height: 6px;
  border-right: 1.5px solid currentcolor;
  border-bottom: 1.5px solid currentcolor;
  background: transparent;
  transform: rotate(-45deg);
  transition: transform 160ms ease;
}

.ui-data-table :deep(.ant-table-row-expand-icon::after) {
  display: none;
}

.ui-data-table :deep(.ant-table-row-expand-icon-expanded::before) {
  transform: rotate(45deg);
}

.ui-data-table :deep(.ant-table-row-expand-icon:hover) {
  background: var(--muyun-hover-subtle);
  color: var(--muyun-theme-base);
}

.ui-data-table :deep(.ant-table-row-expand-icon:focus-visible) {
  outline: 2px solid var(--muyun-theme-focus);
  outline-offset: 1px;
}

.ui-data-table :deep(.ant-table-cell-fix-right),
.ui-data-table :deep(.ant-table-cell-fix-right-first) {
  background: var(--muyun-surface);
}

.ui-data-table :deep(.ant-table-tbody > tr:hover > .ant-table-cell-fix-right),
.ui-data-table :deep(.ant-table-tbody > tr:hover > .ant-table-cell-fix-right-first) {
  background: var(--muyun-hover);
}

.ui-data-table :deep(.ant-table-tbody > tr.selected > .ant-table-cell-fix-right),
.ui-data-table :deep(.ant-table-tbody > tr.selected > .ant-table-cell-fix-right-first) {
  background: var(--muyun-selected);
}

.ui-data-table :deep(.ant-table-expanded-row-fixed) {
  position: static !important;
  left: auto !important;
  width: auto !important;
  overflow: visible !important;
}
</style>
