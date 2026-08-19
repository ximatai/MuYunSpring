<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { UiButton, UiInput } from '@muyun/vue-ui-antdv';
import ManagementPanelHeader from './ManagementPanelHeader.vue';

defineOptions({ name: 'RecordExplorerPanel' });

const props = withDefaults(
  defineProps<{
    title: string;
    refreshTitle?: string;
    searchKeyword?: string;
    searchPlaceholder?: string;
    searchable?: boolean;
  }>(),
  {
    refreshTitle: undefined,
    searchKeyword: '',
    searchPlaceholder: '搜索名称、编码或 ID',
    searchable: true,
  },
);

const emit = defineEmits<{
  'update:searchKeyword': [keyword: string];
  refresh: [];
}>();

const searchExpanded = ref(props.searchKeyword.trim().length > 0);
const searchRoot = ref<HTMLElement>();
const searchVisible = computed(() => props.searchable && searchExpanded.value);

watch(
  () => props.searchKeyword,
  (keyword) => {
    if (keyword.trim()) searchExpanded.value = true;
  },
);

function toggleSearch() {
  if (searchExpanded.value) {
    emit('update:searchKeyword', '');
    searchExpanded.value = false;
    return;
  }
  searchExpanded.value = true;
  focusSearchInput();
}

function handleSearchEscape() {
  emit('update:searchKeyword', '');
  searchExpanded.value = false;
}

async function focusSearchInput() {
  await nextTick();
  searchRoot.value?.querySelector('input')?.focus();
}
</script>

<template>
  <section class="record-explorer-panel">
    <ManagementPanelHeader
      class="record-explorer-panel-header"
      :title="title"
      title-action-icon="reload"
      :title-action-title="refreshTitle ?? `刷新${title}`"
      @title-action="emit('refresh')"
    >
      <template v-if="$slots['title-extra']" #status>
        <slot name="title-extra" />
      </template>
      <template #actions>
        <div class="record-explorer-panel-actions">
          <UiButton
            v-if="searchable"
            icon-name="search"
            type="text"
            :title="`搜索${title}`"
            @click="toggleSearch"
          />
          <slot name="actions" />
        </div>
      </template>
    </ManagementPanelHeader>

    <Transition name="record-explorer-search">
      <div v-if="searchVisible" ref="searchRoot" class="record-explorer-search">
        <UiInput
          :value="searchKeyword"
          allow-clear
          :placeholder="searchPlaceholder"
          autofocus
          @update:value="emit('update:searchKeyword', $event)"
          @keydown.esc="handleSearchEscape"
        />
      </div>
    </Transition>

    <div class="record-explorer-panel-content">
      <slot />
    </div>

    <footer v-if="$slots.footer" class="record-explorer-panel-footer">
      <slot name="footer" />
    </footer>

    <slot name="editor" />
  </section>
</template>

<style scoped>
.record-explorer-panel {
  position: relative;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  padding: var(--muyun-management-panel-padding-block, 10px)
    var(--muyun-management-panel-padding-inline, 12px);
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
  background: var(--muyun-surface);
  overflow: hidden;
}

.record-explorer-panel-header {
  flex: 0 0 auto;
  margin-bottom: var(--muyun-management-panel-content-gap, 8px);
}

.record-explorer-panel-actions {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 4px;
}

.record-explorer-search {
  display: flex;
  flex: 0 0 auto;
  min-width: 0;
  margin-bottom: var(--muyun-management-panel-content-gap, 8px);
  overflow: hidden;
}

.record-explorer-search-enter-active,
.record-explorer-search-leave-active {
  max-height: 40px;
  transition:
    max-height 0.16s ease,
    margin-bottom 0.16s ease,
    opacity 0.16s ease,
    transform 0.16s ease;
}

.record-explorer-search-enter-from,
.record-explorer-search-leave-to {
  max-height: 0;
  margin-bottom: 0;
  opacity: 0;
  transform: translateY(-4px);
}

.record-explorer-search-enter-to,
.record-explorer-search-leave-from {
  max-height: 40px;
  margin-bottom: var(--muyun-management-panel-content-gap, 8px);
  opacity: 1;
  transform: translateY(0);
}

.record-explorer-search :deep(.ant-input) {
  width: 100%;
}

.record-explorer-panel-content {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.record-explorer-panel-content :slotted(*) {
  flex: 1 1 auto;
  min-height: 0;
}

.record-explorer-panel-footer {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  min-height: 32px;
  margin-top: var(--muyun-management-panel-content-gap, 8px);
  padding-top: 8px;
  border-top: 1px solid var(--muyun-border-subtle);
}
</style>
