<script setup lang="ts">
import { computed, ref } from 'vue';
import {
  DateTimeText,
  RecordDetailDrawer,
  RecordQueryListPanel,
  presentPlatformError,
  type RecordQueryListColumn,
} from '@muyun/platform-components';
import { useModuleContext } from '@muyun/web-core';
import type { WebQueryRequest } from '@muyun/web-contracts';
import { UiActionButton, UiEmpty, UiError, UiSpin } from '@muyun/vue-ui-antdv';
import {
  createBusinessLogClient,
  type BusinessLogEventView,
  type BusinessLogStatistics,
  type BusinessLogSurface,
} from './businessLogClient';
import type { RecordActionItem } from '@muyun/platform-components';

defineOptions({ name: 'BusinessLogListDrawer' });

const props = defineProps<{
  surface: BusinessLogSurface;
  moduleAlias: string;
  title: string;
}>();

const moduleContext = useModuleContext<BusinessLogEventView>({ moduleAlias: props.moduleAlias });
const client = createBusinessLogClient(moduleContext.http, props.surface);
const selectedEvent = ref<BusinessLogEventView>();
const detailLoading = ref(false);
const detailError = ref<string>();
const diagnostic = ref<Record<string, unknown>>();
const diagnosticLoading = ref(false);
const diagnosticError = ref<string>();
const statisticsOpen = ref(false);
const actionStatistics = ref<BusinessLogStatistics>();
const pageAccessStatistics = ref<BusinessLogStatistics>();
const statisticsLoading = ref(false);
const statisticsError = ref<string>();
const lastQuery = ref<WebQueryRequest>();

const surfaceConfig = computed(() => configBySurface[props.surface]);
const isActivity = computed(() => props.surface === 'activity');
const isRequestError = computed(() => props.surface === 'request-error');
const tableColumns = computed<RecordQueryListColumn[]>(() => surfaceConfig.value.columns);
const selectedDetailRows = computed(() => (selectedEvent.value ? detailRows(selectedEvent.value) : []));
const diagnosticRows = computed(() => valueRows(diagnostic.value));
const statisticsRows = computed(() => [
  ...statisticRows('业务动作', actionStatistics.value),
  ...statisticRows('页面访问', pageAccessStatistics.value),
]);
const statisticsIncomplete = computed(
  () => actionStatistics.value?.complete === false || pageAccessStatistics.value?.complete === false,
);

async function openDetail(record: Record<string, unknown>) {
  const event = record as BusinessLogEventView;
  const eventId = event.eventId;
  if (!eventId) return;
  selectedEvent.value = event;
  detailError.value = undefined;
  diagnostic.value = undefined;
  diagnosticError.value = undefined;
  detailLoading.value = true;
  try {
    selectedEvent.value = await client.detail(eventId);
  } catch (error) {
    detailError.value = errorMessage(error);
    presentPlatformError(error, { source: 'business-log-detail', phase: 'load' });
  } finally {
    detailLoading.value = false;
  }
}

async function openStatistics() {
  if (!isActivity.value || statisticsLoading.value) return;
  statisticsOpen.value = true;
  statisticsLoading.value = true;
  statisticsError.value = undefined;
  try {
    const request = lastQuery.value ?? defaultQueryRequest();
    const [actions, pageAccess] = await Promise.all([
      client.actionStatistics?.(request),
      client.pageAccessStatistics?.(request),
    ]);
    actionStatistics.value = actions;
    pageAccessStatistics.value = pageAccess;
  } catch (error) {
    statisticsError.value = errorMessage(error);
    presentPlatformError(error, { source: 'business-log-statistics', phase: 'load' });
  } finally {
    statisticsLoading.value = false;
  }
}

async function loadDiagnostic() {
  const eventId = selectedEvent.value?.eventId;
  if (!client.diagnostic || !eventId || diagnosticLoading.value) return;
  diagnosticLoading.value = true;
  diagnosticError.value = undefined;
  try {
    diagnostic.value = await client.diagnostic(eventId);
  } catch (error) {
    diagnosticError.value = errorMessage(error);
    presentPlatformError(error, { source: 'business-log-diagnostic', phase: 'load' });
  } finally {
    diagnosticLoading.value = false;
  }
}

function rememberQuery(request: WebQueryRequest) {
  lastQuery.value = request;
  actionStatistics.value = undefined;
  pageAccessStatistics.value = undefined;
}

