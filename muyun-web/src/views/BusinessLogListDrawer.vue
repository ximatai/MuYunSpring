<script setup lang="ts">
import {
  useLanguageContext,
  normalizeError,
  userFacingErrorMessage,
  OperationUsageError,
  codeOperationErrorMessage,
  resolveOperationErrorMessage,
} from '@muyun/web-core';
import { computed, ref, shallowRef } from 'vue';
import {
  DateTimeText,
  RecordDetailDrawer,
  RecordQueryListPanel,
  presentPlatformError,
  type RecordQueryListColumn,
  type ReferencePickerConfig,
  createUserReferencePickerProvider,
  userReferencePickerColumns,
} from '@muyun/platform-components';
import { useModuleContext } from '@muyun/web-core';
import type { WebQueryRequest } from '@muyun/web-contracts';
import { UiActionButton, UiEmpty, UiError, UiSpin } from '@muyun/vue-ui-antdv';
import {
  createBusinessLogClient,
  businessLogStatisticTitle,
  type BusinessLogEventView,
  type BusinessLogStatistics,
  type BusinessLogSurface,
} from './businessLogClient';
import BusinessLogRetentionControl from './BusinessLogRetentionControl.vue';
import type { BusinessLogEventType } from './businessLogRetentionClient';
import type { RecordActionItem } from '@muyun/platform-components';

const language = useLanguageContext();

defineOptions({ name: 'BusinessLogListDrawer' });

const props = defineProps<{
  surface: BusinessLogSurface;
  moduleAlias: string;
  title: string;
}>();

const moduleContext = useModuleContext<BusinessLogEventView>({ moduleAlias: props.moduleAlias });
const client = createBusinessLogClient(moduleContext.http, props.surface);
const operatorReferencePicker: ReferencePickerConfig = {
  get title() {
    return language.t('platform.screen.BusinessLogListDrawer.select-an-operator', '选择操作用户');
  },
  get placeholder() {
    return language.t('platform.screen.UserPicker.searchPlaceholder', '按账号或用户 ID 搜索');
  },
  get searchPlaceholder() {
    return language.t('platform.screen.UserPicker.searchPlaceholder', '按账号或用户 ID 搜索');
  },
  mode: 'dialog',
  columns: userReferencePickerColumns,
  provider: createUserReferencePickerProvider({
    sourceIdentity: {
      targetModuleAlias: 'iam.user',
      source: { kind: 'businessPurpose', id: `${props.moduleAlias}:operators` },
    },
    searchPage: async ({ keyword, pageNum, pageSize, scope }) => {
      const page = await client.operatorCandidates({ keyword, pageNum, pageSize, ...scope });
      return { records: page.records, total: page.total, navigation: page.navigation };
    },
    resolveUsers: async (ids) => (await client.operatorCandidates({ selectedIds: ids })).selectedRecords,
  }),
};
const selectedEvent = ref<BusinessLogEventView>();
const detailLoading = ref(false);
const detailFailure = shallowRef<Error>();
const detailError = computed(() => (detailFailure.value ? displayError(detailFailure.value) : undefined));
const diagnostic = ref<Record<string, unknown>>();
const diagnosticLoading = ref(false);
const diagnosticFailure = shallowRef<Error>();
const diagnosticError = computed(() =>
  diagnosticFailure.value ? displayError(diagnosticFailure.value) : undefined,
);
const statisticsOpen = ref(false);
const actionStatistics = ref<BusinessLogStatistics>();
const pageAccessStatistics = ref<BusinessLogStatistics>();
const statisticsLoading = ref(false);
const statisticsFailure = shallowRef<Error>();
const statisticsError = computed(() =>
  statisticsFailure.value ? displayError(statisticsFailure.value) : undefined,
);
const lastQuery = ref<WebQueryRequest>();

