<script setup lang="ts">
import { provideWorkspaceUnsavedStateRegistrar, useLanguageContext } from '@muyun/web-core';
import { computed } from 'vue';
import type {
  BusinessRoutePageDescriptor,
  DynamicModulePageDescriptor,
  ModulePageDescriptor,
  ExternalLinkPageDescriptor,
  PageDescriptor,
  PlatformRoutePageDescriptor,
  RemoteUrlPageDescriptor,
} from '@muyun/web-contracts';
import {
  ModulePageHost,
  provideModulePageNavigation,
  type ModulePageWorkspaceView,
} from '@muyun/dynamic-page-runtime';
import { UiEmpty } from '@muyun/vue-ui-antdv';
import BusinessRouteHost from './hosts/BusinessRouteHost.vue';
import ExternalPageHost from './hosts/ExternalPageHost.vue';
import PlatformRouteHost from './hosts/PlatformRouteHost.vue';
import { resolvePageHostComponentName } from './pageHostRegistry';
import { useWorkbenchNavigation } from './workbenchNavigation';
import { createWorkspaceViewDescriptor } from './workspaceViews';
import { syncModulePageWorkspaceViewContributions } from './modulePageWorkspaceViews';
import { tabKeyOf } from './menuNavigation';
import { registerWorkspaceViewUnsavedState } from './workspaceViewUnsavedState';

const language = useLanguageContext();

defineOptions({ name: 'WorkbenchOutlet' });

const props = defineProps<{
  descriptor?: PageDescriptor;
}>();
const navigation = useWorkbenchNavigation();
provideWorkspaceUnsavedStateRegistrar({
  registerUnsavedState(source, isDirty, isBusy, sourceTitle) {
    return props.descriptor
      ? registerWorkspaceViewUnsavedState(tabKeyOf(props.descriptor), source, isDirty, isBusy, sourceTitle)
      : () => {};
  },
});
syncModulePageWorkspaceViewContributions();
provideModulePageNavigation(
  navigation && {
    openPage: navigation.openPage,
    openWorkspaceTab(view, input, title) {
      navigation.openPage(
        createWorkspaceViewDescriptor(
          workspaceViewDefinitionForModulePage(view),
          input,
          'tab',
          title,
          language,
        ),
      );
    },
  },
);

function workspaceViewDefinitionForModulePage(view: ModulePageWorkspaceView) {
  return {
    ...view,
    route: view.route ?? `/_platform/workspace/${encodeURIComponent(view.type)}`,
    presentations: ['tab'] as const,
  };
}

const pageHostComponentName = computed(() =>
  props.descriptor ? resolvePageHostComponentName(props.descriptor.hostType) : undefined,
);
const routeDescriptor = computed(() =>
  pageHostComponentName.value === 'PlatformRouteHost'
    ? (props.descriptor as PlatformRoutePageDescriptor)
    : undefined,
);
const businessRouteDescriptor = computed(() =>
  pageHostComponentName.value === 'BusinessRouteHost'
    ? (props.descriptor as BusinessRoutePageDescriptor)
    : undefined,
);
const dynamicDescriptor = computed(() =>
  pageHostComponentName.value === 'ModulePageHost'
    ? (props.descriptor as ModulePageDescriptor | DynamicModulePageDescriptor)
    : undefined,
);
const externalDescriptor = computed(() =>
  pageHostComponentName.value === 'ExternalPageHost'
    ? (props.descriptor as RemoteUrlPageDescriptor | ExternalLinkPageDescriptor)
    : undefined,
);
</script>

<template>
  <PlatformRouteHost v-if="routeDescriptor" :descriptor="routeDescriptor" />
  <BusinessRouteHost v-else-if="businessRouteDescriptor" :descriptor="businessRouteDescriptor" />
  <ModulePageHost v-else-if="dynamicDescriptor" :descriptor="dynamicDescriptor" />
  <ExternalPageHost v-else-if="externalDescriptor" :descriptor="externalDescriptor" />
  <UiEmpty
    v-else
    :description="language.t('platform.screen.WorkbenchOutlet.no-page-available', '暂无页面')"
  />
</template>
