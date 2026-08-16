<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { DynamicModuleHost } from '@muyun/dynamic-page-runtime';
import type { DynamicModulePageDescriptor, MenuPageMode } from '@muyun/web-contracts';

const route = useRoute();
const descriptor = computed<DynamicModulePageDescriptor>(() => ({
  pageType: 'dynamic-module',
  openMode: 'dynamic-runner',
  hostType: 'dynamic-module-host',
  title: String(route.meta.title ?? route.params.moduleAlias),
  menuId: String(route.meta.menuId),
  target: {
    moduleAlias: String(route.meta.moduleAlias ?? route.params.moduleAlias),
    pageMode: String(route.meta.pageMode ?? route.params.pageMode).toUpperCase() as MenuPageMode,
    defaultUiConfigId:
      typeof route.meta.defaultUiConfigId === 'string' ? route.meta.defaultUiConfigId : undefined,
    defaultQueryTemplateId:
      typeof route.meta.defaultQueryTemplateId === 'string' ? route.meta.defaultQueryTemplateId : undefined,
  },
  params: Object.fromEntries(Object.entries(route.query).filter(([key]) => !key.startsWith('_muyun'))),
  tabPolicy: { identity: 'by-menu', closable: true, cacheable: true },
}));
</script>

<template>
  <DynamicModuleHost :descriptor="descriptor" />
</template>
