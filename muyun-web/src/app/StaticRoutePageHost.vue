<script setup lang="ts">
import { computed, nextTick, onActivated, ref, type Component } from 'vue';
import type { RouteLocationNormalizedLoaded } from 'vue-router';
import { UiSidePanelHost } from '@muyun/vue-ui-antdv';
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
// Workbench tabs restore their page layout from the descriptor. The router only
// knows the generic workspace route, so route metadata alone would downgrade a
// descriptor-owned workbench to flow layout.
const layout = computed<PageLayoutMode>(
  () => props.pageDescriptor?.layout ?? (props.route.meta.layout === 'workspace' ? 'workspace' : 'flow'),
);
const navigation = useWorkbenchNavigation();
const workspaceDescriptor = computed<BusinessRoutePageDescriptor>(() => {
  // A workbench tab may intentionally separate identity parameters from
  // URL-restorable view state. Rebuilding a descriptor from the route would
  // merge them again and make an in-place workspace navigation lose its owner.
  if (props.pageDescriptor?.pageType === 'business-route') {
    return props.pageDescriptor;
  }
  return {
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
  };
});
const workspaceView = computed(() => resolveWorkspaceView(workspaceDescriptor.value));
// Route changes are handled by the workbench cache key. This inner key is only
// for an explicit page refresh, so ordinary static pages retain reactive route
// updates and their local state contract.
const pageContentKey = computed(() => props.refreshRevision ?? 0);
const scrollContent = ref<HTMLElement>();
let scrollTop = 0;
let scrollLeft = 0;
function rememberScroll() {
  if (!scrollContent.value?.isConnected) return;
  scrollTop = scrollContent.value.scrollTop;
  scrollLeft = scrollContent.value.scrollLeft;
}
onActivated(async () => {
  await nextTick();
  if (!scrollContent.value) return;
  scrollContent.value.scrollLeft = scrollLeft;
  scrollContent.value.scrollTop = scrollTop;
});

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
  <UiSidePanelHost>
    <div
      ref="scrollContent"
      class="static-route-page-content"
      :class="{ 'static-route-page-content--workspace': layout === 'workspace' }"
      @scroll="rememberScroll"
    >
      <ModuleContextProvider v-if="moduleAlias" :module-alias="moduleAlias">
        <WorkspaceViewOutlet v-if="workspaceView" :key="pageContentKey" :descriptor="workspaceDescriptor" />
        <component :is="component" v-else :key="pageContentKey" />
      </ModuleContextProvider>
      <WorkspaceViewOutlet
        v-else-if="workspaceView"
        :key="pageContentKey"
        :descriptor="workspaceDescriptor"
      />
      <component :is="component" v-else :key="pageContentKey" />
    </div>
  </UiSidePanelHost>
</template>
<style scoped>
.static-route-page-content {
  box-sizing: border-box;
  height: 100%;
  min-width: 0;
  min-height: 0;
  padding: 10px;
  overflow: auto;
  overscroll-behavior: contain;
}
.static-route-page-content--workspace {
  overflow-x: auto;
  overflow-y: hidden;
}
</style>
