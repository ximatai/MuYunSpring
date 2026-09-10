<script setup lang="ts">
import { computed } from 'vue';
import { UiButton, UiDataTable, UiSearchInput, UiSelect } from '@muyun/vue-ui-antdv';
import type {
  UiDataTableColumn,
  UiDataTableRecord,
  UiDataTableSelection,
  UiIconName,
} from '@muyun/vue-ui-antdv';
import type { Option, OptionValue, OptionValueList } from '@muyun/web-contracts';
import ManagementPanelHeader from './ManagementPanelHeader.vue';

defineOptions({ name: 'RecordQueryListSurface' });

const props = withDefaults(
  defineProps<{
    title?: string;
    subtitle?: string;
    headerVisible?: boolean;
    showTitle?: boolean;
    titleActionIcon?: UiIconName;
    titleActionTitle?: string;
    titleActionDisabled?: boolean;
    quickSearchVisible?: boolean;
    quickSearchValue?: string;
    quickSearchPlaceholder?: string;
    quickSearchDisabled?: boolean;
    columns: UiDataTableColumn[];
    rows: UiDataTableRecord[];
    rowKey?: string | ((record: UiDataTableRecord) => string);
    selection?: UiDataTableSelection;
    selectedRowKey?: string;
    expandedRowKeys?: string[];
    clickableRows?: boolean;
    fillHeight?: boolean;
    horizontalScroll?: boolean;
    rowMuted?: (record: UiDataTableRecord) => boolean;
    showActionColumn?: boolean;
    actionColumnTitle?: string;
    actionColumnWidth?: string | number;
    actionColumnFixed?: boolean;
    tableVisible?: boolean;
    embedded?: boolean;
    chromeFree?: boolean;
    /** The surface renders pagination only; its owner keeps query state and data loading. */
    pageable?: boolean;
    total?: number;
    pageNum?: number;
    pages?: number;
    pageSize?: number;
    pageSizeOptions?: number[];
    paginationDisabled?: boolean;
  }>(),
  {
    title: '',
    subtitle: undefined,
    headerVisible: true,
    showTitle: true,
    titleActionIcon: undefined,
    titleActionTitle: undefined,
    titleActionDisabled: false,
    quickSearchVisible: false,
    quickSearchValue: '',
    quickSearchPlaceholder: undefined,
    quickSearchDisabled: false,
    rowKey: 'id',
    selection: undefined,
    selectedRowKey: undefined,
    expandedRowKeys: () => [],
    clickableRows: false,
    fillHeight: true,
    horizontalScroll: true,
    rowMuted: undefined,
    showActionColumn: false,
    actionColumnTitle: undefined,
    actionColumnWidth: undefined,
    actionColumnFixed: true,
    tableVisible: true,
    embedded: false,
    chromeFree: false,
    pageable: false,
    total: 0,
    pageNum: 1,
    pages: 1,
    pageSize: 20,
    pageSizeOptions: () => [10, 20, 50],
    paginationDisabled: false,
  },
);

const emit = defineEmits<{
  titleAction: [];
  'update:quickSearchValue': [value: string];
  quickSearch: [value: string];
  rowClick: [record: UiDataTableRecord, event: MouseEvent];
  rowDblclick: [record: UiDataTableRecord, event: MouseEvent];
  rowExpand: [record: UiDataTableRecord, expanded: boolean];
  pageChange: [pageNum: number];
  pageSizeChange: [pageSize: number];
}>();

const pageSizeOptions = computed<Option[]>(() =>
  props.pageSizeOptions.map((value) => ({ label: `${value} 条/页`, value })),
);

function handlePageSizeChange(value: OptionValue | OptionValueList | null) {
  const pageSize = Array.isArray(value) ? undefined : value;
  const nextPageSize = typeof pageSize === 'number' ? pageSize : Number(pageSize ?? props.pageSize);
  emit('pageSizeChange', nextPageSize);
}

defineSlots<{
  operations?: () => unknown;
  queryControls?: () => unknown;
  conditions?: () => unknown;
  beforeTable?: () => unknown;
  header?: (props: { column: UiDataTableColumn }) => unknown;
  cell?: (props: { column: UiDataTableColumn; record: UiDataTableRecord; value: unknown }) => unknown;
  rowActions?: (props: { record: UiDataTableRecord; rowKey: string }) => unknown;
  expandedRow?: (props: { record: UiDataTableRecord; rowKey: string }) => unknown;
  footer?: () => unknown;
}>();
</script>

