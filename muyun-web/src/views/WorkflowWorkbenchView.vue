<script setup lang="ts">
import {
  useLanguageContext,
  presentationLabel,
  displayModuleRuntime,
  createModuleContext,
  createWorkflowClient,
  useModuleContext,
  withHttpHeaders,
} from '@muyun/web-core';
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';

import type { ModuleTitleView, WorkflowWorkbenchCard, WorkflowWorkbenchFilters } from '@muyun/web-contracts';
import { refreshModulePageList } from '@muyun/dynamic-page-runtime';
import {
  WorkflowRecordPanel,
  workflowTitle as localizedWorkflowTitle,
  presentPlatformError,
  RecordDetailFields,
  RecordPicker,
  RecordQueryListSurface,
  RecordDetailDrawer,
  ManagementTabs,
  RecordFieldLabel,
  resolveRecordFormFields,
  type RecordFormRecord,
  type RecordFormFieldDescriptor,
} from '@muyun/platform-components';
import { workflowAccountOptions } from '../platform-components/workflowAccountOptions';
import { sameWorkflowTextSource } from '../platform-components/workflowPresentation';
import { usePageDataChangeHandler } from '../platform-admin-runtime/pageRealtime';
import { useCurrentUserContext } from '../platform-admin-runtime/currentUserContext';
import { useWorkspaceViewUnsavedState } from '@muyun/platform-workbench';
import {
  UiButton,
  UiInput,
  UiSelect,
  UiCheckbox,
  UiError,
  confirmAction,
  showSuccessMessage,
} from '@muyun/vue-ui-antdv';

const workflowLanguage = useLanguageContext();
function workflowTitle(value?: string) {
  return localizedWorkflowTitle(value, workflowLanguage);
}
defineOptions({ name: 'WorkflowWorkbenchView' });
const context = useModuleContext(),
  currentUser = useCurrentUserContext(),
  route = useRoute();
const tenantId = ref<string | undefined>(
  currentUser?.value?.system && typeof route.query.tenantId === 'string' ? route.query.tenantId : undefined,
);
const scopedHttp = computed(() =>
  tenantId.value ? withHttpHeaders(context.http, { 'X-MuYun-Tenant-Id': tenantId.value }) : context.http,
);
const accountOptions = computed(() => workflowAccountOptions(scopedHttp.value));
const client = computed(() => createWorkflowClient(scopedHttp.value));
const tenantContext = createModuleContext<Record<string, unknown>>({
  http: context.http,
  language: workflowLanguage,
  moduleAlias: 'iam.tenant',
  runtimeAccess: 'REFERENCE',
});
const accountScopeReady = computed(() => !currentUser?.value?.system || Boolean(tenantId.value));
const accountContext = computed(() =>
  createModuleContext<Record<string, unknown>>({
    http: scopedHttp.value,
    language: workflowLanguage,
    moduleAlias: 'iam.user',
    runtimeAccess: 'VIEW',
  }),
);
const board = ref('todo'),
  page = ref(1),
  pageSize = ref(30),
  total = ref(0),
  pages = ref(0);
const cards = ref<WorkflowWorkbenchCard[]>([]),
  selected = ref<WorkflowWorkbenchCard>();
const loading = ref(false),
  error = ref(''),
  keyword = ref(''),
  filters = ref<WorkflowWorkbenchFilters>({});
const moduleTitles = ref<Record<string, ModuleTitleView>>({});
const legacyModuleLabels = ref<Record<string, string>>({});
const moduleLabels = computed(() => ({
  ...legacyModuleLabels.value,
  ...Object.fromEntries(
    Object.entries(moduleTitles.value).map(([alias, view]) => [
      alias,
      presentationLabel(view.title, view.titleText, workflowLanguage),
    ]),
  ),
}));
const counts = ref<Record<string, number>>({});
const receivedFrom = ref(''),
  receivedTo = ref('');
const editing = ref(false),
  panel = ref<InstanceType<typeof WorkflowRecordPanel>>();