const surfaceConfig = computed(() => configBySurface.value[props.surface]);
const isActivity = computed(() => props.surface === 'activity');
const isRequestError = computed(() => props.surface === 'request-error');
const retentionEventTypes = computed<BusinessLogEventType[]>(() => {
  return {
    login: ['LOGIN'],
    activity: ['ACTION', 'PAGE_ACCESS'],
    'request-error': ['REQUEST_ERROR'],
  }[props.surface] as BusinessLogEventType[];
});
const canViewInternalDiagnostic = computed(() => moduleContext.can('viewInternalDiagnostic') === true);
const tableColumns = computed<RecordQueryListColumn[]>(() => surfaceConfig.value.columns);
const selectedDetailRows = computed(() => (selectedEvent.value ? detailRows(selectedEvent.value) : []));
const diagnosticRows = computed(() => valueRows(diagnostic.value));
const statisticsRows = computed(() => [
  ...statisticRows(
    language.t('platform.workflow-task-editor.business-action', '业务动作'),
    actionStatistics.value,
  ),
  ...statisticRows(
    language.t('platform.screen.BusinessLogListDrawer.page-access', '页面访问'),
    pageAccessStatistics.value,
  ),
]);
const statisticsIncomplete = computed(
  () => actionStatistics.value?.complete === false || pageAccessStatistics.value?.complete === false,
);

async function openDetail(record: Record<string, unknown>) {
  const event = record as BusinessLogEventView;
  const eventId = event.eventId;
  if (!eventId) return;
  selectedEvent.value = event;
  detailFailure.value = undefined;
  diagnostic.value = undefined;
  diagnosticFailure.value = undefined;
  detailLoading.value = true;
  try {
    selectedEvent.value = await client.detail(eventId);
  } catch (error) {
    detailFailure.value = errorMessage(error);
    presentPlatformError(error, { language, source: 'business-log-detail', phase: 'load' });
  } finally {
    detailLoading.value = false;
  }
}

async function openStatistics() {
  if (!isActivity.value || statisticsLoading.value) return;
  statisticsOpen.value = true;
  statisticsLoading.value = true;
  statisticsFailure.value = undefined;
  try {
    const request = lastQuery.value ?? defaultQueryRequest();
    const [actions, pageAccess] = await Promise.all([
      client.actionStatistics?.(request),
      client.pageAccessStatistics?.(request),
    ]);
    actionStatistics.value = actions;
    pageAccessStatistics.value = pageAccess;
  } catch (error) {
    statisticsFailure.value = errorMessage(error);
    presentPlatformError(error, { language, source: 'business-log-statistics', phase: 'load' });
  } finally {
    statisticsLoading.value = false;
  }
}