function defaultQueryRequest(): WebQueryRequest {
  return { page: { pageNum: 1, pageSize: 50 }, conditions: [] };
}

function displayValue(column: string, event: BusinessLogEventView) {
  switch (column) {
    case 'outcome':
      return outcomeLabel(event.outcome);
    case 'employeeName':
      return event.operatorIdentity?.employeeName ?? '—';
    case 'username':
      return event.operatorIdentity?.username ?? event.operatorId ?? '—';
    case 'loginAccount':
      return loginAccount(event) ?? '—';
    case 'organizationName':
      return event.operatorIdentity?.organizationName ?? '—';
    case 'departmentName':
      return event.operatorIdentity?.departmentName ?? '—';
    case 'errorCode':
      return textValue(event.details?.errorCode) ?? '—';
    case 'httpStatus':
      return textValue(event.details?.httpStatus) ?? '—';
    default:
      return readableValue(event[column]);
  }
}

function outcomeLabel(value: string | undefined) {
  return (
    {
      SUCCESS: '成功',
      FAILURE: '失败',
      REJECTED: '拒绝',
    }[value ?? ''] ??
    value ??
    '—'
  );
}

function detailRows(event: BusinessLogEventView) {
  return [
    ['发生时间', event.occurredAt],
    ['事件类型', event.eventType],
    ['结果', event.outcome],
    ['职员姓名', event.operatorIdentity?.employeeName],
    ['操作用户', event.operatorIdentity?.username ?? event.operatorId],
    ['登录账号', loginAccount(event)],
    ['用户 ID', event.operatorId],
    ['机构', event.operatorIdentity?.organizationName],
    ['机构 ID', event.operatorIdentity?.organizationId ?? event.operatorOrganizationId],
    ['部门', event.operatorIdentity?.departmentName],
    ['部门 ID', event.operatorIdentity?.departmentId],
    ['业务模块', event.moduleAlias],
    ['动作', event.actionCode],
    ['追踪 ID', event.traceId],
    ...valueRows(event.details).map((item) => [item.key, item.value] as const),
  ]
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => ({ key, value: readableValue(value) }));
}

function statisticRows(kind: string, statistics: BusinessLogStatistics | undefined) {
  return (statistics?.items ?? []).map((item) => ({
    key: `${kind}:${item.key}`,
    kind,
    title: item.title,
    count: item.count,
  }));
}

function loginAccount(event: BusinessLogEventView) {
  if (event.eventType !== 'LOGIN') return undefined;
  return (
    event.loginAccount ??
    textValue(event.details?.confirmedAccount) ??
    textValue(event.details?.claimedAccount)
  );
}

function detailRowActions(): RecordActionItem[] {
  return [{ key: 'detail', title: '详情', primary: true, iconName: 'eye' }];
}

function handleRowAction(action: RecordActionItem, record: Record<string, unknown>) {
  if (action.key === 'detail') {
    void openDetail(record);
  }
}

function valueRows(value: Record<string, unknown> | undefined) {
  return Object.entries(value ?? {}).map(([key, item]) => ({ key, value: readableValue(item) }));
}

function textValue(value: unknown) {
  return typeof value === 'string' || typeof value === 'number' ? String(value) : undefined;
}

function readableValue(value: unknown) {
  if (value === undefined || value === null || value === '') return '—';
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2);
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '请求未完成，请稍后重试。';
}

const configBySurface: Record<BusinessLogSurface, { columns: RecordQueryListColumn[] }> = {
  login: {
    columns: [
      { key: 'outcome', title: '结果', width: '96px' },
      { key: 'occurredAt', title: '发生时间', width: '176px' },
      { key: 'loginAccount', title: '登录账号', width: '200px' },
    ],
  },
  activity: {
    columns: [
      { key: 'occurredAt', title: '发生时间', width: '176px' },
      { key: 'username', title: '操作用户', width: '160px' },
      { key: 'moduleAlias', title: '业务模块', width: '190px' },
      { key: 'actionCode', title: '动作', width: '150px' },
    ],
  },
  'request-error': {
    columns: [
      { key: 'occurredAt', title: '发生时间', width: '176px' },
      { key: 'moduleAlias', title: '业务模块', width: '190px' },
      { key: 'errorCode', title: '错误码', width: '150px' },
      { key: 'httpStatus', title: 'HTTP 状态', width: '112px' },
      { key: 'summary', title: '错误摘要', width: '260px' },
    ],
  },
};
</script>

