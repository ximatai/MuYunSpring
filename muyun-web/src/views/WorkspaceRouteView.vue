<script setup lang="ts">
import { computed } from 'vue';
import type { BusinessRoutePageDescriptor } from '@muyun/web-contracts';
import { WorkspaceViewOutlet } from '@muyun/platform-workbench';
import { usePageRoute } from '../app/pageRouteContext';

const route = usePageRoute();
const descriptor = computed<BusinessRoutePageDescriptor>(() => ({
  pageType: 'business-route',
  openMode: 'workbench-route',
  hostType: 'business-route-host',
  target: { route: route.value.path, query: route.value.query },
  params: route.value.query,
  tabPolicy: { identity: 'by-params', closable: true, cacheable: true },
}));
</script>

<template>
  <WorkspaceViewOutlet :descriptor="descriptor" />
</template>
