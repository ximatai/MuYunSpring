<script setup lang="ts">
import { computed } from 'vue';
import { usePageRoute } from '../app/pageRouteContext';
import ExternalPageHost from '@/platform-workbench/hosts/ExternalPageHost.vue';
import type { RemoteUrlPageDescriptor } from '@muyun/web-contracts';

const route = usePageRoute();
const descriptor = computed<RemoteUrlPageDescriptor>(() => ({
  pageType: 'remote-url',
  openMode: 'iframe',
  hostType: 'external-page-host',
  title: String(route.value.meta.title ?? ''),
  menuId: String(route.value.meta.menuId),
  target: {
    url: String(route.value.meta.externalUrl ?? ''),
    moduleAlias: String(route.value.meta.moduleAlias ?? ''),
  },
  tabPolicy: { identity: 'by-menu', closable: true, cacheable: true },
}));
</script>

<template>
  <ExternalPageHost :descriptor="descriptor" />
</template>
