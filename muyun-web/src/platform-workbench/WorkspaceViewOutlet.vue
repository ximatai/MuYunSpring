<script setup lang="ts">
import type { BusinessRoutePageDescriptor } from '@muyun/web-contracts';
import { useLanguageContext, presentationLabel } from '@muyun/web-core';
import { computed, onUnmounted } from 'vue';
import { UiEmpty } from '@muyun/vue-ui-antdv';
import { provideModulePageUnsavedStateHost } from '@muyun/dynamic-page-runtime';
import { tabKeyOf } from './menuNavigation';
import { useWorkbenchNavigation } from './workbenchNavigation';
import { provideWorkspaceViewHost } from './workspaceViewHost';
import { dismissWorkspaceViewDescriptor, resolveWorkspaceView } from './workspaceViews';
import { registerWorkspaceViewUnsavedState } from './workspaceViewUnsavedState';

const props = defineProps<{ descriptor: BusinessRoutePageDescriptor }>();
const language = useLanguageContext();
const resolvedView = computed(() => resolveWorkspaceView(props.descriptor));
// `replacePage` preserves this physical tab key even if URL-restorable view
// state changes. Keep the original owner rather than recomputing a key from
// a descriptor that may now describe a different navigation state.
const ownerPageKey = tabKeyOf(props.descriptor);
const navigation = useWorkbenchNavigation();
const ownedRegistrations = new Set<() => void>();

function registerUnsavedState(
  source: string,
  isDirty: () => boolean,
  isBusy?: () => boolean,
  sourceTitle?: () => string,
) {
  const unregister = registerWorkspaceViewUnsavedState(ownerPageKey, source, isDirty, isBusy, sourceTitle);
  const release = () => {
    unregister();
    ownedRegistrations.delete(release);
  };
  ownedRegistrations.add(release);
  return release;
}

provideWorkspaceViewHost({
  get presentation() {
    return resolvedView.value?.presentation ?? 'tab';
  },
  setTitle(title) {
    const normalizedTitle = title.trim();
    if (
      !normalizedTitle ||
      !navigation ||
      (props.descriptor.title === normalizedTitle && !props.descriptor.titleText)
    )
      return;
    navigation.replacePage(ownerPageKey, {
      ...props.descriptor,
      title: normalizedTitle,
      titleText: undefined,
    });
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
  registerUnsavedState,
  dismiss() {
    const view = resolvedView.value;
    if (view && navigation)
      navigation.replacePage(
        ownerPageKey,
        dismissWorkspaceViewDescriptor(props.descriptor, view.view, language),
      );
  },
  close() {
    navigation?.closePage(ownerPageKey);
  },
});
provideModulePageUnsavedStateHost({ registerUnsavedState });

onUnmounted(() => ownedRegistrations.forEach((release) => release()));
</script>
<template>
  <component
    :is="resolvedView.view.component"
    v-if="resolvedView"
    v-bind="resolvedView.input"
    :title="
      presentationLabel(
        descriptor.title ?? resolvedView.view.titleOf(resolvedView.input),
        descriptor.titleText,
        language,
      )
    "
    @close-workspace="navigation?.closePage(ownerPageKey)"
  />
  <UiEmpty
    v-else
    :description="
      language.t(
        'platform.screen.WorkspaceViewOutlet.unable-to-restore-this-workspace-view',
        '无法恢复该工作视图',
      )
    "
  />
</template>
