<script setup lang="ts">
import { computed } from 'vue';
import { resolveDictionaryOptions } from '../dictionaries';
import UiDataTable from './UiDataTable.vue';
import type { UiDataTableColumn, UiDataTablePagination, UiDataTableSelection } from '../types';
import type { RecordData, TableColumn, TableContract } from '@muyun/web-contracts';

defineOptions({ name: 'UiTable', inheritAttrs: false });

const props = defineProps<{
  contract: TableContract;
  rows: RecordData[];
  loading?: boolean;
  pagination?: false | UiDataTablePagination;
  selection?: UiDataTableSelection;
  size?: 'small' | 'middle' | 'large';
  fillHeight?: boolean;
}>();

function renderCell(column: TableColumn, value: unknown) {
  if (!column.dictionaryAlias) {
    return String(value ?? '');
  }

  const option = resolveDictionaryOptions(column.dictionaryAlias).find((item) => item.value === value);
  return option?.label ?? String(value ?? '');
}

function contractColumnOf(column: UiDataTableColumn): TableColumn {
  return (
    props.contract.columns.find((item) => item.key === column.key) ?? {
      key: column.key,
      title: column.title,
      width: typeof column.width === 'number' ? column.width : undefined,
    }
  );
}

const columns = computed(() =>
  props.contract.columns.map<UiDataTableColumn>((column) => ({
    title: column.title,
    key: column.key,
    dataIndex: column.key,
    width: column.width,
  })),
);
</script>

<template>
  <UiDataTable
    :columns="columns"
    :rows="rows"
    :row-key="contract.rowKey ?? 'id'"
    :loading="loading"
    :pagination="pagination ?? { pageSize: 5, showSizeChanger: false }"
    :selection="selection"
    :size="size ?? 'middle'"
    :fill-height="fillHeight"
    horizontal-scroll
    :class="$attrs.class"
    :style="$attrs.style"
  >
    <template #cell="{ column, value }">
      {{ renderCell(contractColumnOf(column), value) }}
    </template>
  </UiDataTable>
</template>
