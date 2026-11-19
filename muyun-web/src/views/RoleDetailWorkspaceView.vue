<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue';
import { ModulePageHost, refreshModulePageList } from '@muyun/dynamic-page-runtime';
import type { QueryListRecord } from '@muyun/platform-components';
import type { StandardModulePageDescriptor } from '@muyun/web-contracts';
import { recordPresentationLabel, useLanguageContext } from '@muyun/web-core';
import { useWorkspaceViewHost } from '../platform-admin-runtime/workspaceViewHost';
import { useWorkspaceViewPromotion } from '../platform-admin-runtime/useWorkspaceViewPromotion';
import {
  handOffWorkspaceViewSession,
  registerWorkspaceViewHandoffRecipient,
  replaceWorkspaceViewSession,
  takeWorkspaceViewSession,
} from '../platform-admin-runtime/workspaceViewSessions';
import { roleDetailWorkspaceView, type RoleDetailWorkspaceViewInput } from './roleDetailWorkspaceView';

defineOptions({ name: 'RoleDetailWorkspaceView' });

const props = defineProps<RoleDetailWorkspaceViewInput>();
const emit = defineEmits<{ 'close-workspace': [] }>();
const moduleAlias = 'iam.role';
const language = useLanguageContext();
const workspace = useWorkspaceViewHost();
const drawer = computed(() => workspace?.presentation === 'drawer');
const input = computed<RoleDetailWorkspaceViewInput>(() => ({
  recordId: props.recordId,
  scopeKind: props.scopeKind,
  ...(props.scopeKind === 'platform' ? {} : { scopeId: props.scopeId }),
}));
const scopeKey = computed(() =>
  props.scopeKind === 'platform' ? 'platform' : `${props.scopeKind}:${props.scopeId}`,
);
const descriptor: StandardModulePageDescriptor = {
  pageType: 'dynamic-module',
  openMode: 'dynamic-runner',
  hostType: 'module-page-host',
  layout: 'workspace',
  target: { moduleAlias },
  tabPolicy: { identity: 'by-target' },
};
const host = ref<InstanceType<typeof ModulePageHost>>();
const openedRecord = shallowRef<QueryListRecord>();
const title = computed(
  () =>
    openedRecord.value &&
    (recordPresentationLabel(openedRecord.value, 'title', language) ?? String(openedRecord.value.id)),
);
watch(title, (value) => {
  if (value) workspace?.setTitle(value);
});
const interaction = ref({ busy: false, dirty: false });
const promotion = useWorkspaceViewPromotion({
  view: roleDetailWorkspaceView,
  input: () => (drawer.value ? input.value : undefined),
  title,
  eligibility: () => ({ hasStableIdentity: true, busy: interaction.value.busy }),
  async beforePromote(target) {
    const draft = host.value?.captureRecordDraft();
    return draft
      ? (await handOffWorkspaceViewSession(roleDetailWorkspaceView, target, draft)) === 'accepted'
      : !interaction.value.dirty;
  },
  onPromoted: close,
});
watch(
  [drawer, input],
  ([isDrawer, target], _, onCleanup) => {
    if (!isDrawer)
      onCleanup(registerWorkspaceViewHandoffRecipient(roleDetailWorkspaceView, target, restoreDraft));
  },
  { immediate: true },
);

function close() {
  if (drawer.value) workspace?.dismiss();
  else emit('close-workspace');
}

function readOnlyRole(record: Readonly<QueryListRecord>) {
  return record.systemManaged === true;
}

function restoreDraft(draft: QueryListRecord) {
  return host.value?.restoreRecordDraft(draft) ?? false;
}

async function recordReady(record: QueryListRecord) {
  openedRecord.value = record;
  if (drawer.value) return;
  const target = input.value;
  const draft = takeWorkspaceViewSession<RoleDetailWorkspaceViewInput, QueryListRecord>(
    roleDetailWorkspaceView,
    target,
  );
  if (draft && !(await restoreDraft(draft))) {
    replaceWorkspaceViewSession(roleDetailWorkspaceView, target, draft);
  }
}

function recordChanged({
  type,
  record,
}: {
  type: 'saved' | 'deleted' | 'unavailable';
  record?: QueryListRecord;
}) {
  if (type === 'unavailable') return;
  if (record) openedRecord.value = record;
  refreshModulePageList(moduleAlias);
  if (type === 'deleted') close();
}
</script>

<template>
  <ModulePageHost
    :key="drawer ? 'drawer' : 'tab'"
    ref="host"
    :descriptor="descriptor"
    :record-only="{
      recordId,
      renderMode: 'inline',
      surface: drawer ? undefined : 'workspace',
      pageSelection: { kind: 'roleScope', key: scopeKey },
      readOnly: readOnlyRole,
      promotion,
    }"
    @record-only-ready="recordReady"
    @record-only-change="recordChanged"
    @record-only-close="close"
    @interaction-state-change="interaction = { busy: $event.busy, dirty: $event.dirty === true }"
  />
</template>