<template>
  <main
    class="record-query-list-surface"
    :class="{
      'is-embedded': embedded,
      'is-chrome-free': chromeFree,
    }"
  >
    <ManagementPanelHeader
      v-if="headerVisible"
      class="record-query-list-header"
      :title="showTitle ? title : ''"
      :subtitle="showTitle ? subtitle : undefined"
      :title-action-icon="showTitle ? titleActionIcon : undefined"
      :title-action-title="showTitle ? titleActionTitle : undefined"
      :title-action-disabled="titleActionDisabled"
      @title-action="emit('titleAction')"
    >
      <template #actions>
        <div class="record-query-list-actions">
          <div class="record-query-list-operation-actions"><slot name="operations" /></div>
          <div class="record-query-list-query-actions">
            <UiSearchInput
              v-if="quickSearchVisible"
              :value="quickSearchValue"
              class="record-query-list-search"
              :disabled="quickSearchDisabled"
              :placeholder="quickSearchPlaceholder"
              @update:value="emit('update:quickSearchValue', $event)"
              @search="emit('quickSearch', $event)"
            />
            <slot name="queryControls" />
          </div>
        </div>
      </template>
    </ManagementPanelHeader>

    <section v-if="$slots.conditions" class="record-query-list-conditions">
      <slot name="conditions" />
    </section>

    <section class="record-query-list-body">
      <slot name="beforeTable" />
      <UiDataTable
        v-if="tableVisible"
        class="record-query-list-table"
        :columns="columns"
        :rows="rows"
        :row-key="rowKey"
        :pagination="false"
        :selection="selection"
        :selected-row-key="selectedRowKey"
        :expanded-row-keys="expandedRowKeys"
        :clickable-rows="clickableRows"
        :fill-height="fillHeight"
        :horizontal-scroll="horizontalScroll"
        :row-muted="rowMuted"
        :show-action-column="showActionColumn"
        :action-column-title="actionColumnTitle"
        :action-column-width="actionColumnWidth"
        :action-column-fixed="actionColumnFixed"
        @row-click="(record, event) => emit('rowClick', record, event)"
        @row-dblclick="(record, event) => emit('rowDblclick', record, event)"
        @row-expand="(record, expanded) => emit('rowExpand', record, expanded)"
      >
        <template v-if="$slots.header" #header="slotProps"
          ><slot name="header" v-bind="slotProps"
        /></template>
        <template v-if="$slots.cell" #cell="slotProps"><slot name="cell" v-bind="slotProps" /></template>
        <template v-if="$slots.rowActions" #rowActions="slotProps"
          ><slot name="rowActions" v-bind="slotProps"
        /></template>
        <template v-if="$slots.expandedRow" #expandedRow="slotProps"
          ><slot name="expandedRow" v-bind="slotProps"
        /></template>
      </UiDataTable>
    </section>

    <footer v-if="$slots.footer || pageable" class="record-query-list-pagination">
      <div v-if="$slots.footer" class="record-query-list-footer-extensions"><slot name="footer" /></div>
      <div v-if="pageable" class="record-query-list-pagination-controls">
        <span>共 {{ total }} 条</span>
        <UiSelect
          class="record-query-list-page-size"
          :value="pageSize"
          :options="pageSizeOptions"
          :allow-clear="false"
          :disabled="paginationDisabled"
          @update:value="handlePageSizeChange"
        />
        <span class="record-query-list-page-navigation">
          <UiButton
            aria-label="上一页"
            title="上一页"
            icon-name="left"
            :disabled="paginationDisabled || pageNum <= 1"
            @click="emit('pageChange', pageNum - 1)"
          />
          <span>第 {{ pageNum }} / {{ pages }} 页</span>
          <UiButton
            aria-label="下一页"
            title="下一页"
            icon-name="right"
            :disabled="paginationDisabled || pageNum >= pages"
            @click="emit('pageChange', pageNum + 1)"
          />
        </span>
      </div>
    </footer>
  </main>
</template>

<style scoped>
.record-query-list-surface {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  grid-template-areas:
    'header'
    'conditions'
    'body'
    'pagination';
  align-content: stretch;
  gap: var(--muyun-management-panel-content-gap, 8px);
  min-width: 0;
  min-height: 0;
  height: 100%;
  padding: var(--muyun-management-panel-padding-block, 10px)
    var(--muyun-management-panel-padding-inline, 12px);
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
  background: var(--muyun-surface);
}

.record-query-list-surface.is-embedded {
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
}

.record-query-list-surface.is-chrome-free {
  grid-template-rows: minmax(0, 1fr);
  grid-template-areas: 'body';
  gap: 0;
}

.record-query-list-surface.is-embedded .record-query-list-table {
  border-radius: 0;
}

.record-query-list-header {
  grid-area: header;
}

.record-query-list-actions,
.record-query-list-operation-actions,
.record-query-list-query-actions,
.record-query-list-pagination,
.record-query-list-pagination-controls,
.record-query-list-footer-extensions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.record-query-list-actions {
  flex: 0 1 auto;
  margin-left: auto;
  justify-content: flex-end;
  gap: var(--muyun-management-panel-header-gap, 8px);
}

.record-query-list-operation-actions {
  flex: 0 0 auto;
}

.record-query-list-query-actions {
  flex: 1 1 auto;
  justify-content: flex-end;
  flex-wrap: wrap;
}

.record-query-list-search {
  flex: 0 1 clamp(150px, 20vw, 220px);
  width: clamp(150px, 20vw, 220px);
}

.record-query-list-conditions {
  grid-area: conditions;
}

.record-query-list-body {
  grid-area: body;
  display: grid;
  min-height: 0;
}

.record-query-list-table {
  min-height: 0;
  height: 100%;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 8px;
  overflow: hidden;
}

.record-query-list-pagination {
  grid-area: pagination;
  justify-content: space-between;
  flex-wrap: wrap;
  color: var(--muyun-text-muted);
  font-size: 13px;
}

.record-query-list-footer-extensions {
  flex: 0 1 auto;
}

.record-query-list-pagination-controls {
  flex: 0 1 auto;
  flex-wrap: wrap;
  justify-content: flex-end;
  max-width: 100%;
  margin-left: auto;
}

.record-query-list-page-navigation {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
}

.record-query-list-page-size {
  flex: 0 0 112px;
  width: 112px;
  max-width: 100%;
  min-width: 0;
}

@media (max-width: 680px) {
  .record-query-list-header {
    flex-direction: column;
    align-items: stretch;
  }

  .record-query-list-actions {
    width: 100%;
    flex-wrap: wrap;
    justify-content: flex-start;
  }

  .record-query-list-query-actions {
    flex: 1 1 100%;
    justify-content: flex-start;
  }

  .record-query-list-search {
    flex: 0 1 220px;
    width: min(220px, 100%);
  }
}
</style>
