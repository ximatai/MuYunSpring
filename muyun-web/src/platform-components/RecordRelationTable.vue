<script setup lang="ts" generic="Row extends Record<string, unknown>">
import { computed } from 'vue';

defineOptions({ name: 'RecordRelationTable' });
const props = withDefaults(
  defineProps<{
    columns: { fieldName: string; title?: string; width?: number; align?: string }[];
    rows: Row[];
    rowKey?: string;
    selection?: boolean;
    density?: 'default' | 'compact';
    cellClass?: (row: Row, column: { fieldName: string }) => string | undefined;
    cellKey?: (row: Row, column: { fieldName: string }) => string | number;
  }>(),
  { rowKey: 'id', selection: false, density: 'default', cellClass: undefined, cellKey: undefined },
);
// Keep the same usable column footprint in business tables and composition previews.
function columnWidth(column: { width?: number }) {
  return column.width != null && Number.isFinite(column.width) && column.width > 0 ? column.width : 160;
}
const tableMinWidth = computed(() =>
  props.columns.reduce((width, column) => width + columnWidth(column), props.selection ? 34 : 0),
);
</script>
<template>
  <section class="managed-relation-inline" :class="`managed-relation-inline--${density}`">
    <div class="managed-relation-inline__scroll">
      <table class="managed-relation-inline__table" :style="{ minWidth: `${tableMinWidth}px` }">
        <colgroup>
          <col v-if="selection" class="managed-relation-inline__selection-column" />
          <col
            v-for="column in columns"
            :key="column.fieldName"
            :style="{ width: `${columnWidth(column)}px` }"
          />
        </colgroup>
        <thead>
          <tr>
            <th v-if="selection" class="managed-relation-inline__selection">
              <slot name="selection-header" />
            </th>
            <th
              v-for="column in columns"
              :key="column.fieldName"
              :style="{
                textAlign: column.align === 'center' || column.align === 'right' ? column.align : 'left',
              }"
            >
              <slot name="header" :column="column">{{ column.title ?? column.fieldName }}</slot>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in rows" :key="String(row[rowKey] ?? index)">
            <td v-if="selection" class="managed-relation-inline__selection">
              <slot name="selection" :row="row" />
            </td>
            <td
              v-for="column in columns"
              :key="cellKey?.(row, column) ?? column.fieldName"
              :class="cellClass?.(row, column)"
              :style="{
                textAlign: column.align === 'center' || column.align === 'right' ? column.align : 'left',
              }"
            >
              <slot name="cell" :row="row" :column="column">
                <span class="managed-relation-inline__value">{{ row[column.fieldName] }}</span>
              </slot>
            </td>
          </tr>
          <tr v-if="rows.length === 0">
            <td :colspan="columns.length + (selection ? 1 : 0)" class="managed-relation-inline__empty">
              <slot name="empty">暂无关联记录</slot>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
<style scoped src="./recordRelationTable.css"></style>
