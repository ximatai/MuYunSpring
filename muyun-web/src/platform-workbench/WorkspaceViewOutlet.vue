<script setup lang="ts">
import type { BusinessRoutePageDescriptor } from '@muyun/web-contracts';
import { computed } from 'vue';
import { UiEmpty } from '@muyun/vue-ui-antdv';
import { tabKeyOf } from './menuNavigation';
import { useWorkbenchNavigation } from './workbenchNavigation';
import { provideWorkspaceViewHost } from './workspaceViewHost';
import { dismissWorkspaceViewDescriptor, resolveWorkspaceView } from './workspaceViews';

const props = defineProps<{ descriptor: BusinessRoutePageDescriptor }>();
const resolvedView = computed(() => resolveWorkspaceView(props.descriptor));
const ownerPageKey = computed(() => tabKeyOf(props.descriptor));
const navigation = useWorkbenchNavigation();
provideWorkspaceViewHost({
  get presentation() {
    return resolvedView.value?.presentation ?? 'tab';
  },
  setTitle(title) {
    const normalizedTitle = title.trim();
    if (!normalizedTitle || !navigation || props.descriptor.title === normalizedTitle) return;
    navigation.replacePage(ownerPageKey.value, { ...props.descriptor, title: normalizedTitle });
  },
  dismiss() {
    const view = resolvedView.value;
    if (view && navigation)
      navigation.replacePage(ownerPageKey.value, dismissWorkspaceViewDescriptor(props.descriptor, view.view));
  },
});
</script>
<template>
  <component
    :is="resolvedView.view.component"
    v-if="resolvedView"
    v-bind="resolvedView.input"
    :title="descriptor.title ?? resolvedView.view.titleOf(resolvedView.input)"
  />
  <UiEmpty v-else description="无法恢复该工作视图" />
</template>
