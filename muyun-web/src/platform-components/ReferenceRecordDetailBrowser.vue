<script setup lang="ts">
import { onUnmounted, useSlots } from 'vue';
import type { HttpClient } from '@muyun/web-core';
import type { RecordFormRecord } from './recordFormFieldModel';
import RecordDetailDrawer from './RecordDetailDrawer.vue';
import RecordDetailFields from './RecordDetailFields.vue';
import RecordPanelState from './RecordPanelState.vue';
import {
  createReferenceRecordDetailBrowser,
  provideReferenceRecordDetailBrowser,
  type ReferenceRecordDetailMutation,
  type ReferenceRecordDetailBrowser as ReferenceRecordDetailBrowserState,
} from './referenceRecordDetailBrowser';

defineOptions({ name: 'ReferenceRecordDetailBrowser' });

const props = withDefaults(
  defineProps<{ http?: HttpClient; browser?: ReferenceRecordDetailBrowserState; inlineAnchor?: boolean }>(),
  { http: undefined, browser: undefined, inlineAnchor: false },
);
const browser = props.browser ?? createReferenceRecordDetailBrowser(requireHttp(props.http));
const slots = useSlots();
provideReferenceRecordDetailBrowser(browser);
defineExpose({ browser });
onUnmounted(browser.dispose);

const emit = defineEmits<{
  'record-change': [mutation: ReferenceRecordDetailMutation];
}>();

function reportMutation(mutation: ReferenceRecordDetailMutation) {
  browser.reportMutation(mutation);
  emit('record-change', mutation);
}

function setBusy(busy: boolean) {
  const active = browser.active.value;
  if (active) browser.setBusy(active.targetModuleAlias, active.recordId, busy);
}

function requireHttp(http: HttpClient | undefined) {
  if (!http) throw new Error('Reference record detail browser requires an HttpClient');
  return http;
}

const hasLoadedFullSurface = () =>
  Boolean(browser.active.value?.record && browser.activeContext.value && slots['full-surface']);
</script>

<template>
  <slot />
  <slot
    v-if="hasLoadedFullSurface()"
    name="full-surface"
    :active="browser.active.value"
    :context="browser.activeContext.value"
    :record="browser.active.value!.record"
    :report-mutation="reportMutation"
    :set-busy="setBusy"
    :close="browser.close"
  />
  <RecordDetailDrawer
    v-else
    :open="Boolean(browser.active.value)"
    :title="browser.title.value"
    :render-mode="props.inlineAnchor ? 'inline' : 'portal'"
    :scope="props.inlineAnchor ? 'tab' : 'viewport'"
    @close="browser.close"
  >
    <template v-if="$slots['header-actions']" #header-actions>
      <slot
        name="header-actions"
        :context="browser.activeContext.value"
        :record="browser.active.value?.record"
        :report-mutation="reportMutation"
        :set-busy="setBusy"
      />
    </template>
    <RecordPanelState
      v-if="browser.active.value?.loading"
      loading
      loading-tip="加载记录详情"
      description=""
    />
    <RecordPanelState
      v-else-if="browser.active.value?.failed"
      :description="
        browser.active.value.failureKind === 'notFound'
          ? '记录已不存在，或你已无权查看。'
          : '详情暂时无法加载，请关闭后重试。'
      "
    />
    <RecordPanelState
      v-else-if="browser.active.value?.record && browser.fields.value.size === 0 && !$slots.detail"
      description="当前模块未配置可展示的详情字段。"
    />
    <slot
      v-else-if="browser.active.value?.record && browser.activeContext.value"
      name="detail"
      :context="browser.activeContext.value"
      :record="browser.active.value.record"
      :report-mutation="reportMutation"
      :set-busy="setBusy"
    >
      <RecordDetailFields
        :record="browser.active.value.record as RecordFormRecord"
        :fields="browser.fields.value"
        :file-transfer-context="browser.activeContext.value"
      />
    </slot>
  </RecordDetailDrawer>
</template>
