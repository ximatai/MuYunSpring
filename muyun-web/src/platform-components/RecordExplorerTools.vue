<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import { UiButton } from '@muyun/vue-ui-antdv';

const language = useLanguageContext();

defineProps<{
  title: string;
  searchable: boolean;
  searchExpanded: boolean;
  refreshable: boolean;
  refreshDisabled: boolean;
  refreshTitle: string;
}>();
const emit = defineEmits<{ search: []; refresh: [] }>();
</script>

<template>
  <div class="record-explorer-tools">
    <slot />
    <UiButton
      v-if="searchable"
      icon-name="search"
      icon-only
      size="small"
      type="text"
      :selected="searchExpanded"
      :title="
        language.t('platform.screen.RecordExplorerTools.search-value', '搜索{value1}', { value1: title })
      "
      @click="emit('search')"
    />
    <UiButton
      v-if="refreshable"
      icon-name="reload"
      icon-only
      size="small"
      type="text"
      :disabled="refreshDisabled"
      :title="refreshTitle"
      @click="!refreshDisabled && emit('refresh')"
    />
  </div>
</template>

<style scoped>
.record-explorer-tools {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
</style>