async function loadDiagnostic() {
  const eventId = selectedEvent.value?.eventId;
  if (!client.diagnostic || !eventId || diagnosticLoading.value) return;
  diagnosticLoading.value = true;
  diagnosticFailure.value = undefined;
  try {
    diagnostic.value = await client.diagnostic(eventId);
  } catch (error) {
    diagnosticFailure.value = errorMessage(error);
    presentPlatformError(error, { language, source: 'business-log-diagnostic', phase: 'load' });
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

function referencePickerOf(field: { name: string }) {
  return field.name === 'operatorId' ? operatorReferencePicker : undefined;
}

function displayValue(column: string, event: BusinessLogEventView) {
  switch (column) {
    case 'outcome':
      return outcomeLabel(event.outcome);
    case 'mutationSource':
      return mutationSourceLabel(event.mutationSource);
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

function mutationSourceLabel(value: string | undefined) {
  return (
    {
      BUSINESS: language.t('platform.screen.BusinessLogListDrawer.business-data-write', '业务写入'),
      ACTION: language.t('platform.workflow-task-editor.business-action', '业务动作'),
      SYSTEM: language.t('platform.screen.BusinessLogListDrawer.system-processing', '系统处理'),
      WRITE_BACK: language.t('platform.screen.BusinessLogListDrawer.platform-write-back', '平台回写'),
    }[value ?? ''] ??
    value ??
    '—'
  );
}

function outcomeLabel(value: string | undefined) {
  return (
    {
      SUCCESS: language.t('platform.screen.BusinessLogListDrawer.success', '成功'),
      FAILURE: language.t('platform.screen.BusinessLogListDrawer.failure', '失败'),
      REJECTED: language.t('platform.screen.BusinessLogListDrawer.rejected', '拒绝'),
    }[value ?? ''] ??
    value ??
    '—'
  );
}

function detailRows(event: BusinessLogEventView) {
  return [
    [language.t('platform.screen.BusinessLogListDrawer.occurred-at', '发生时间'), event.occurredAt],
    [language.t('platform.screen.BusinessLogListDrawer.event-type', '事件类型'), event.eventType],
    [language.t('platform.screen.BusinessLogListDrawer.outcome', '结果'), event.outcome],
    [
      language.t('platform.user-picker.column.employee-name', '职员姓名'),
      event.operatorIdentity?.employeeName,
    ],
    [
      language.t('platform.screen.BusinessLogListDrawer.operator', '操作用户'),
      event.operatorIdentity?.username ?? event.operatorId,
    ],
    [language.t('platform.screen.CurrentUserProfileDialog.sign-in-account', '登录账号'), loginAccount(event)],
    [language.t('platform.screen.BusinessLogListDrawer.user-id', '用户 ID'), event.operatorId],
    [language.t('platform.user-picker.axis.organization', '机构'), event.operatorIdentity?.organizationName],
    [
      language.t('platform.screen.BusinessLogListDrawer.organization-id', '机构 ID'),
      event.operatorIdentity?.organizationId ?? event.operatorOrganizationId,
    ],
    [language.t('platform.user-picker.axis.department', '部门'), event.operatorIdentity?.departmentName],
    [
      language.t('platform.screen.BusinessLogListDrawer.department-id', '部门 ID'),
      event.operatorIdentity?.departmentId,
    ],
    [language.t('platform.screen.WorkflowWorkbenchView.business-module', '业务模块'), event.moduleAlias],
    [language.t('platform.governance.tab.actions', '动作'), event.actionCode],
    [language.t('platform.screen.BusinessLogListDrawer.entity', '实体'), event.entityAlias],
    [language.t('platform.screen.BusinessLogListDrawer.record-id', '记录 ID'), event.recordId],
    [
      language.t('platform.screen.BusinessLogListDrawer.change-source', '变更来源'),
      mutationSourceLabel(event.mutationSource),
    ],
    [language.t('platform.screen.BusinessLogListDrawer.trace-id', '追踪 ID'), event.traceId],
    ...valueRows(event.details, ['outcome', 'entityAlias', 'recordId', 'mutationSource']).map(
      (item) => [item.key, item.value] as const,
    ),
  ]
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => ({ key, value: readableValue(value) }));
}

function statisticRows(kind: string, statistics: BusinessLogStatistics | undefined) {
  return (statistics?.items ?? []).map((item) => ({
    key: `${kind}:${item.key}`,
    kind,
    title: businessLogStatisticTitle(item, language),
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
  return [
    {
      key: 'detail',
      title: language.t('platform.screen.PageCompositionEditor.details', '详情'),
      primary: true,
      iconName: 'eye',
    },
  ];
}

function handleRowAction(action: RecordActionItem, record: Record<string, unknown>) {
  if (action.key === 'detail') {
    void openDetail(record);
  }
}

function valueRows(value: Record<string, unknown> | undefined, excludedKeys: string[] = []) {
  const excluded = new Set(excludedKeys);
  return Object.entries(value ?? {})
    .filter(([key]) => !excluded.has(key))
    .map(([key, item]) => ({ key, value: readableValue(item) }));
}

function textValue(value: unknown) {
  return typeof value === 'string' || typeof value === 'number' ? String(value) : undefined;
}

function readableValue(value: unknown) {
  if (value === undefined || value === null || value === '') return '—';
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2);
}

function errorMessage(error: unknown) {
  return error instanceof Error
    ? error
    : new OperationUsageError(
        codeOperationErrorMessage(
          language,
          'platform.screen.BusinessLogListDrawer.the-request-did-not-complete-try-again-later',
          '请求未完成，请稍后重试。',
        ),
      );
}

const configBySurface = computed<Record<BusinessLogSurface, { columns: RecordQueryListColumn[] }>>(() => ({
  login: {
    columns: [
      {
        key: 'outcome',
        title: language.t('platform.screen.BusinessLogListDrawer.outcome', '结果'),
        width: '96px',
      },
      {
        key: 'occurredAt',
        title: language.t('platform.screen.BusinessLogListDrawer.occurred-at', '发生时间'),
        width: '176px',
      },
      {
        key: 'loginAccount',
        title: language.t('platform.screen.CurrentUserProfileDialog.sign-in-account', '登录账号'),
        width: '200px',
      },
    ],
  },
  activity: {
    columns: [
      {
        key: 'outcome',
        title: language.t('platform.screen.BusinessLogListDrawer.outcome', '结果'),
        width: '96px',
      },
      {
        key: 'occurredAt',
        title: language.t('platform.screen.BusinessLogListDrawer.occurred-at', '发生时间'),
        width: '176px',
      },
      {
        key: 'username',
        title: language.t('platform.screen.BusinessLogListDrawer.operator', '操作用户'),
        width: '160px',
      },
      {
        key: 'moduleAlias',
        title: language.t('platform.screen.WorkflowWorkbenchView.business-module', '业务模块'),
        width: '190px',
      },
      { key: 'actionCode', title: language.t('platform.governance.tab.actions', '动作'), width: '150px' },
      {
        key: 'recordId',
        title: language.t('platform.screen.BusinessLogListDrawer.record-id', '记录 ID'),
        width: '170px',
      },
      {
        key: 'mutationSource',
        title: language.t('platform.screen.BusinessLogListDrawer.change-source', '变更来源'),
        width: '120px',
      },
    ],
  },
  'request-error': {
    columns: [
      {
        key: 'occurredAt',
        title: language.t('platform.screen.BusinessLogListDrawer.occurred-at', '发生时间'),
        width: '176px',
      },
      {
        key: 'moduleAlias',
        title: language.t('platform.screen.WorkflowWorkbenchView.business-module', '业务模块'),
        width: '190px',
      },
      {
        key: 'errorCode',
        title: language.t('platform.screen.BusinessLogListDrawer.error-code', '错误码'),
        width: '150px',
      },
      {
        key: 'httpStatus',
        title: language.t('platform.screen.BusinessLogListDrawer.http-status', 'HTTP 状态'),
        width: '112px',
      },
      {
        key: 'summary',
        title: language.t('platform.screen.BusinessLogListDrawer.error-summary', '错误摘要'),
        width: '260px',
      },
    ],
  },
}));
function displayError(error: Error): string {
  return error instanceof OperationUsageError
    ? resolveOperationErrorMessage(error, language)
    : userFacingErrorMessage(normalizeError(error), language);
}
</script>

<template>
  <RecordQueryListPanel
    class="business-log-list"
    :context="moduleContext"
    :title="title"
    :columns="tableColumns"
    row-key="eventId"
    :show-recycle-bin="false"
    :page-size="50"
    :page-size-options="[20, 50, 100]"
    :reference-picker-of="referencePickerOf"
    :empty-description="
      language.t('platform.screen.BusinessLogListDrawer.no-logs-match-the-criteria', '暂无符合条件的日志')
    "
    :row-actions-of="detailRowActions"
    :row-actions-title="language.t('platform.screen.RecordPermissionDialog.operation', '操作')"
    action-column-width="88px"
    @record-activate="openDetail"
    @row-action="handleRowAction"
    @queried="rememberQuery"
  >
    <template #operations>
      <UiActionButton v-if="isActivity" :loading="statisticsLoading" @click="openStatistics">
        {{ language.t('platform.screen.BusinessLogListDrawer.usage-statistics', '使用统计') }}
      </UiActionButton>
      <BusinessLogRetentionControl :event-types="retentionEventTypes" />
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
    :title="
      language.t('platform.screen.BusinessLogListDrawer.value-details', '{value1}详情', { value1: title })
    "
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
    <UiSpin
      v-if="detailLoading"
      :tip="language.t('platform.screen.BusinessLogListDrawer.loading-details', '加载详情')"
    />
    <UiError
      v-else-if="detailError"
      :title="
        language.t('platform.screen.BusinessLogListDrawer.failed-to-load-log-details', '日志详情加载失败')
      "
      :message="detailError"
    />
    <dl v-else-if="selectedEvent" class="business-log-list__detail">
      <template v-for="row in selectedDetailRows" :key="row.key">
        <dt>{{ row.key }}</dt>
        <dd>
          <pre>{{ row.value }}</pre>
        </dd>
      </template>
    </dl>
    <UiEmpty
      v-else
      :description="language.t('platform.screen.BusinessLogListDrawer.no-log-selected', '未选择日志')"
    />

    <section
      v-if="isRequestError && selectedEvent && canViewInternalDiagnostic"
      class="business-log-list__diagnostic"
    >
      <UiActionButton :loading="diagnosticLoading" @click="loadDiagnostic">{{
        language.t('platform.screen.BusinessLogListDrawer.view-internal-diagnostics', '查看内部诊断')
      }}</UiActionButton>
      <small>{{
        language.t(
          'platform.screen.BusinessLogListDrawer.only-administrators-with-platform-wide-scope-can-view-this-information',
          '仅具备平台范围的管理员可查看。',
        )
      }}</small>
      <UiError
        v-if="diagnosticError"
        :title="
          language.t(
            'platform.screen.BusinessLogListDrawer.internal-diagnostics-unavailable',
            '内部诊断不可用',
          )
        "
        :message="diagnosticError"
      />
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
    :title="
      language.t(
        'platform.screen.BusinessLogListDrawer.business-activity-usage-statistics',
        '业务活动使用统计',
      )
    "
    render-mode="inline"
    :subtitle="
      language.t(
        'platform.screen.BusinessLogListDrawer.statistics-follow-the-current-query-criteria-and-the-signed-in',
        '统计口径遵循当前标准查询条件与登录账号的数据范围。',
      )
    "
    :close-on-outside="true"
    @close="statisticsOpen = false"
  >
    <UiSpin
      v-if="statisticsLoading"
      :tip="language.t('platform.screen.BusinessLogListDrawer.loading-usage-statistics', '加载使用统计')"
    />
    <UiError
      v-else-if="statisticsError"
      :title="
        language.t(
          'platform.screen.BusinessLogListDrawer.failed-to-load-usage-statistics',
          '使用统计加载失败',
        )
      "
      :message="statisticsError"
    />
    <template v-else>
      <p v-if="statisticsIncomplete" class="business-log-list__hint">
        {{
          language.t(
            'platform.screen.BusinessLogListDrawer.these-statistics-cover-only-the-most-recent-accessible-logs',
            '当前统计只覆盖可读取的最近日志。',
          )
        }}
      </p>
      <dl v-if="statisticsRows.length > 0" class="business-log-list__detail">
        <template v-for="row in statisticsRows" :key="row.key">
          <dt>{{ row.kind }} · {{ row.title }}</dt>
          <dd>{{ row.count }}</dd>
        </template>
      </dl>
      <UiEmpty
        v-else
        :description="
          language.t(
            'platform.screen.BusinessLogListDrawer.no-business-activity-available-for-statistics',
            '暂无可统计的业务活动',
          )
        "
      />
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
