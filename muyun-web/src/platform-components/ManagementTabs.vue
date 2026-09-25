<script setup lang="ts">
defineOptions({ name: 'ManagementTabs' });

withDefaults(
  defineProps<{
    tabs: Array<{ key: string; title: string }>;
    activeKey?: string;
    disabled?: boolean;
    label?: string;
    appearance?: 'section' | 'header';
  }>(),
  { label: '视图切换', appearance: 'section' },
);

const emit = defineEmits<{
  'update:activeKey': [key: string];
}>();
</script>

<template>
  <nav
    v-if="tabs.length > 1"
    class="management-tabs"
    :class="{ 'management-tabs--header': appearance === 'header' }"
    role="tablist"
    :aria-label="label"
  >
    <button
      v-for="tab in tabs"
      :key="tab.key"
      class="management-tabs__tab"
      :class="{ 'management-tabs__tab--active': tab.key === activeKey }"
      :disabled="disabled"
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
.management-tabs {
  display: flex;
  gap: 18px;
  min-height: 34px;
  border-bottom: 1px solid var(--ui-border-color);
}

.management-tabs__tab {
  position: relative;
  padding: 7px 1px 6px;
  border: 0;
  color: var(--ui-text-secondary);
  background: transparent;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.management-tabs__tab::after {
  position: absolute;
  right: 0;
  bottom: -1px;
  left: 0;
  height: 2px;
  background: transparent;
  content: '';
}

.management-tabs__tab--active {
  color: var(--ui-primary-color);
  font-weight: 600;
}

.management-tabs__tab--active::after {
  background: currentColor;
}

.management-tabs__tab:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
.management-tabs--header {
  min-height: var(--muyun-management-panel-header-height, 30px);
  border-bottom: 0;
  gap: 16px;
}
.management-tabs--header .management-tabs__tab {
  padding: 4px 0;
  font-size: 14px;
  white-space: nowrap;
}
</style>
