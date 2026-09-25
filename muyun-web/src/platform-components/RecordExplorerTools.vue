<script setup lang="ts">
import { UiButton } from '@muyun/vue-ui-antdv';

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
      :title="`搜索${title}`"
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