const boards = computed(() =>
  [
    {
      key: 'todo',
      title: workflowLanguage.t('platform.screen.WorkflowWorkbenchView.my-pending-tasks', '待我办理'),
    },
    {
      key: 'done',
      title: workflowLanguage.t('platform.screen.WorkflowWorkbenchView.my-completed-tasks', '我已办理'),
    },
    {
      key: 'notice',
      title: workflowLanguage.t('platform.screen.WorkflowWorkbenchView.my-notices', '通知我的'),
    },
    {
      key: 'tracking',
      title: workflowLanguage.t('platform.screen.WorkflowWorkbenchView.my-submissions', '我发起的'),
    },
    {
      key: 'delegation',
      title: workflowLanguage.t('platform.screen.WorkflowWorkbenchView.delegated-tasks', '委托办理'),
    },
  ].map((item) => ({
    ...item,
    title: item.title + (counts.value[item.key] == null ? '' : ` (${counts.value[item.key]})`),
  })),
);
const moduleOptions = computed(() =>
  Object.entries(moduleLabels.value).map(([value, label]) => ({ value, label })),
);
const rows = computed(() =>
  cards.value.map((card) => ({
    ...card,
    id: card.taskId ?? card.instanceId,
    nodeTitle: presentationLabel(card.nodeTitle ?? '', card.nodeTitleText, workflowLanguage),
    title:
      card.business?.readable === false
        ? workflowLanguage.t(
            'platform.screen.WorkflowWorkbenchView.restricted-business-record',
            '受限业务记录',
          )
        : (card.business?.title ??
          workflowLanguage.t('platform.screen.WorkflowWorkbenchView.untitled-business-record', '未命名业务')),
    moduleTitle:
      card.business?.moduleTitle != null
        ? presentationLabel(card.business.moduleTitle, card.business.moduleTitleText, workflowLanguage)
        : (moduleLabels.value[card.moduleAlias] ??
          workflowLanguage.t('platform.screen.WorkflowWorkbenchView.business-module', '业务模块')),
    status: workflowTitle(card.taskStatus ?? card.instanceStatus),
    received: formatTime(card.receivedAt),
    completed: formatTime(card.completedAt),
    assignees: card.currentAssigneeTitles.join('、'),
    urgency: workflowTitle(card.overtimeStatus),
  })),
);
const columns = computed(() => [
  {
    key: 'title',
    title: workflowLanguage.t('platform.screen.WorkflowWorkbenchView.business-item', '业务事项'),
    dataIndex: 'title',
    width: 240,
  },
  {
    key: 'moduleTitle',
    title: workflowLanguage.t('platform.screen.WorkflowWorkbenchView.business-module', '业务模块'),
    dataIndex: 'moduleTitle',
    width: 140,
  },
  {
    key: 'nodeTitle',
    title: workflowLanguage.t('platform.screen.WorkflowWorkbenchView.task-node', '办理节点'),
    dataIndex: 'nodeTitle',
    width: 160,
  },
  {
    key: 'submitterUserTitle',
    title: workflowLanguage.t('platform.screen.WorkflowWorkbenchView.submitter', '发起人'),
    dataIndex: 'submitterUserTitle',
    width: 110,
  },
  {
    key: 'status',
    title: workflowLanguage.t('platform.screen.WorkflowWorkbenchView.status', '状态'),
    dataIndex: 'status',
    width: 100,
  },
  {
    key: 'assignees',
    title: workflowLanguage.t('platform.screen.WorkflowWorkbenchView.processors', '办理人'),
    dataIndex: 'assignees',
    width: 130,
  },
  {
    key: 'received',
    title: workflowLanguage.t('platform.screen.WorkflowWorkbenchView.received-at', '接收时间'),
    dataIndex: 'received',
    width: 170,
  },
  ...(board.value === 'done'
    ? [
        {
          key: 'completed',
          title: workflowLanguage.t('platform.screen.WorkflowWorkbenchView.processed-at', '办理时间'),
          dataIndex: 'completed',
          width: 170,
        },
      ]
    : [
        {
          key: 'urgency',
          title: workflowLanguage.t('platform.screen.WorkflowWorkbenchView.timeliness', '时效'),
          dataIndex: 'urgency',
          width: 90,
        },
      ]),
]);
const recordContext = computed(() =>
  selected.value
    ? createModuleContext<Record<string, unknown>>({
        http: scopedHttp.value,
        language: workflowLanguage,
        moduleAlias: selected.value.moduleAlias,
        runtimeAccess: 'VIEW',
      })
    : undefined,
);
const businessRecord = ref<RecordFormRecord>(),
  originalBusinessFields = ref(new Map<string, RecordFormFieldDescriptor>()),
  businessLoading = ref(false),
  businessError = ref('');
const businessFields = computed(() => {
  const display = recordContext.value
    ? resolveRecordFormFields(displayModuleRuntime(recordContext.value.runtime)?.uiDescriptor)
    : undefined;
  return new Map(
    [...originalBusinessFields.value].map(([key, original]) => [key, display?.get(key) ?? original]),
  );
});
function formatTime(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—';
}
function pickerTitle(record: { id?: string; title?: string; username?: string }) {
  return record.title || record.username || record.id || '';
}
let listRevision = 0,
  businessRevision = 0;
