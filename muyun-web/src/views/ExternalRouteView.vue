<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import ExternalPageHost from '@/platform-workbench/hosts/ExternalPageHost.vue';
import type { RemoteUrlPageDescriptor } from '@muyun/web-contracts';

const route = useRoute();
const descriptor = computed<RemoteUrlPageDescriptor>(() => ({
  pageType: 'remote-url',
  openMode: 'iframe',
  hostType: 'external-page-host',
  title: String(route.meta.title ?? ''),
  menuId: String(route.meta.menuId),
  target: { url: String(route.meta.externalUrl ?? ''), moduleAlias: String(route.meta.moduleAlias ?? '') },
  tabPolicy: { identity: 'by-menu', closable: true, cacheable: true },
}));
</script>

<template>
  <ExternalPageHost :descriptor="descriptor" />
</template>
