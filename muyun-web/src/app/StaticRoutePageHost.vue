<script setup lang="ts">
import { computed, type Component } from 'vue';
import type { RouteLocationNormalizedLoaded } from 'vue-router';
import { ModuleContextProvider } from '@muyun/web-core';
import { providePageLayout } from '@muyun/platform-components';
import type { PageLayoutMode } from '@muyun/web-contracts';

const props = defineProps<{
  component: Component;
  route: RouteLocationNormalizedLoaded;
}>();

const moduleAlias = computed(() => String(props.route.meta.moduleAlias ?? ''));
const layout = computed<PageLayoutMode>(() =>
  props.route.meta.layout === 'workspace' ? 'workspace' : 'flow',
);

providePageLayout(layout);
</script>

<template>
  <ModuleContextProvider v-if="moduleAlias" :module-alias="moduleAlias">
    <component :is="component" />
  </ModuleContextProvider>
</template>
