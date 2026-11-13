<script setup lang="ts">
import { computed, onMounted, ref, watchEffect } from 'vue';
import { actionResultData, useLanguageContext } from '@muyun/web-core';
import type { WebActionResultEnvelope } from '@muyun/web-contracts';
import {
  ModuleReferenceRecordDetailBrowser,
  type ModulePageDrawerContext,
} from '@muyun/dynamic-page-runtime';
import { presentPlatformError } from '@muyun/platform-components';
import { UiButton, UiEmpty, UiSpin, UiDataTable } from '@muyun/vue-ui-antdv';
import { statistic } from './federationPresentation';

const props = defineProps<{
  context: ModulePageDrawerContext;
  action: 'preview' | 'synchronize' | 'scopeImpact';
}>();
const language = useLanguageContext();
const recordBrowser = ref<InstanceType<typeof ModuleReferenceRecordDetailBrowser>>();
const busy = ref(false);
const loaded = ref(false);
const jobId = ref<string>();
type Impact = { scopeId?: string; baseScopeId?: string; runId?: string; counts?: Record<string, number> };
const report = ref<Impact>();
const authorized = ref(false);
const id = String(props.context.record?.id ?? '');
const ready = computed(
  () =>
    Boolean(report.value?.scopeId && report.value?.runId) &&
    ['departments', 'people', 'positions', 'grants'].every(
      (key) => statistic(report.value?.counts?.[key]) !== undefined,
    ),
);
const counts = computed(() =>
  [
    ['departments', language.t('platform.federation.affected-departments', '受影响部门')],
    ['people', language.t('platform.federation.affected-people', '受影响人员')],
    ['positions', language.t('platform.federation.retiring-positions', '将回收的来源任岗')],
    ['grants', language.t('platform.federation.retiring-grants', '将回收的来源授权')],
  ].map(([key, label]) => ({ key, label, count: statistic(report.value?.counts?.[key!]) ?? '—' })),
);

watchEffect(() => {
  props.context.setCloseBlocked(busy.value);
  props.context.setOperation(
    !jobId.value && props.action !== 'scopeImpact'
      ? {
          actions: [
            {
              key: 'execute',
              label:
                props.action === 'preview'
                  ? language.t('platform.federation.preview', '预览通讯录')
                  : language.t('platform.federation.synchronize', '立即同步'),
              emphasis: 'primary',
              loading: busy.value,
              disabled: busy.value || !authorized.value,
              run: () => execute(props.action),
            },
          ],
        }
      : !jobId.value && ready.value && authorized.value
        ? {
            actions: [
              {
                key: 'confirm',
                label: language.t('platform.federation.confirm-scope', '确认范围变更并同步'),
                emphasis: 'primary',
                loading: busy.value,
                disabled: busy.value,
                run: () => execute('confirmScope'),
              },
            ],
          }
        : undefined,
  );
});

onMounted(() => void load());
async function load() {
  busy.value = true;
  loaded.value = false;
  report.value = undefined;
  try {
    await props.context.module.recordActions(id);
    authorized.value =
      props.context.module.can(props.action === 'scopeImpact' ? 'confirmScope' : props.action, id) === true;
    if (props.action === 'scopeImpact') {
      const response = await props.context.module.http.request<WebActionResultEnvelope<Impact>>({
        method: 'POST',
        path: `/iam.tenant_identity_policy/${encodeURIComponent(id)}/scopeImpact`,
      });
      report.value = actionResultData(response);
    }
    loaded.value = true;
  } catch (error) {
    presentPlatformError(error, { language, source: 'directory-policy', phase: 'load' });
  } finally {
    busy.value = false;
  }
}
async function execute(action: string) {
  if (busy.value || jobId.value || !authorized.value || (action === 'confirmScope' && !ready.value)) return;
  busy.value = true;
  try {
    const response = await props.context.module.http.request<WebActionResultEnvelope<string>>({
      method: 'POST',
      path: `/iam.tenant_identity_policy/${encodeURIComponent(id)}/${action}`,
      ...(action === 'confirmScope'
        ? { body: { scopeId: report.value?.scopeId, runId: report.value?.runId } }
        : {}),
    });
    jobId.value = actionResultData(response);
    props.context.refreshList();
    props.context.refreshDetailExtensions();
  } catch (error) {
    // The reviewed report may have expired. Never offer a stale confirmation again.
    if (action === 'confirmScope') report.value = undefined;
    presentPlatformError(error, { language, source: 'directory-policy', phase: 'action' });
  } finally {
    busy.value = false;
  }
}
function openJob() {
  if (jobId.value) void recordBrowser.value?.openRecord('platform.job_run', jobId.value);
}
</script>

<template>
  <section class="directory-policy-drawer">
    <UiSpin :spinning="busy">
      <template v-if="jobId">
        <p>
          {{
            language.t(
              'platform.federation.queued',
              '作业已排队。请在作业运行中查看进度、结果或重试失败运行。',
            )
          }}
        </p>
        <UiButton @click="openJob">{{ language.t('platform.federation.open-job', '查看作业运行') }}</UiButton>
        <p class="directory-policy-reference">{{ jobId }}</p>
      </template>
      <template v-else-if="action === 'scopeImpact'">
        <template v-if="ready">
          <p>
            {{
              language.t(
                'platform.federation.scope-warning',
                '确认后将切换到此预览的范围版本并立即同步，仅回收该身份源维护的任岗与授权，保留手工数据。',
              )
            }}
          </p>
          <UiDataTable
            :columns="[
              { key: 'label', title: language.t('platform.federation.impact', '影响项') },
              { key: 'count', title: language.t('platform.federation.count', '数量') },
            ]"
            :rows="counts"
            row-key="key"
            :pagination="false"
          />
          <p class="directory-policy-reference">
            {{ language.t('platform.federation.scope-version', '范围版本') }}：{{ report?.scopeId }}
          </p>
          <UiButton @click="recordBrowser?.openRecord('iam.directory_sync_run', report!.runId!)">
            {{ language.t('platform.federation.open-preview', '查看预览运行') }}
          </UiButton>
        </template>
        <UiEmpty
          v-else-if="loaded"
          :description="
            language.t(
              'platform.federation.no-report',
              '暂无可确认的范围影响报告。请先保存范围变更并执行预览，待预览成功后刷新报告。',
            )
          "
        />
        <UiButton :disabled="busy" @click="load">
          {{ language.t('platform.federation.refresh-report', '刷新报告') }}
        </UiButton>
      </template>
      <p v-else>
        {{
          action === 'preview'
            ? language.t(
                'platform.federation.preview-hint',
                '预览只读取目录并生成统计，不写入人员数据。存在待确认范围时，预览完成后请查看范围变更影响。',
              )
            : language.t(
                'platform.federation.sync-hint',
                '正式同步使用当前已生效的范围。待确认的范围变更需要先预览并确认，才会生效。',
              )
        }}
      </p>
    </UiSpin>
    <ModuleReferenceRecordDetailBrowser ref="recordBrowser" :http="context.module.http" />
  </section>
</template>

<style scoped>
.directory-policy-drawer {
  display: grid;
  gap: 16px;
}
.directory-policy-drawer p {
  margin: 0 0 16px;
}
.directory-policy-reference {
  color: var(--muyun-text-muted);
  overflow-wrap: anywhere;
}
</style>
