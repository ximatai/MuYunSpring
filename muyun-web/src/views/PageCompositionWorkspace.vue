<script setup lang="ts">
import { shallowRef, onActivated, onDeactivated, onMounted, onBeforeUnmount, watch } from 'vue';
import { useModuleContext, useAssistantSurfaceHost, createAssistantTurnRequester } from '@muyun/web-core';
import { createPageCompositionSession } from './pageCompositionSession';
import { usePageCompositionWorkspace } from './pageCompositionWorkspace';
import { createPageCompositionAssistantSurface } from './pageCompositionAssistantSurface';
import { waitForConfigurationEditor } from './configurationEditorNavigation';
import PageCompositionEditor from './PageCompositionEditor.vue';

defineOptions({ name: 'PageCompositionWorkspace' });
const props = defineProps<{ moduleAlias: string; moduleTitle?: string }>();
const context = useModuleContext({ moduleAlias: 'platform.module' });
const workspace = usePageCompositionWorkspace();
const host = useAssistantSurfaceHost();
let active = false;
let unregister: (() => void) | undefined;
let fallback: ReturnType<typeof createPageCompositionSession> | undefined;
const session = shallowRef<ReturnType<typeof createPageCompositionSession>>(null!);
watch(
  () => [props.moduleAlias, props.moduleTitle] as const,
  () => {
    if (workspace) session.value = workspace.session(props.moduleAlias, props.moduleTitle);
    else {
      fallback?.dispose();
      session.value = fallback = createPageCompositionSession(
        context.http,
        { moduleAlias: props.moduleAlias, moduleTitle: props.moduleTitle },
        { valid: () => true, active: () => active },
      );
    }
  },
  { immediate: true },
);

function clear() {
  unregister?.();
  unregister = undefined;
}
function activate() {
  active = true;
  workspace?.focus(session.value);
  workspace?.showEditor(session.value);
  clear();
  const pageInstanceKey = host?.activePageInstanceKey();
  if (host && pageInstanceKey)
    unregister = host.registry.register({
      pageInstanceKey,
      contextRevision: () => `${props.moduleAlias}:${session.value.contextRevision.value}`,
      settle: (signal) =>
        waitForConfigurationEditor(
          () => !session.value.isMutating.value && !session.value.catalogueRefreshPending.value,
          signal,
        ),
      surface: {
        ...createPageCompositionAssistantSurface(
          session.value.adapter,
          createAssistantTurnRequester(context.http),
          () => host.capabilities?.() ?? [],
        ),
        // Keep the mounted page readiness signal; the shared workspace owns its tools.
        ...(workspace ? { capabilities: () => host.capabilities?.() ?? workspace.capabilities() } : {}),
      },
    });
}
watch(session, (value, previous) => {
  if (previous) workspace?.hideEditor(previous);
  if (active) activate();
});
onMounted(activate);
onActivated(activate);
onDeactivated(() => {
  active = false;
  workspace?.hideEditor(session.value);
  clear();
});
onBeforeUnmount(() => {
  active = false;
  workspace?.hideEditor(session.value);
  clear();
  fallback?.dispose();
});
</script>
<template>
  <PageCompositionEditor :key="moduleAlias" :session="session" :retained="Boolean(workspace)" />
</template>