let textResourceRevision = 0;
usePageDataChangeHandler(async (changeSet) => {
  const modules = new Set(
    changeSet.changes
      .filter((change) => change.type === 'configuration-translations-changed')
      .map((change) => change.moduleAlias),
  );
  const refreshCards = cards.value.some((card) => modules.has(card.moduleAlias));
  const refreshDirectory = Object.keys(moduleTitles.value).some((alias) => modules.has(alias));
  if (!refreshCards && !refreshDirectory) return;
  const request = ++textResourceRevision;
  const list = listRevision;
  const tenant = tenantId.value;
  const user = currentUser?.value?.userId;
  const business = recordContext.value;
  try {
    const [next, directory] = await Promise.all([
      refreshCards
        ? client.value.workbenchPage(board.value, page.value, filters.value, keyword.value, pageSize.value)
        : undefined,
      client.value.workbenchModules(),
      business && modules.has(business.moduleAlias) ? business.runtime.refreshTexts?.() : undefined,
    ]);
    if (
      request !== textResourceRevision ||
      listRevision !== list ||
      tenantId.value !== tenant ||
      currentUser?.value?.userId !== user
    )
      return;
    const resourceFor = (card: WorkflowWorkbenchCard) =>
      next?.records.find((item) => item.instanceId === card.instanceId && item.taskId === card.taskId)
        ?.nodeTitleText;
    const refreshedModules = { ...directory.modules, ...next?.navigation?.moduleTitles?.modules };
    const retainedModules = { ...moduleTitles.value };
    for (const [alias, current] of Object.entries(retainedModules)) {
      const text = refreshedModules[alias]?.titleText;
      if (sameWorkflowTextSource(current.titleText, text))
        retainedModules[alias] = { ...current, titleText: text };
    }
    moduleTitles.value = retainedModules;
    const updatedCard = (card: WorkflowWorkbenchCard) => {
      const nextCard = next?.records.find(
        (item) => item.instanceId === card.instanceId && item.taskId === card.taskId,
      );
      const moduleText = nextCard?.business?.moduleTitleText ?? refreshedModules[card.moduleAlias]?.titleText;
      return {
        ...card,
        ...(sameWorkflowTextSource(card.nodeTitleText, nextCard?.nodeTitleText)
          ? { nodeTitleText: nextCard?.nodeTitleText }
          : {}),
        business:
          card.business && sameWorkflowTextSource(card.business.moduleTitleText, moduleText)
            ? { ...card.business, moduleTitleText: moduleText }
            : card.business,
      };
    };
    cards.value = cards.value.map(updatedCard);
    if (selected.value) {
      const text = resourceFor(selected.value);
      if (sameWorkflowTextSource(selected.value.nodeTitleText, text)) selected.value.nodeTitleText = text;
      const updated = updatedCard(selected.value);
      if (updated.business) selected.value.business = updated.business;
    }
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-text-resources', phase: 'load' });
  }
});
async function load() {
  const revision = ++listRevision;
  loading.value = true;
  error.value = '';
  try {
    const result = await client.value.workbenchPage(
      board.value,
      page.value,
      filters.value,
      keyword.value,
      pageSize.value,
    );
    if (revision !== listRevision) return;
    cards.value = result.records;
    total.value = result.total;
    pages.value = result.pages;
    legacyModuleLabels.value = { ...legacyModuleLabels.value, ...result.navigation?.modules };
    moduleTitles.value = { ...moduleTitles.value, ...result.navigation?.moduleTitles?.modules };
    counts.value[board.value] = result.total;
  } catch (cause) {
    if (revision === listRevision) {
      cards.value = [];
      total.value = 0;
      pages.value = 0;
      delete counts.value[board.value];
      error.value = workflowLanguage.t(
        'platform.screen.WorkflowWorkbenchView.unable-to-load-tasks-retry',
        '任务加载失败，请重试。',
      );
      presentPlatformError(cause, { source: 'workflow-workbench', phase: 'load' });
    }
  } finally {
    if (revision === listRevision) loading.value = false;
  }
}
async function loadBusiness() {
  const revision = ++businessRevision,
    card = selected.value,
    business = recordContext.value;
  businessRecord.value = undefined;
  businessError.value = '';
  businessLoading.value = false;
  if (!card || !business) return;
  if (card.business?.readable === false) {
    businessError.value = workflowLanguage.t(
      'platform.screen.WorkflowWorkbenchView.you-can-view-the-task-status-but',
      '你可以查看任务状态，但尚无该业务记录的读取权限。请联系业务管理员。',
    );
    return;
  }
  businessLoading.value = true;
  try {
    const runtime = await business.runtime.ready;
    const stored = await business.crud.view(card.recordId);
    if (revision !== businessRevision) return;
    originalBusinessFields.value = resolveRecordFormFields(runtime.uiDescriptor);
    businessRecord.value = stored;
  } catch (cause) {
    if (revision === businessRevision) {
      businessError.value = workflowLanguage.t(
        'platform.screen.WorkflowWorkbenchView.unable-to-load-business-details-retry-or',
        '业务详情加载失败，请重试或检查业务读取权限。',
      );
      presentPlatformError(cause, { source: 'workflow-workbench', phase: 'load' });
    }
  } finally {
    if (revision === businessRevision) businessLoading.value = false;
  }
}
watch([selected, tenantId], () => void loadBusiness());
watch([board, page, pageSize, tenantId], () => void load(), { immediate: true });
watch(
  tenantId,
  () => {
    selected.value = undefined;
    filters.value = {};
    keyword.value = '';
    receivedFrom.value = '';
    receivedTo.value = '';
    legacyModuleLabels.value = {};
    moduleTitles.value = {};
    counts.value = {};
    page.value = 1;
    ++delegationRevision;
    delegationOpen.value = false;
    delegationBusy.value = false;
    policies.value = [];
    newPolicy();
  },
  { flush: 'sync' },
);
async function mayClose() {
  return !editing.value || (await panel.value?.mayLeave()) === true;
}
async function closeDetail() {
  if (await mayClose()) selected.value = undefined;
}
function query() {
  if (editing.value) return;
  filters.value.receivedFrom = receivedFrom.value
    ? new Date(`${receivedFrom.value}T00:00:00`).toISOString()
    : undefined;
  filters.value.receivedTo = receivedTo.value
    ? new Date(
        new Date(`${receivedTo.value}T00:00:00`).setDate(
          new Date(`${receivedTo.value}T00:00:00`).getDate() + 1,
        ),
      ).toISOString()
    : undefined;
  if (page.value !== 1) page.value = 1;
  else void load();
}
function resetQuery() {
  keyword.value = '';
  filters.value = {};
  receivedFrom.value = '';
  receivedTo.value = '';
  query();
}
async function afterChanged() {
  editing.value = false;
  if (selected.value) refreshModulePageList(selected.value.moduleAlias);
  await load();
  await loadBusiness();
}

