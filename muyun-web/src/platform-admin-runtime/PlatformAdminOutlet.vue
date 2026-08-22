<script setup lang="ts">
import { computed } from 'vue';
import { ModuleContextProvider } from '@muyun/web-core';
import { providePageLayout } from '@muyun/platform-components';
import type { BusinessRoutePageDescriptor } from '@muyun/web-contracts';
import { resolvePlatformAdminRoute } from './platformAdminRoutes';
import { providePageRoute, type PageRoute } from '../app/pageRouteContext';
import './workspaceViews';
import { WorkspaceViewOutlet } from '@muyun/platform-workbench';

defineOptions({ name: 'PlatformAdminRouteOutlet' });

const props = defineProps<{
  descriptor: BusinessRoutePageDescriptor;
}>();

const route = computed(() => resolvePlatformAdminRoute(props.descriptor));
const moduleAlias = computed(() => props.descriptor.target.moduleAlias ?? route.value?.moduleAlias);
const workspaceViewPresentation = computed(() => props.descriptor.target.query?.workspacePresentation);
providePageLayout(computed(() => props.descriptor.layout ?? route.value?.layout));
providePageRoute((): PageRoute => {
  const pattern = route.value?.route ?? props.descriptor.target.route ?? '';
  const path = props.descriptor.target.route ?? pattern;
  return {
    path,
    meta: {},
    query: (props.descriptor.target.query ?? props.descriptor.params ?? {}) as PageRoute['query'],
    params: routeParamsOf(pattern, path),
    matched: [{ path: pattern }],
  };
});

function routeParamsOf(pattern: string, path: string) {
  const params: Record<string, string> = {};
  const patternSegments = pattern.split('/').filter(Boolean);
  const pathSegments = path.split('/').filter(Boolean);
  patternSegments.forEach((segment, index) => {
    if (segment.startsWith(':') && pathSegments[index]) params[segment.slice(1)] = pathSegments[index];
  });
  return params;
}
</script>

<template>
  <ModuleContextProvider v-if="route && moduleAlias" :module-alias="moduleAlias">
    <WorkspaceViewOutlet
      v-if="workspaceViewPresentation === 'drawer' || workspaceViewPresentation === 'tab'"
      :descriptor="descriptor"
    />
    <component :is="route.component" v-else />
  </ModuleContextProvider>
</template>
