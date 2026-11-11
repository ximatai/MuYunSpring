<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useLanguageContext } from '@muyun/web-core';
import { UiButton, UiDataTable, UiInput, type UiDataTablePagination } from '@muyun/vue-ui-antdv';
import { referencePickerDisplayTitle, type ReferencePickerCandidate } from './referencePickerModel';

const props = withDefaults(
  defineProps<{
    candidates: readonly ReferencePickerCandidate[];
    disabled?: boolean;
    allowClear?: boolean;
    selectionNoun?: string;
  }>(),
  { disabled: false, allowClear: true, selectionNoun: undefined },
);
const emit = defineEmits<{ remove: [id: string]; clear: [] }>();
const language = useLanguageContext();
const keyword = ref('');
const currentPage = ref(1);
const pageSize = 8;
const rows = computed(() => {
  const labels = props.candidates.map((candidate) => referencePickerDisplayTitle(candidate, language));
  const counts = new Map<string, number>();
  for (const label of labels) counts.set(label, (counts.get(label) ?? 0) + 1);
  return props.candidates.map((candidate, index) => {
    const label = labels[index]!;
    return {
      id: candidate.id,
      label,
      title: candidate.unavailable
        ? language.t('platform.reference-picker.unavailable', '{title}（不可用）', { title: label })
        : label,
      identity: counts.get(label)! > 1 && label !== candidate.id ? candidate.id : '',
    };
  });
});
const filteredRows = computed(() => {
  const query = keyword.value.trim().toLocaleLowerCase();
  return rows.value.filter((row) => !query || `${row.label} ${row.id}`.toLocaleLowerCase().includes(query));
});
const pagination = computed<false | UiDataTablePagination>(() =>
  filteredRows.value.length <= pageSize
    ? false
    : {
        current: currentPage.value,
        pageSize,
        total: filteredRows.value.length,
        showSizeChanger: false,
        showQuickJumper: false,
        onChange: (page) => {
          currentPage.value = page;
        },
      },
);
watch(keyword, () => {
  currentPage.value = 1;
});
watch(
  () => filteredRows.value.length,
  (length) => {
    currentPage.value = Math.min(currentPage.value, Math.max(1, Math.ceil(length / pageSize)));
  },
);
const heading = computed(() =>
  language.t('platform.reference-picker.draft-selected', '已选 {count} 个{noun}', {
    count: props.candidates.length,
    noun: props.selectionNoun ?? language.t('platform.reference-picker.noun', '记录'),
  }),
);
</script>

<template>
  <section class="reference-selection-panel" :aria-label="heading">
    <div class="reference-selection-heading">
      <strong>{{ heading }}</strong>
      <UiButton
        v-if="candidates.length && allowClear"
        type="link"
        size="small"
        :disabled="disabled"
        @click="emit('clear')"
      >
        {{ language.t('platform.reference-picker.clear', '清空选择') }}
      </UiButton>
    </div>
    <UiInput
      :value="keyword"
      :placeholder="language.t('platform.reference-picker.search-selected', '搜索已选内容')"
      :aria-label="language.t('platform.reference-picker.search-selected', '搜索已选内容')"
      allow-clear
      @update:value="keyword = String($event ?? '')"
    />
    <UiDataTable
      :columns="[{ key: 'title', title: language.t('platform.reference-picker.name', '名称') }]"
      :rows="filteredRows"
      :pagination="pagination"
      size="small"
      show-action-column
      :action-column-fixed="false"
      :action-column-width="64"
      :empty-description="
        keyword
          ? language.t('platform.reference-picker.selected-no-match', '没有匹配的已选内容')
          : language.t('platform.reference-picker.unselected', '尚未选择')
      "
    >
      <template #cell="{ record }">
        <div class="reference-selection-label" :title="String(record.title)">{{ record.title }}</div>
        <small v-if="record.identity" class="reference-selection-identity">{{ record.identity }}</small>
      </template>
      <template #rowActions="{ record }">
        <UiButton
          type="text"
          size="small"
          :disabled="disabled"
          :aria-label="
            language.t('platform.reference-picker.remove-selected', '移除 {title}', {
              title: String(record.title),
            })
          "
          @click="emit('remove', String(record.id))"
          >{{ language.t('platform.reference-picker.remove', '移除') }}</UiButton
        >
      </template>
    </UiDataTable>
  </section>
</template>

<style scoped>
.reference-selection-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}
.reference-selection-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 32px;
}
.reference-selection-label {
  overflow-wrap: anywhere;
  white-space: normal;
}
.reference-selection-identity {
  color: var(--muyun-color-text-secondary);
  overflow-wrap: anywhere;
}
</style>