interface Delegation {
  id: string;
  version: number;
  title: string;
  delegateUserId: string;
  enabled: boolean;
  principalCanProcess: boolean;
  moduleScopeType?: string;
  moduleAliases?: string[];
  orgScopeType?: string;
  organizationIds?: string[];
}
const policies = ref<Delegation[]>([]),
  delegationOpen = ref(false),
  delegationBusy = ref(false),
  policyError = ref('');
const policyDraft = ref<Partial<Delegation>>({
    title: workflowLanguage.t('platform.workflow.delegation.default-title', '审批代办'),
    moduleScopeType: 'all',
    moduleAliases: [],
    principalCanProcess: false,
  }),
  policyInitial = ref('');
const policyDirty = computed(
  () => delegationOpen.value && JSON.stringify(policyDraft.value) !== policyInitial.value,
);
useWorkspaceViewUnsavedState(
  () => workflowLanguage.t('platform.unsaved-source.WorkflowWorkbenchView', '个人委托'),
  () => policyDirty.value,
  () => delegationBusy.value,
);
let delegationRevision = 0;
function delegationIsCurrent(current: number, tenant: string | undefined) {
  return current === delegationRevision && tenant === tenantId.value && delegationOpen.value;
}
async function openDelegations() {
  if (delegationBusy.value || delegationOpen.value || !accountScopeReady.value) return;
  const current = ++delegationRevision,
    tenant = tenantId.value,
    workflow = client.value;
  delegationOpen.value = true;
  delegationBusy.value = true;
  policies.value = [];
  newPolicy();
  try {
    const labels = await workflow.workbenchModules();
    if (!delegationIsCurrent(current, tenant)) return;
    moduleTitles.value = { ...moduleTitles.value, ...labels.modules };
    await loadPolicies();
  } catch (cause) {
    if (delegationIsCurrent(current, tenant))
      presentPlatformError(cause, { source: 'workflow-delegation', phase: 'load' });
  } finally {
    if (delegationIsCurrent(current, tenant)) delegationBusy.value = false;
  }
}
function newPolicy() {
  policyDraft.value = {
    title: workflowLanguage.t('platform.workflow.delegation.default-title', '审批代办'),
    moduleScopeType: 'all',
    moduleAliases: [],
    principalCanProcess: false,
  };
  policyInitial.value = JSON.stringify(policyDraft.value);
  policyError.value = '';
}
async function loadPolicies() {
  const current = delegationRevision,
    tenant = tenantId.value,
    http = scopedHttp.value;
  try {
    const result = await http.request<{ records: Delegation[] }>({
      method: 'POST',
      path: '/workflow/delegation/query',
      body: { page: { pageNum: 1, pageSize: 200 } },
    });
    if (delegationIsCurrent(current, tenant)) policies.value = result.records;
  } catch (cause) {
    if (delegationIsCurrent(current, tenant))
      presentPlatformError(cause, { source: 'workflow-delegation', phase: 'load' });
  }
}
async function startNewPolicy() {
  if (await mayClosePolicy()) newPolicy();
}
async function editPolicy(policy: Delegation) {
  if (!(await mayClosePolicy())) return;
  policyDraft.value = JSON.parse(JSON.stringify(policy));
  policyInitial.value = JSON.stringify(policyDraft.value);
  policyError.value = '';
}
async function mayClosePolicy() {
  if (delegationBusy.value || !delegationOpen.value) return false;
  const current = delegationRevision,
    tenant = tenantId.value;
  const permitted =
    !policyDirty.value ||
    (await confirmAction(
      {
        title: workflowLanguage.t(
          'platform.screen.WorkflowWorkbenchView.discard-delegation-changes',
          '放弃未保存的委托设置？',
        ),
        okText: workflowLanguage.t('platform.screen.WorkflowWorkbenchView.discard-changes', '放弃修改'),
        danger: true,
      },
      workflowLanguage,
    ));
  return permitted && delegationIsCurrent(current, tenant);
}
async function savePolicy() {
  if (delegationBusy.value || !delegationOpen.value) return;
  const current = delegationRevision,
    tenant = tenantId.value,
    http = scopedHttp.value;
  const draft = policyDraft.value;
  if (
    !draft.title?.trim() ||
    !draft.delegateUserId ||
    (draft.moduleScopeType === 'include' && !draft.moduleAliases?.length)
  ) {
    policyError.value = workflowLanguage.t(
      'platform.screen.WorkflowWorkbenchView.delegation-fields-required',
      '请填写规则名称、代办人，并选择适用业务模块。',
    );
    return;
  }
  delegationBusy.value = true;
  try {
    await http.request({
      method: 'POST',
      path: draft.id ? `/workflow/delegation/update/${draft.id}` : '/workflow/delegation/insert',
      body: {
        ...draft,
        enabled: draft.enabled ?? true,
        orgScopeType: draft.orgScopeType ?? 'all',
        moduleAliases: draft.moduleScopeType === 'all' ? [] : draft.moduleAliases,
      },
    });
    if (!delegationIsCurrent(current, tenant)) return;
    newPolicy();
    await loadPolicies();
    if (!delegationIsCurrent(current, tenant)) return;
    showSuccessMessage(
      workflowLanguage.t(
        'platform.screen.WorkflowWorkbenchView.delegation-saved',
        '委托规则已保存，仅影响后续新任务',
      ),
    );
  } catch (cause) {
    if (delegationIsCurrent(current, tenant))
      presentPlatformError(cause, { source: 'workflow-delegation', phase: 'action' });
  } finally {
    if (delegationIsCurrent(current, tenant)) delegationBusy.value = false;
  }
}
async function togglePolicy(policy: Delegation) {
  if (delegationBusy.value || !delegationOpen.value) return;
  const current = delegationRevision,
    tenant = tenantId.value,
    http = scopedHttp.value;
  delegationBusy.value = true;
  try {
    await http.request({
      method: 'POST',
      path: `/workflow/delegation/${policy.enabled ? 'disable' : 'enable'}/${policy.id}`,
      body: { version: policy.version },
    });
    if (delegationIsCurrent(current, tenant)) await loadPolicies();
  } catch (cause) {
    if (delegationIsCurrent(current, tenant))
      presentPlatformError(cause, { source: 'workflow-delegation', phase: 'action' });
  } finally {
    if (delegationIsCurrent(current, tenant)) delegationBusy.value = false;
  }
}
</script>
<template>
  <section class="workflow-workbench">
    <ManagementTabs
      :tabs="boards"
      :active-key="board"
      :label="
        workflowLanguage.t(
          'platform.screen.WorkflowWorkbenchView.approval-workbench-groups',
          '审批工作台分组',
        )
      "
      :disabled="editing"
      @update:active-key="
        board = $event;
        page = 1;
        selected = undefined;
      "
    />
    <UiError
      v-if="error"
      :title="
        workflowLanguage.t('platform.screen.WorkflowWorkbenchView.unable-to-load-tasks', '无法加载任务')
      "
      :message="error"
    />
    <RecordQueryListSurface
      :title="
        boards.find((item) => item.key === board)?.title ??
        workflowLanguage.t('platform.screen.WorkflowWorkbenchView.workflow-tasks', '审批任务')
      "
      :columns="columns"
      :rows="rows"
      row-key="id"
      :clickable-rows="true"
      :selected-row-key="selected?.taskId ?? selected?.instanceId"
      :quick-search-visible="true"
      :quick-search-value="keyword"
      :quick-search-placeholder="
        workflowLanguage.t('platform.screen.WorkflowWorkbenchView.search-business-title', '搜索业务标题')
      "
      :quick-search-disabled="editing || loading"
      :pageable="true"
      :total="total"
      :pages="pages"
      :page-num="page"
      :page-size="pageSize"
      :page-size-options="[10, 20, 30, 50]"
      :pagination-disabled="editing || loading"
      @update:quick-search-value="keyword = $event"
      @quick-search="query"
      @page-change="page = $event"
      @page-size-change="
        pageSize = $event;
        page = 1;
      "
      @row-click="
        !editing && (selected = cards.find((card) => (card.taskId ?? card.instanceId) === $event.id))
      "
    >
      <template #operations
        ><UiButton :loading="loading" :disabled="editing" @click="load">{{
          workflowLanguage.t('platform.screen.WorkflowWorkbenchView.refresh-tasks', '刷新任务')
        }}</UiButton
        ><UiButton
          :disabled="editing || delegationOpen || delegationBusy || !accountScopeReady"
          @click="openDelegations"
          >{{
            workflowLanguage.t(
              'platform.screen.WorkflowWorkbenchView.personal-delegation-settings',
              '个人代办设置',
            )
          }}</UiButton
        ></template
      >
      <template #conditions
        ><div class="workflow-query-fields">
          <label class="workflow-field" v-if="currentUser?.system"
            ><RecordFieldLabel>{{
              workflowLanguage.t('platform.screen.WorkflowWorkbenchView.business-tenant', '业务租户')
            }}</RecordFieldLabel
            ><RecordPicker
              v-model:value="tenantId"
              :disabled="editing || delegationOpen || delegationBusy"
              :context="tenantContext"
              mode="list"
              :title-of="pickerTitle"
          /></label>
          <label class="workflow-field"
            ><RecordFieldLabel>{{
              workflowLanguage.t('platform.screen.WorkflowWorkbenchView.business-module', '业务模块')
            }}</RecordFieldLabel
            ><UiSelect
              v-model:value="filters.moduleAlias"
              :options="moduleOptions"
              :disabled="editing"
              show-search
              :placeholder="
                workflowLanguage.t(
                  'platform.screen.WorkflowWorkbenchView.all-business-modules',
                  '全部业务模块',
                )
              "
          /></label>
          <label class="workflow-field" v-if="accountScopeReady"
            ><RecordFieldLabel>{{
              workflowLanguage.t('platform.screen.WorkflowWorkbenchView.submitter', '发起人')
            }}</RecordFieldLabel
            ><RecordPicker
              v-model:value="filters.submitterUserId"
              :disabled="editing"
              :context="accountContext"
              :load-options="accountOptions"
              mode="list"
              :title-of="pickerTitle"
          /></label>
          <label class="workflow-field"
            ><RecordFieldLabel>{{
              workflowLanguage.t('platform.screen.WorkflowWorkbenchView.timeliness', '时效')
            }}</RecordFieldLabel
            ><UiSelect
              v-model:value="filters.overtimeStatus"
              :disabled="editing"
              :options="[
                { value: 'normal', label: workflowTitle('normal') },
                { value: 'warned', label: workflowTitle('warned') },
                { value: 'overdue', label: workflowTitle('overdue') },
              ]"
              :placeholder="workflowLanguage.t('platform.screen.WorkflowWorkbenchView.all', '全部')"
          /></label>
          <div class="date-filters">
            <label class="workflow-field"
              >{{ workflowLanguage.t('platform.screen.WorkflowWorkbenchView.received-from', '接收开始日期')
              }}<UiInput v-model:value="receivedFrom" type="date" :disabled="editing" /></label
            ><label class="workflow-field"
              >{{ workflowLanguage.t('platform.screen.WorkflowWorkbenchView.received-until', '接收结束日期')
              }}<UiInput v-model:value="receivedTo" type="date" :disabled="editing"
            /></label>
          </div>
          <div class="workflow-query-controls">
            <UiButton :disabled="editing || loading" @click="query">{{
              workflowLanguage.t('platform.screen.WorkflowWorkbenchView.search', '查询')
            }}</UiButton
            ><UiButton :disabled="editing || loading" @click="resetQuery">{{
              workflowLanguage.t('platform.screen.WorkflowWorkbenchView.reset', '重置')
            }}</UiButton>
          </div>
        </div></template
      >
      <template #cell="{ column, record, value }"
        ><span
          :class="{ 'workflow-urgency': column.key === 'urgency' && record.overtimeStatus === 'overdue' }"
          >{{ value ?? '—' }}</span
        ></template
      >
    </RecordQueryListSurface>
    <RecordDetailDrawer
      :open="Boolean(selected)"
      :title="
        (businessRecord?.title as string) ??
        selected?.business?.title ??
        workflowLanguage.t(
          'platform.screen.WorkflowWorkbenchView.business-processing-details',
          '业务办理详情',
        )
      "
      width="wide"
      :before-close="mayClose"
      @close="selected = undefined"
    >
      <p v-if="businessLoading">
        {{
          workflowLanguage.t(
            'platform.screen.WorkflowWorkbenchView.loading-business-details',
            '加载业务详情…',
          )
        }}
      </p>
      <UiError
        v-if="businessError"
        :title="
          workflowLanguage.t(
            'platform.screen.WorkflowWorkbenchView.unable-to-read-business-details',
            '无法读取业务详情',
          )
        "
        :message="businessError"
      /><UiButton v-if="businessError" @click="loadBusiness">{{
        workflowLanguage.t('platform.screen.WorkflowWorkbenchView.retry', '重试')
      }}</UiButton>
      <RecordDetailFields
        v-if="businessRecord"
        :record="businessRecord"
        :fields="businessFields"
        :option-context="recordContext"
      />
      <WorkflowRecordPanel
        v-if="selected && recordContext && !businessError"
        ref="panel"
        :key="selected.instanceId"
        :context="recordContext"
        :record-id="selected.recordId"
        :instance-id="selected.instanceId"
        @editing-change="editing = $event"
        @changed="afterChanged"
      />
      <template #operation
        ><UiButton :disabled="editing" @click="closeDetail">{{
          workflowLanguage.t('platform.screen.WorkflowWorkbenchView.close-details', '关闭详情')
        }}</UiButton></template
      >
    </RecordDetailDrawer>
    <RecordDetailDrawer
      :open="delegationOpen"
      :title="
        policyDraft.id
          ? workflowLanguage.t(
              'platform.screen.WorkflowWorkbenchView.edit-personal-delegation',
              '编辑个人委托',
            )
          : workflowLanguage.t(
              'platform.screen.WorkflowWorkbenchView.personal-delegation-settings',
              '个人代办设置',
            )
      "
      :before-close="mayClosePolicy"
      @close="delegationOpen = false"
    >
      <UiError
        v-if="policyError"
        :title="
          workflowLanguage.t(
            'platform.screen.WorkflowWorkbenchView.complete-the-delegation-rule',
            '请完善委托规则',
          )
        "
        :message="policyError"
      />
      <div class="policy-form">
        <label class="workflow-field"
          ><RecordFieldLabel required>{{
            workflowLanguage.t('platform.screen.WorkflowWorkbenchView.rule-name', '规则名称')
          }}</RecordFieldLabel
          ><UiInput v-model:value="policyDraft.title" :disabled="delegationBusy" /></label
        ><label class="workflow-field"
          ><RecordFieldLabel required>{{
            workflowLanguage.t('platform.screen.WorkflowWorkbenchView.delegate', '代办人')
          }}</RecordFieldLabel
          ><RecordPicker
            v-model:value="policyDraft.delegateUserId"
            :context="accountContext"
            :load-options="accountOptions"
            mode="list"
            :title-of="pickerTitle"
            :disabled="delegationBusy" /></label
        ><label class="workflow-field"
          >{{ workflowLanguage.t('platform.screen.WorkflowWorkbenchView.applicable-scope', '适用范围')
          }}<UiSelect
            v-model:value="policyDraft.moduleScopeType"
            :disabled="delegationBusy"
            :allow-clear="false"
            :options="[
              {
                value: 'all',
                label: workflowLanguage.t(
                  'platform.screen.WorkflowWorkbenchView.all-business-modules',
                  '全部业务模块',
                ),
              },
              {
                value: 'include',
                label: workflowLanguage.t(
                  'platform.screen.WorkflowWorkbenchView.specified-business-modules',
                  '指定业务模块',
                ),
              },
            ]" /></label
        ><label class="workflow-field" v-if="policyDraft.moduleScopeType === 'include'"
          >{{ workflowLanguage.t('platform.screen.WorkflowWorkbenchView.business-module', '业务模块')
          }}<UiSelect
            v-model:value="policyDraft.moduleAliases"
            :disabled="delegationBusy"
            :options="moduleOptions"
            mode="multiple"
            show-search /></label
        ><UiCheckbox v-model:checked="policyDraft.principalCanProcess" :disabled="delegationBusy">{{
          workflowLanguage.t(
            'platform.screen.WorkflowWorkbenchView.retain-my-processing-permission',
            '保留本人办理权限',
          )
        }}</UiCheckbox>
      </div>
      <p>
        {{
          workflowLanguage.t(
            'platform.screen.WorkflowWorkbenchView.rules-affect-new-tasks-assigned-tasks-retain',
            '规则仅影响后续新任务，已分派任务保留原人员与权限快照。',
          )
        }}
      </p>
      <h3>
        {{
          workflowLanguage.t(
            'platform.screen.WorkflowWorkbenchView.existing-delegation-rules',
            '现有委托规则',
          )
        }}
      </h3>
      <div v-for="policy in policies" :key="policy.id" class="policy-row">
        <strong
          >{{ policy.title }} ·
          {{
            policy.enabled
              ? workflowLanguage.t('platform.screen.WorkflowWorkbenchView.enabled', '启用')
              : workflowLanguage.t('platform.screen.WorkflowWorkbenchView.disabled', '停用')
          }}</strong
        ><RecordPicker
          :value="policy.delegateUserId"
          :context="accountContext"
          :load-options="accountOptions"
          mode="list"
          :title-of="pickerTitle"
          disabled
        /><span>{{
          policy.moduleScopeType === 'all'
            ? workflowLanguage.t('platform.screen.WorkflowWorkbenchView.all-business-modules', '全部业务模块')
            : policy.moduleAliases?.map((alias) => moduleLabels[alias] ?? alias).join('、')
        }}</span>
        <div>
          <UiButton :disabled="delegationBusy" @click="editPolicy(policy)">{{
            workflowLanguage.t('platform.screen.WorkflowWorkbenchView.edit-rule', '编辑规则')
          }}</UiButton
          ><UiButton :disabled="delegationBusy" @click="togglePolicy(policy)">{{
            policy.enabled
              ? workflowLanguage.t('platform.screen.WorkflowWorkbenchView.disable-rule', '停用规则')
              : workflowLanguage.t('platform.screen.WorkflowWorkbenchView.enable-rule', '启用规则')
          }}</UiButton>
        </div>
      </div>
      <template #operation
        ><UiButton type="primary" :loading="delegationBusy" @click="savePolicy">{{
          workflowLanguage.t('platform.screen.WorkflowWorkbenchView.save-delegation-rule', '保存委托规则')
        }}</UiButton
        ><UiButton :disabled="delegationBusy" @click="startNewPolicy">{{
          workflowLanguage.t('platform.screen.WorkflowWorkbenchView.new-rule', '新建规则')
        }}</UiButton></template
      >
    </RecordDetailDrawer>
  </section>
</template>
<style scoped>
.workflow-workbench {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
  min-height: 0;
  padding: 12px;
  box-sizing: border-box;
  overflow: hidden;
}
.workflow-workbench > .record-query-list-surface {
  flex: 1;
  min-height: 320px;
}
.workflow-field,
.policy-form {
  display: grid;
  gap: 8px;
  min-width: 0;
}
.date-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.policy-form {
  gap: 16px;
}
.policy-row {
  display: grid;
  gap: 8px;
  padding: 12px 0;
  border-top: 1px solid var(--muyun-border-subtle);
}
.policy-row > div {
  display: flex;
  gap: 8px;
}
.workflow-urgency {
  color: var(--muyun-danger-base);
  font-weight: 600;
}
.workflow-query-fields {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 12px;
}
.workflow-query-fields > .workflow-field {
  flex: 0 1 200px;
  min-width: min(180px, 100%);
}
.workflow-query-controls {
  display: flex;
  gap: 8px;
  align-items: center;
  padding-bottom: 1px;
}
</style>
