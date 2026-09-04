<script setup lang="ts">
import type { BusinessRoutePageDescriptor } from '@muyun/web-contracts';
import { computed, onUnmounted } from 'vue';
import { UiEmpty } from '@muyun/vue-ui-antdv';
import { tabKeyOf } from './menuNavigation';
import { useWorkbenchNavigation } from './workbenchNavigation';
import { provideWorkspaceViewHost } from './workspaceViewHost';
import { dismissWorkspaceViewDescriptor, resolveWorkspaceView } from './workspaceViews';
import {
  clearWorkspaceViewUnsavedState,
  registerWorkspaceViewUnsavedState,
} from './workspaceViewUnsavedState';

const props = defineProps<{ descriptor: BusinessRoutePageDescriptor }>();
const resolvedView = computed(() => resolveWorkspaceView(props.descriptor));
// `replacePage` preserves this physical tab key even if URL-restorable view
// state changes. Keep the original owner rather than recomputing a key from
// a descriptor that may now describe a different navigation state.
const ownerPageKey = tabKeyOf(props.descriptor);
const navigation = useWorkbenchNavigation();
provideWorkspaceViewHost({
  get presentation() {
    return resolvedView.value?.presentation ?? 'tab';
  },
  setTitle(title) {
    const normalizedTitle = title.trim();
    if (!normalizedTitle || !navigation || props.descriptor.title === normalizedTitle) return;
    navigation.replacePage(ownerPageKey, { ...props.descriptor, title: normalizedTitle });
  },
  replaceQuery(changes) {
    if (!navigation) return;
    const query = { ...(props.descriptor.target.query ?? {}) };
    for (const [key, value] of Object.entries(changes)) {
      if (value === undefined) delete query[key];
      else query[key] = value;
    }
    navigation.replacePage(ownerPageKey, {
      ...props.descriptor,
      target: { ...props.descriptor.target, query },
    });
  },
  registerUnsavedState(source, isDirty) {
    return registerWorkspaceViewUnsavedState(ownerPageKey, source, isDirty);
  },
  dismiss() {
    const view = resolvedView.value;
    if (view && navigation)
      navigation.replacePage(ownerPageKey, dismissWorkspaceViewDescriptor(props.descriptor, view.view));
  },
  close() {
    navigation?.closePage(ownerPageKey);
  },
});

onUnmounted(() => clearWorkspaceViewUnsavedState(ownerPageKey));
</script>
<template>
  <component
    :is="resolvedView.view.component"
    v-if="resolvedView"
    v-bind="resolvedView.input"
    :title="descriptor.title ?? resolvedView.view.titleOf(resolvedView.input)"
    @close-workspace="navigation?.closePage(ownerPageKey)"
  />
  <UiEmpty v-else description="无法恢复该工作视图" />
</template>