<template>
  <RecordQueryListPanel
    class="business-log-list"
    :context="moduleContext"
    :title="title"
    subtitle="按事件发生时间检索，数据范围由当前登录账号的管理范围决定。"
    :columns="tableColumns"
    row-key="eventId"
    :show-recycle-bin="false"
    :page-size="50"
    :page-size-options="[20, 50, 100]"
    empty-description="暂无符合条件的日志"
    :row-actions-of="detailRowActions"
    row-actions-title="操作"
    action-column-width="88px"
    @select="openDetail"
    @row-action="handleRowAction"
    @queried="rememberQuery"
  >
    <template #toolbarActions>
      <UiActionButton v-if="isActivity" :loading="statisticsLoading" @click="openStatistics">
        使用统计
      </UiActionButton>
    </template>
    <template #cell="{ column, record }">
      <DateTimeText v-if="column.key === 'occurredAt'" :value="(record as BusinessLogEventView).occurredAt" />
      <span v-else :class="{ 'business-log-list__summary': column.key === 'summary' }">
        {{ displayValue(column.key, record as BusinessLogEventView) }}
      </span>
    </template>
  </RecordQueryListPanel>

  <RecordDetailDrawer
    :open="Boolean(selectedEvent)"
    :title="`${title}详情`"
    render-mode="inline"
    :subtitle="
      selectedEvent
        ? (selectedEvent.operatorIdentity?.employeeName ??
          selectedEvent.operatorIdentity?.username ??
          selectedEvent.operatorId)
        : undefined
    "
    :close-on-outside="true"
    @close="selectedEvent = undefined"
  >
    <UiSpin v-if="detailLoading" tip="加载详情" />
    <UiError v-else-if="detailError" title="日志详情加载失败" :message="detailError" />
    <dl v-else-if="selectedEvent" class="business-log-list__detail">
      <template v-for="row in selectedDetailRows" :key="row.key">
        <dt>{{ row.key }}</dt>
        <dd>
          <pre>{{ row.value }}</pre>
        </dd>
      </template>
    </dl>
    <UiEmpty v-else description="未选择日志" />

    <section v-if="isRequestError && selectedEvent" class="business-log-list__diagnostic">
      <UiActionButton :loading="diagnosticLoading" @click="loadDiagnostic">查看内部诊断</UiActionButton>
      <small>仅具备平台范围的管理员可查看。</small>
      <UiError v-if="diagnosticError" title="内部诊断不可用" :message="diagnosticError" />
      <dl v-else-if="diagnostic" class="business-log-list__detail">
        <template v-for="row in diagnosticRows" :key="row.key">
          <dt>{{ row.key }}</dt>
          <dd>
            <pre>{{ row.value }}</pre>
          </dd>
        </template>
      </dl>
    </section>
  </RecordDetailDrawer>

  <RecordDetailDrawer
    :open="statisticsOpen"
    title="业务活动使用统计"
    render-mode="inline"
    subtitle="统计口径遵循当前标准查询条件与登录账号的数据范围。"
    :close-on-outside="true"
    @close="statisticsOpen = false"
  >
    <UiSpin v-if="statisticsLoading" tip="加载使用统计" />
    <UiError v-else-if="statisticsError" title="使用统计加载失败" :message="statisticsError" />
    <template v-else>
      <p v-if="statisticsIncomplete" class="business-log-list__hint">当前统计只覆盖可读取的最近日志。</p>
      <dl v-if="statisticsRows.length > 0" class="business-log-list__detail">
        <template v-for="row in statisticsRows" :key="row.key">
          <dt>{{ row.kind }} · {{ row.title }}</dt>
          <dd>{{ row.count }}</dd>
        </template>
      </dl>
      <UiEmpty v-else description="暂无可统计的业务活动" />
    </template>
  </RecordDetailDrawer>
</template>

<style scoped>
.business-log-list__summary {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.business-log-list__detail {
  display: grid;
  grid-template-columns: minmax(120px, 0.35fr) minmax(0, 1fr);
  gap: 10px 16px;
  margin: 0;
}
.business-log-list__detail dt {
  color: var(--muyun-text-secondary);
}
.business-log-list__detail dd {
  min-width: 0;
  margin: 0;
}
.business-log-list__detail pre {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
}
.business-log-list__diagnostic {
  display: grid;
  gap: 10px;
  margin-top: 20px;
}
.business-log-list__diagnostic small,
.business-log-list__hint {
  color: var(--muyun-text-muted);
}
</style>
