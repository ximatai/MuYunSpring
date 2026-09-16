<script setup lang="ts">
import { computed } from 'vue';
import { ModuleContextProvider, type HttpClient } from '@muyun/web-core';
import {
  ReferenceRecordDetailBrowser,
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
}>();

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
    scope: props.scope ?? (renderMode === 'inline' ? 'tab' : 'viewport'),
  };
});
</script>

<template>
  <ReferenceRecordDetailBrowser
    :http="props.http"
    :browser="props.browser"
    :inline-anchor="presentation.renderMode === 'inline'"
    @record-change="emit('record-change', $event)"
  >
    <slot />
    <template #full-surface="{ context, record, reportMutation, setBusy, close }">
      <ModuleContextProvider v-if="context && record" :context="context">
        <ModulePageHost
          :key="`${context.moduleAlias}:${String(record.id)}`"
          :descriptor="recordOnlyDescriptor(context.moduleAlias)"
          :record-only="{
            recordId: String(record.id),
            renderMode: presentation.renderMode,
            scope: presentation.scope,
          }"
          @interaction-state-change="setBusy($event.busy)"
          @record-only-close="close"
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
