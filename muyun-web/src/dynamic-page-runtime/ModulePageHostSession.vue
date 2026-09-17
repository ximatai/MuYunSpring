<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue';
import { ModuleHttpProvider, useModuleContext, withHttpHeaders } from '@muyun/web-core';
import type { StandardModulePageDescriptor } from '@muyun/web-contracts';
import { RecordPanelButton, RecordPanelState, type QueryListRecord } from '@muyun/platform-components';
import ModulePageHostRuntime from './ModulePageHostRuntime.vue';
import ModulePageBusinessSession from './ModulePageBusinessSession';
import { useTenantScopeController } from './useTenantScopeController';
import type { ModulePageSessionView } from './useModulePageSession';

defineOptions({ name: 'ModulePageHostSession' });
const props = defineProps<{
  descriptor: StandardModulePageDescriptor;
  requireConfiguredPage?: boolean;
  recordOnly?: { recordId: string; renderMode?: 'inline' | 'portal'; scope?: 'tab' | 'viewport' };
  /** Rebuilds only the frozen business session; the tenant controller stays mounted. */
  reloadKey?: number;
}>();
const emit = defineEmits<{
  'interaction-state-change': [state: { editing: boolean; busy: boolean; dirty?: boolean }];
  'record-only-change': [mutation: { type: 'saved' | 'deleted' | 'unavailable'; record?: QueryListRecord }];
  'record-only-close': [];
}>();
const controlContext = useModuleContext<QueryListRecord>({
  moduleAlias: props.descriptor.target.moduleAlias,
});
const blocked = ref(false);
const tenantController = useTenantScopeController(controlContext, Boolean(props.recordOnly), blocked);
const tenantScope = tenantController.selected;
const generation = ref(0);
const pending = ref(true);
const failure = ref<string>();
const view = shallowRef<ModulePageSessionView>();
const sessionHttp = computed(() => {
  // Every generation gets a fresh transport. Existing sessions retain the one
  // they captured, so late responses cannot bleed into the replacement session.
  void generation.value;
  if (props.recordOnly) return controlContext.http;
  const tenantId = tenantScope.value?.id == null ? undefined : String(tenantScope.value.id);
  return withHttpHeaders(controlContext.http, { 'X-MuYun-Tenant-Id': tenantId });
});
function startBusinessSession() {
  generation.value += 1;
  pending.value = true;
  failure.value = undefined;
}
watch([tenantScope, () => props.reloadKey], startBusinessSession, { flush: 'sync' });
function acceptSession(session: ModulePageSessionView) {
  view.value = session;
  failure.value = undefined;
  pending.value = false;
}
function rejectSession(session: ModulePageSessionView, message: string) {
  // Keep the stable renderer mounted after an initial business bootstrap error.
  // It owns the tenant explorer, so a system user can select a tenant and retry.
  if (!view.value) view.value = session;
  failure.value = message;
  pending.value = false;
}
function interactionChanged(state: { editing: boolean; busy: boolean; dirty?: boolean }) {
  blocked.value = state.editing || state.busy;
  emit('interaction-state-change', state);
}
function refreshList() {
  if (pending.value || failure.value) return;
  view.value?.refreshList();
}
defineExpose({ refreshList, retry: startBusinessSession });
</script>

<template>
  <ModulePageBusinessSession
    :key="generation"
    :descriptor="descriptor"
    :require-configured-page="requireConfiguredPage"
    :record-only="recordOnly"
    :tenant-scope="tenantScope"
    :tenant-controller="tenantController"
    :http="recordOnly ? undefined : sessionHttp"
    @ready="acceptSession"
    @failed="rejectSession"
    @interaction-state-change="interactionChanged"
    @record-only-change="emit('record-only-change', $event)"
    @record-only-close="emit('record-only-close')"
  />
  <ModuleHttpProvider :http="sessionHttp">
    <ModulePageHostRuntime
      v-if="view"
      :session="view"
      :tenant-controller="tenantController"
      :pending="pending"
      :business-error="failure"
      @retry="startBusinessSession"
    />
    <section v-else-if="failure" class="module-page-host-session-error" role="alert">
      <RecordPanelState :description="failure" />
      <RecordPanelButton type="link" @click="startBusinessSession">重试</RecordPanelButton>
    </section>
    <RecordPanelState v-else loading loading-tip="加载页面入口" description="" />
  </ModuleHttpProvider>
</template>
