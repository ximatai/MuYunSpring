<script setup lang="ts">
defineOptions({ name: 'RecordRelationTabs' });

defineProps<{
  tabs: Array<{ key: string; title: string }>;
  activeKey?: string;
}>();

const emit = defineEmits<{
  'update:activeKey': [key: string];
}>();
</script>

<template>
  <nav v-if="tabs.length > 1" class="record-relation-tabs" role="tablist" aria-label="关联记录">
    <button
      v-for="tab in tabs"
      :key="tab.key"
      class="record-relation-tabs__tab"
      :class="{ 'record-relation-tabs__tab--active': tab.key === activeKey }"
      type="button"
      role="tab"
      :aria-selected="tab.key === activeKey"
      @click="emit('update:activeKey', tab.key)"
    >
      {{ tab.title }}
    </button>
  </nav>
</template>

<style scoped>
.record-relation-tabs {
  display: flex;
  gap: 18px;
  min-height: 34px;
  border-bottom: 1px solid var(--ui-border-color);
}

.record-relation-tabs__tab {
  position: relative;
  padding: 7px 1px 6px;
  border: 0;
  color: var(--ui-text-secondary);
  background: transparent;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.record-relation-tabs__tab::after {
  position: absolute;
  right: 0;
  bottom: -1px;
  left: 0;
  height: 2px;
  background: transparent;
  content: '';
}

.record-relation-tabs__tab--active {
  color: var(--ui-primary-color);
  font-weight: 600;
}

.record-relation-tabs__tab--active::after {
  background: currentColor;
}
</style>
