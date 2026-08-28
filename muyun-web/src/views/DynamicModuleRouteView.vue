<script setup lang="ts">
import { computed } from 'vue';
import { usePageDescriptor, usePageRoute } from '../app/pageRouteContext';
import { ModulePageHost } from '@muyun/dynamic-page-runtime';
import type { MenuPageMode, ModulePageDescriptor } from '@muyun/web-contracts';

const route = usePageRoute();
const restoredDescriptor = usePageDescriptor();
const moduleAlias = computed(() => {
  const fromMeta = route.value.meta.moduleAlias;
  if (typeof fromMeta === 'string') return fromMeta;
  const applicationAlias = String(route.value.params.applicationAlias ?? '');
  const moduleName = String(route.value.params.moduleName ?? '').replaceAll('-', '_');
  return applicationAlias && moduleName ? `${applicationAlias}.${moduleName}` : '';
});
const descriptor = computed<ModulePageDescriptor>(() => {
  if (restoredDescriptor.value?.pageType === 'dynamic-module') {
    return { ...restoredDescriptor.value, hostType: 'module-page-host' };
  }

  return {
    pageType: 'dynamic-module',
    openMode: 'dynamic-runner',
    hostType: 'module-page-host',
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
// ModulePageHost creates module-scoped transport clients in setup. Route
// transitions can update this descriptor before an outer cached page host has
// deactivated, so its identity must include the resolved module and route.
const moduleRuntimeKey = computed(
  () =>
    `${descriptor.value.target.moduleAlias}:${descriptor.value.menuId ?? ''}:${route.value.path}:${JSON.stringify(route.value.query)}`,
);
</script>

<template>
  <ModulePageHost :key="moduleRuntimeKey" :descriptor="descriptor" />
</template>
