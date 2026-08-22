<script setup lang="ts">
import { computed } from 'vue';
import { usePageDescriptor, usePageRoute } from '../app/pageRouteContext';
import { DynamicModuleHost } from '@muyun/dynamic-page-runtime';
import type { DynamicModulePageDescriptor, MenuPageMode } from '@muyun/web-contracts';

const route = usePageRoute();
const restoredDescriptor = usePageDescriptor();
const moduleAlias = computed(() => {
  const fromMeta = route.value.meta.moduleAlias;
  if (typeof fromMeta === 'string') return fromMeta;
  const applicationAlias = String(route.value.params.applicationAlias ?? '');
  const moduleName = String(route.value.params.moduleName ?? '').replaceAll('-', '_');
  return applicationAlias && moduleName ? `${applicationAlias}.${moduleName}` : '';
});
const descriptor = computed<DynamicModulePageDescriptor>(() => {
  if (restoredDescriptor.value?.pageType === 'dynamic-module') {
    return { ...restoredDescriptor.value, hostType: 'dynamic-module-host' };
  }

  return {
    pageType: 'dynamic-module',
    openMode: 'dynamic-runner',
    hostType: 'dynamic-module-host',
    title: String(route.value.meta.title ?? moduleAlias.value),
    menuId: typeof route.value.meta.menuId === 'string' ? route.value.meta.menuId : undefined,
    target: {
      moduleAlias: moduleAlias.value,
      pageMode: String(
        route.value.meta.pageMode ?? route.value.query.mode ?? 'LIST',
      ).toUpperCase() as MenuPageMode,
      defaultUiConfigId:
        typeof route.value.meta.defaultUiConfigId === 'string'
          ? route.value.meta.defaultUiConfigId
          : undefined,
      defaultQueryTemplateId:
        typeof route.value.meta.defaultQueryTemplateId === 'string'
          ? route.value.meta.defaultQueryTemplateId
          : undefined,
    },
    params: Object.fromEntries(
      Object.entries(route.value.query).filter(
        ([key]) => key !== 'menu' && key !== 'mode' && key !== 'InstanceKey',
      ),
    ),
    tabPolicy: { identity: 'by-menu', closable: true, cacheable: true },
  };
});
</script>

<template>
  <DynamicModuleHost :descriptor="descriptor" />
</template>
