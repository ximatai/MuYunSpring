<script setup lang="ts">
import type { PlatformRoutePageDescriptor } from '@muyun/web-contracts';
import { computed } from 'vue';

defineOptions({ name: 'PlatformRouteHost' });

const props = defineProps<{
  descriptor: PlatformRoutePageDescriptor;
}>();

const title = computed(
  () =>
    props.descriptor.title ??
    props.descriptor.target.route ??
    props.descriptor.target.routeName ??
    props.descriptor.target.pageKey,
);
</script>

<template>
  <RouterView v-if="props.descriptor.target.route === '/'" />
  <section v-else class="page-host">
    <header>
      <span class="host-badge">平台页面</span>
      <h2>{{ title }}</h2>
    </header>
    <p>
      {{
        props.descriptor.target.route ?? props.descriptor.target.routeName ?? props.descriptor.target.pageKey
      }}
    </p>
  </section>
</template>

<style scoped>
.page-host {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 76px;
  padding: 14px;
  border: 1px solid var(--muyun-support-border);
  border-radius: 8px;
  background: var(--muyun-support-surface);
}

header {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.host-badge {
  width: fit-content;
  padding: 4px 8px;
  border-radius: 999px;
  background: var(--muyun-info-soft);
  color: var(--muyun-info-soft-text);
  font-size: 12px;
  font-weight: 700;
}

h2 {
  overflow: hidden;
  margin: 0;
  color: var(--muyun-support-text);
  font-size: 15px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

p {
  overflow: hidden;
  margin: 0;
  color: var(--muyun-support-text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
