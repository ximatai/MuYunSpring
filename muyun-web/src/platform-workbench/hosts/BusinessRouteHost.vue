<script setup lang="ts">
import type { BusinessRoutePageDescriptor } from '@muyun/web-contracts';
import { computed } from 'vue';
import { ModuleContextProvider } from '@muyun/web-core';
import WorkspaceViewOutlet from '../WorkspaceViewOutlet.vue';
import { resolveWorkspaceView } from '../workspaceViews';

defineOptions({ name: 'BusinessRouteHost' });

const props = defineProps<{
  descriptor: BusinessRoutePageDescriptor;
}>();

const title = computed(
  () =>
    props.descriptor.title ??
    props.descriptor.target.route ??
    props.descriptor.target.routeName ??
    props.descriptor.target.pageKey,
);
const workspaceView = computed(() => resolveWorkspaceView(props.descriptor));
</script>

<template>
  <ModuleContextProvider v-if="workspaceView" :module-alias="workspaceView.view.moduleAlias">
    <WorkspaceViewOutlet :descriptor="descriptor" />
  </ModuleContextProvider>
  <section v-else class="page-host">
    <header>
      <span class="host-badge">业务页面</span>
      <h2>{{ title }}</h2>
    </header>
    <p>{{ descriptor.target.route ?? descriptor.target.routeName ?? descriptor.target.pageKey }}</p>
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
  background: var(--muyun-warning-soft);
  color: var(--muyun-warning-soft-text);
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
