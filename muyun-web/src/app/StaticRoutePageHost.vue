<script setup lang="ts">
import { computed, type Component } from 'vue';
import type { RouteLocationNormalizedLoaded } from 'vue-router';
import { ModuleContextProvider } from '@muyun/web-core';
import { providePageLayout } from '@muyun/platform-components';
import { provideModulePageNavigation, type ModulePageWorkspaceView } from '@muyun/dynamic-page-runtime';
import type { BusinessRoutePageDescriptor, PageDescriptor, PageLayoutMode } from '@muyun/web-contracts';
import {
  createWorkspaceViewDescriptor,
  resolveWorkspaceView,
  syncModulePageWorkspaceViewContributions,
  useWorkbenchNavigation,
  WorkspaceViewOutlet,
} from '@muyun/platform-workbench';
import { providePageDescriptor, providePageRoute } from './pageRouteContext';

const props = defineProps<{
  component: Component;
  route: RouteLocationNormalizedLoaded;
  pageDescriptor?: PageDescriptor;
  /** Refreshes this page's inner route component without evicting sibling tabs from KeepAlive. */
  refreshRevision?: number;
}>();

const moduleAlias = computed(() => String(props.route.meta.moduleAlias ?? ''));
const layout = computed<PageLayoutMode>(() =>
  props.route.meta.layout === 'workspace' ? 'workspace' : 'flow',
);
const navigation = useWorkbenchNavigation();
const workspaceDescriptor = computed<BusinessRoutePageDescriptor>(() => ({
  pageType: 'business-route',
  openMode: 'workbench-route',
  hostType: 'business-route-host',
  title: typeof props.route.meta.title === 'string' ? props.route.meta.title : undefined,
  layout: layout.value,
  target: {
    route: props.route.path,
    moduleAlias: moduleAlias.value || undefined,
    query: props.route.query,
  },
  params: props.route.query,
  tabPolicy: { identity: 'by-params', closable: true, cacheable: true },
}));
const workspaceView = computed(() => resolveWorkspaceView(workspaceDescriptor.value));
// Route components such as ModulePageHost capture their transport identity in
// setup. If a parent briefly reuses this host while navigation commits, the
// inner runtime must still be rebuilt instead of receiving a cross-module prop
// update with stale request clients.
const pageContentKey = computed(() => `${props.route.fullPath}:${props.refreshRevision ?? 0}`);

syncModulePageWorkspaceViewContributions();
provideModulePageNavigation(
  navigation && {
    openPage: navigation.openPage,
    openWorkspaceTab(view, input, title) {
      navigation.openPage(
        createWorkspaceViewDescriptor(workspaceViewDefinitionForModulePage(view), input, 'tab', title),
      );
    },
  },
);

providePageLayout(layout);
providePageRoute(() => props.route);
providePageDescriptor(() => props.pageDescriptor);

function workspaceViewDefinitionForModulePage(view: ModulePageWorkspaceView) {
  return {
    ...view,
    route: view.route ?? `/_platform/workspace/${encodeURIComponent(view.type)}`,
    presentations: ['tab'] as const,
  };
}
</script>

<template>
  <ModuleContextProvider v-if="moduleAlias" :module-alias="moduleAlias">
    <WorkspaceViewOutlet v-if="workspaceView" :key="pageContentKey" :descriptor="workspaceDescriptor" />
    <component :is="component" v-else :key="pageContentKey" />
  </ModuleContextProvider>
  <WorkspaceViewOutlet v-else-if="workspaceView" :key="pageContentKey" :descriptor="workspaceDescriptor" />
  <component :is="component" v-else :key="pageContentKey" />
</template>
