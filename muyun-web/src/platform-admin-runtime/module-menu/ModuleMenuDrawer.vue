<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import type { ModulePageDrawerContext } from '@muyun/dynamic-page-runtime';
import { useWorkbenchNavigation } from '@muyun/platform-workbench';
import ModuleMenuEditor from './ModuleMenuEditor.vue';
import { useCurrentUserContext } from '../currentUserContext';
import { createModuleMenuReceiptStore } from './moduleMenuReceiptStore';
import { createModuleMenuClient } from './moduleMenuClient';
import { createModuleMenuSession } from './moduleMenuSession';
import { useModuleMenuWorkspace } from './moduleMenuWorkspace';
const props = defineProps<{ context: ModulePageDrawerContext }>();
const alias = String(props.context.record?.alias ?? props.context.record?.id ?? '');
const navigation = useWorkbenchNavigation();
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
let alive = true;
const session =
  workspace?.session(alias) ??
  createModuleMenuSession(
    createModuleMenuClient(props.context.module.http),
    alias,
    { valid: () => alive && Boolean(user?.value?.userId) && identity() === owner, active: () => alive },
    createModuleMenuReceiptStore(owner, alias, () => Boolean(user?.value?.userId) && identity() === owner),
    navigation?.refreshMenus,
  );
const editor = ref<InstanceType<typeof ModuleMenuEditor>>();
workspace?.focus(session);
workspace?.showEditor(session);
if (!session.ready.value) void session.load();
watch(
  [session.canSave, session.saving, session.saved, session.resultUnknown],
  () => {
    props.context.setCloseBlocked(session.saving.value);
    props.context.setTitleActions(
      session.saved.value || session.resultUnknown.value
        ? []
        : [
            {
              key: 'add-menu',
              label: '添加',
              emphasis: 'primary',
              disabled: !session.canSave.value,
              loading: session.saving.value,
              run: () => editor.value?.save(),
            },
          ],
    );
  },
  { immediate: true },
);
watch(session.saved, (record) => {
  if (record) props.context.refreshDetailExtensions();
});
onBeforeUnmount(() => {
  alive = false;
  workspace?.hideEditor(session);
  if (!workspace) session.dispose();
});
</script>
<template><ModuleMenuEditor ref="editor" :session="session" @opened="context.close()" /></template>
