<script setup lang="ts">
import { computed } from 'vue';
import { ModuleContextProvider } from '@muyun/web-core';
import { providePageLayout } from '@muyun/platform-components';
import type { BusinessRoutePageDescriptor } from '@muyun/web-contracts';
import { resolvePlatformAdminRoute } from './platformAdminRoutes';
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
