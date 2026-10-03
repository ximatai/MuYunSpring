<script setup lang="ts">
import { onMounted, onActivated, onDeactivated, onBeforeUnmount } from 'vue';
import { useModuleContext, useAssistantSurfaceHost, createAssistantTurnRequester } from '@muyun/web-core';
import { useWorkbenchNavigation } from '@muyun/platform-workbench';
import { useModuleMenuWorkspace } from './moduleMenuWorkspace';
import { useCurrentUserContext } from '../currentUserContext';
import { createModuleMenuReceiptStore } from './moduleMenuReceiptStore';
import { createModuleMenuClient } from './moduleMenuClient';
import { createModuleMenuSession } from './moduleMenuSession';
import ModuleMenuEditor from './ModuleMenuEditor.vue';
const props = defineProps<{ moduleAlias: string }>();
const workspace = useModuleMenuWorkspace();
const user = useCurrentUserContext();
const identity = () =>
  JSON.stringify([
    user?.value?.userId,
    user?.value?.tenantId,
    user?.value?.organizationId,
    user?.value?.system,
  ]);
const owner = identity();
const context = useModuleContext({ moduleAlias: 'platform.module' });
const host = useAssistantSurfaceHost();
const navigation = useWorkbenchNavigation();
let active = false;
let generation = 0;
let unregister: (() => void) | undefined;
const session =
  workspace?.session(props.moduleAlias) ??
  createModuleMenuSession(
    createModuleMenuClient(context.http),
    props.moduleAlias,
    { valid: () => Boolean(user?.value?.userId) && identity() === owner, active: () => active },
    createModuleMenuReceiptStore(
      owner,
      props.moduleAlias,
      () => Boolean(user?.value?.userId) && identity() === owner,
    ),
    navigation?.refreshMenus,
  );
async function activate() {
  if (active) return;
  active = true;
  const current = ++generation;
  workspace?.focus(session);
  workspace?.showEditor(session);
  if (!session.ready.value) await session.load();
  if (!active || generation !== current) return;
  const key = host?.activePageInstanceKey();
  if (key)
    unregister = host?.registry.register({
      pageInstanceKey: key,
      contextRevision: () => `${props.moduleAlias}:${session.revision.value}`,
      surface: {
        describe: () => ({
          surface: 'module-menu',
          title: '添加业务菜单入口',
          facts: workspace?.describe() ?? { moduleAlias: props.moduleAlias },
        }),
        capabilities: () => host?.capabilities?.() ?? [],
        requestTurn: createAssistantTurnRequester(context.http),
      },
    });
}
function deactivate() {
  active = false;
  generation++;
  unregister?.();
  unregister = undefined;
  workspace?.hideEditor(session);
}
onMounted(activate);
onActivated(activate);
onDeactivated(deactivate);
onBeforeUnmount(() => {
  deactivate();
  if (!workspace) session.dispose();
});
</script>
<template>
  <section class="module-menu-governance">
    <h2>业务入口</h2>
    <ModuleMenuEditor :session="session" show-save />
  </section>
</template>
<style scoped>
.module-menu-governance {
  overflow: auto;
  padding: 20px;
  max-width: 760px;
}
</style>
