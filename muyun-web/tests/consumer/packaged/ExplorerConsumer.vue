<script setup lang="ts">
// Compiled by the installed consumer's vue-tsc; imports must resolve through package exports.
import { ref } from 'vue';
import { UiSearchInput, RecordExplorerPanel } from '@ximatai/muyun-web-app';

const value = ref('');
const keyword = ref('');
const disabled = ref(false);
function search(value: string, source?: 'input' | 'clear') {
  keyword.value = source === 'clear' ? '' : value;
}
function refresh() {
  value.value = keyword.value;
}

// Negative checks ensure generated declarations have not degraded to permissive any types.
type SearchProps = InstanceType<typeof UiSearchInput>['$props'];
type PanelProps = InstanceType<typeof RecordExplorerPanel>['$props'];
type PanelSlots = InstanceType<typeof RecordExplorerPanel>['$slots'];
// @ts-expect-error Search values are strings, not vendor event/value objects.
const invalidValue: SearchProps['value'] = 42;
// @ts-expect-error Search sources are normalized platform values.
const invalidSearch: SearchProps['onSearch'] = (value: string, source?: 'vendor') => {
  void [value, source];
};
// @ts-expect-error Refresh state is a boolean.
const invalidDisabled: PanelProps['refreshDisabled'] = 'disabled';
// @ts-expect-error Controlled keywords are strings.
const invalidKeyword: PanelProps['onUpdate:searchKeyword'] = (value: number) => {
  void value;
};
// @ts-expect-error Named slots have callable Vue slot types.
const invalidSlot: PanelSlots['footer'] = 42;
// @ts-expect-error Vendor props are not part of the public adapter contract.
const vendorProp: SearchProps['enterButton'] = true;
void [invalidValue, invalidSearch, invalidDisabled, invalidKeyword, invalidSlot, vendorProp];
</script>

<template>
  <UiSearchInput v-model:value="value" :disabled="disabled" search-icon-only @search="search" />
  <RecordExplorerPanel
    v-model:search-keyword="keyword"
    title="项目"
    :refresh-disabled="disabled"
    @refresh="refresh"
  >
    <div>项目列表</div>
    <template #footer><span>项目统计</span></template>
  </RecordExplorerPanel>
</template>
