<script setup lang="ts">
import { computed, ref } from 'vue';
import { useLanguageContext } from '@muyun/web-core';
import {
  ModuleReferenceRecordDetailBrowser,
  type ModulePageDetailSectionContext,
} from '@muyun/dynamic-page-runtime';
import { UiButton, UiDataTable } from '@muyun/vue-ui-antdv';
import { diagnosticPages, jsonObject, statistic } from './federationPresentation';

const props = defineProps<{ context: ModulePageDetailSectionContext }>();
const language = useLanguageContext();
const recordBrowser = ref<InstanceType<typeof ModuleReferenceRecordDetailBrowser>>();
const statistics = computed(() => jsonObject(props.context.record.statistics));
const failures = computed(() => diagnosticPages(props.context.record.failedPages));
const rows = computed(() =>
  [
    ['organizations', language.t('platform.federation.organizations', '已读取部门')],
    ['people', language.t('platform.federation.people', '已读取人员')],
    ['duplicates', language.t('platform.federation.duplicates', '重复观测')],
    ['conflicts', language.t('platform.federation.conflicts', '数据冲突')],
    ['failedPages', language.t('platform.federation.failed-pages', '失败页')],
    ['unknownStatus', language.t('platform.federation.unknown-status', '来源状态未知')],
  ].map(([key, label]) => ({ key, label, count: statistic(statistics.value?.[key!]) ?? '—' })),
);
</script>

<template>
  <section class="directory-summary">
    <UiButton
      v-if="context.record.jobRunId"
      @click="recordBrowser?.openRecord('platform.job_run', String(context.record.jobRunId))"
    >
      {{ language.t('platform.federation.open-job', '查看作业运行') }}
    </UiButton>
    <p v-if="context.record.status === 'FAILED'">
      {{
        language.t(
          'platform.federation.failed-run-hint',
          '运行失败，请到关联作业运行查看原因，具备重试权限时可重试。',
        )
      }}
    </p>
    <template v-else-if="context.record.status === 'PARTIAL'">
      <p>
        {{
          language.t(
            'platform.federation.partial-run-hint',
            '本轮部分完成。请先处理失败页、冲突或来源状态问题，再到租户接入策略重新发起预览或同步。',
          )
        }}
      </p>
      <UiButton
        v-if="context.record.policyId"
        @click="recordBrowser?.openRecord('iam.tenant_identity_policy', String(context.record.policyId))"
      >
        {{ language.t('platform.federation.open-policy', '查看租户接入策略') }}
      </UiButton>
    </template>
    <p>
      {{
        language.t(
          'platform.federation.statistics-hint',
          '统计反映目录读取和处理情况，不等同于新增账号数。失败页或冲突需要处理，来源状态未知的人员不会自动开户。',
        )
      }}
    </p>
    <UiDataTable
      :rows="rows"
      :columns="[
        { key: 'label', title: language.t('platform.federation.metric', '统计项') },
        { key: 'count', title: language.t('platform.federation.count', '数量') },
      ]"
      row-key="key"
      :pagination="false"
    />
    <p v-if="!statistics">
      {{
        language.t('platform.federation.statistics-unavailable', '统计暂不可用，请查看作业运行中的诊断信息。')
      }}
    </p>
    <template v-if="failures?.length">
      <p>
        {{
          language.t(
            'platform.federation.failures-hint',
            '失败定位按“阶段:对象标识:页游标”展示，最多保留 100 条。',
          )
        }}
      </p>
      <ul>
        <li v-for="(failure, index) in failures" :key="index">{{ failure }}</li>
      </ul>
    </template>
    <p v-else-if="!failures && context.record.failedPages">
      {{
        language.t('platform.federation.diagnostics-unavailable', '失败页诊断无法解析，请查看关联作业运行。')
      }}
    </p>
    <ModuleReferenceRecordDetailBrowser ref="recordBrowser" :http="context.module.http" />
  </section>
</template>

<style scoped>
.directory-summary {
  display: grid;
  gap: 12px;
}
.directory-summary p,
.directory-summary ul {
  margin: 0;
}
.directory-summary li {
  overflow-wrap: anywhere;
}
</style>
