<script setup lang="ts">
import { computed } from 'vue';
import { usePageRoute } from '../app/pageRouteContext';
import { DynamicModuleHost } from '@muyun/dynamic-page-runtime';
import type { DynamicModulePageDescriptor, MenuPageMode } from '@muyun/web-contracts';

const route = usePageRoute();
const descriptor = computed<DynamicModulePageDescriptor>(() => ({
  pageType: 'dynamic-module',
  openMode: 'dynamic-runner',
  hostType: 'dynamic-module-host',
  title: String(route.value.meta.title ?? route.value.params.moduleAlias),
  menuId: String(route.value.meta.menuId),
  target: {
    moduleAlias: String(route.value.meta.moduleAlias ?? route.value.params.moduleAlias),
    pageMode: String(route.value.meta.pageMode ?? route.value.params.pageMode).toUpperCase() as MenuPageMode,
    defaultUiConfigId:
      typeof route.value.meta.defaultUiConfigId === 'string' ? route.value.meta.defaultUiConfigId : undefined,
    defaultQueryTemplateId:
      typeof route.value.meta.defaultQueryTemplateId === 'string'
        ? route.value.meta.defaultQueryTemplateId
        : undefined,
  },
  params: Object.fromEntries(Object.entries(route.value.query).filter(([key]) => !key.startsWith('_muyun'))),
  tabPolicy: { identity: 'by-menu', closable: true, cacheable: true },
}));
</script>

<template>
  <DynamicModuleHost :descriptor="descriptor" />
</template>
