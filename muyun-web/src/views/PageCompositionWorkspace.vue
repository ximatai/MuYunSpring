<script setup lang="ts">
import { shallowRef, nextTick, onActivated, onDeactivated, onMounted, onBeforeUnmount, watch } from 'vue';
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
let settlement: AbortController | undefined;
let stopReadiness: (() => void) | undefined;
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
  stopReadiness?.();
  stopReadiness = undefined;
  settlement?.abort();
  settlement = undefined;
  unregister?.();
  unregister = undefined;
}
function activate() {
  // KeepAlive invokes mounted and activated on first entry; register one stable surface.
  if (active && (unregister || settlement)) return;
  active = true;
  workspace?.focus(session.value);
  workspace?.showEditor(session.value);
  clear();
  const pageInstanceKey = host?.activePageInstanceKey();
  if (!host || !pageInstanceKey) return;
  const current = session.value;
  const controller = new AbortController();
  settlement = controller;
  const ready = () => !current.isMutating.value && !current.catalogueRefreshPending.value;
  // Lifecycle readiness survives a navigation timeout; a slow successful load must still register.
  stopReadiness = watch(
    ready,
    async (available) => {
      if (!available) return;
      await nextTick();
      if (
        controller.signal.aborted ||
        settlement !== controller ||
        !active ||
        !ready() ||
        session.value !== current ||
        host.activePageInstanceKey() !== pageInstanceKey
      )
        return;
      stopReadiness?.();
      stopReadiness = undefined;
      settlement = undefined;
      unregister = host.registry.register({
        pageInstanceKey,
        contextRevision: () => `${current.moduleAlias}:${current.contextRevision.value}`,
        settle: (signal) => waitForConfigurationEditor(ready, signal),
        surface: {
          ...createPageCompositionAssistantSurface(
            current.adapter,
            createAssistantTurnRequester(context.http),
            () => host.capabilities?.() ?? [],
          ),
          // Keep the mounted page readiness signal; the shared workspace owns its tools.
          ...(workspace ? { capabilities: () => host.capabilities?.() ?? workspace.capabilities() } : {}),
        },
      });
    },
    { immediate: true, flush: 'post' },
  );
}
watch(session, (value, previous) => {
  if (previous) workspace?.hideEditor(previous);
  if (active) {
    clear();
    activate();
  }
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
