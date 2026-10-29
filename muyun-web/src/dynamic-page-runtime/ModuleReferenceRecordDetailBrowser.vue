<script setup lang="ts">
import { computed, onBeforeUnmount, shallowRef, watch } from 'vue';
import { ModuleContextProvider, type HttpClient } from '@muyun/web-core';
import {
  ReferenceRecordDetailBrowser,
  createReferenceRecordDetailBrowser,
  type ReferenceRecordDetailBrowserState,
  type ReferenceRecordDetailMutation,
} from '@muyun/platform-components';
import ModulePageHost from './ModulePageHost.vue';

/**
 * Ready-to-use standard target-record browser for business applications.
 *
 * The public platform browser stays a lightweight VIEW-only shell. This dynamic
 * runtime composition supplies the standard record-only detail lifecycle without
 * inheriting a source menu, list query, page context, or navigation session.
 */
defineOptions({ name: 'ModuleReferenceRecordDetailBrowser' });

const props = withDefaults(
  defineProps<{
    http?: HttpClient;
    browser?: ReferenceRecordDetailBrowserState;
    inlineAnchor?: boolean;
    renderMode?: 'inline' | 'portal';
    scope?: 'tab' | 'viewport';
  }>(),
  { http: undefined, browser: undefined, inlineAnchor: false, renderMode: undefined, scope: undefined },
);
const emit = defineEmits<{
  'record-change': [mutation: ReferenceRecordDetailMutation];
  'interaction-state-change': [state: { editing: boolean; busy: boolean; dirty?: boolean }];
}>();
const browser = props.browser ?? createReferenceRecordDetailBrowser(requireHttp(props.http));
const host = shallowRef<InstanceType<typeof ModulePageHost>>();
const unregisterLeaveGuard = browser.setBeforeLeave(() => host.value?.confirmLeave() ?? true);
onBeforeUnmount(unregisterLeaveGuard);
defineExpose({ browser, openRecord: browser.open, closeRecord: browser.close });

function reportInteraction(
  moduleAlias: string,
  recordId: string,
  state: { editing: boolean; busy: boolean; dirty?: boolean },
) {
  const active = browser.active.value;
  if (active?.targetModuleAlias !== moduleAlias || active.recordId !== recordId) return;
  browser.setBusy(moduleAlias, recordId, state.busy);
  emit('interaction-state-change', state);
}

function releaseClosedRecord(moduleAlias: string, recordId: string) {
  const active = browser.active.value;
  if (active?.targetModuleAlias === moduleAlias && active.recordId === recordId) browser.dispose();
}

// The standard browser may dismiss a target after a delete or access loss. Reset
// the outer Host in that path as well, even though the target Host is unmounted.
watch(
  () => browser.active.value,
  (active) => {
    if (!active) emit('interaction-state-change', { editing: false, busy: false, dirty: false });
  },
  { flush: 'sync' },
);

function requireHttp(http: HttpClient | undefined) {
  if (!http) throw new Error('Module reference record detail browser requires an HttpClient');
  return http;
}

function recordOnlyDescriptor(moduleAlias: string) {
  return {
    pageType: 'dynamic-module' as const,
    openMode: 'dynamic-runner' as const,
    hostType: 'module-page-host' as const,
    target: { moduleAlias },
    tabPolicy: { identity: 'by-target' as const, cacheable: false },
  };
}

const presentation = computed(() => {
  const renderMode = props.renderMode ?? (props.inlineAnchor ? 'inline' : 'portal');
  return {
    renderMode,
    scope: props.scope ?? 'tab',
  };
});
</script>

<template>
  <ReferenceRecordDetailBrowser
    :http="props.http"
    :browser="browser"
    :inline-anchor="presentation.renderMode === 'inline'"
    :scope="presentation.scope"
    @record-change="emit('record-change', $event)"
  >
    <slot />
    <template #full-surface="{ context, record, reportMutation }">
      <ModuleContextProvider v-if="context && record" :context="context">
        <ModulePageHost
          ref="host"
          :key="`${context.moduleAlias}:${String(record.id)}`"
          :descriptor="recordOnlyDescriptor(context.moduleAlias)"
          :record-only="{
            recordId: String(record.id),
            renderMode: presentation.renderMode,
            scope: presentation.scope,
          }"
          @interaction-state-change="reportInteraction(context.moduleAlias, String(record.id), $event)"
          @record-only-close="releaseClosedRecord(context.moduleAlias, String(record.id))"
          @record-only-change="
            (mutation) =>
              reportMutation({
                ...mutation,
                targetModuleAlias: context.moduleAlias,
                recordId: String(record.id),
              })
          "
        />
      </ModuleContextProvider>
    </template>
  </ReferenceRecordDetailBrowser>
</template>
