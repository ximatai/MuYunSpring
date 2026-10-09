<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import {
  createModuleContext,
  createWorkflowClient,
  useModuleContext,
  withHttpHeaders,
} from '@muyun/web-core';
import type { WorkflowWorkbenchCard, WorkflowWorkbenchFilters } from '@muyun/web-contracts';
import { refreshModulePageList } from '@muyun/dynamic-page-runtime';
import {
  WorkflowRecordPanel,
  workflowTitle,
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
  moduleAlias: 'iam.tenant',
  runtimeAccess: 'REFERENCE',
});
const accountScopeReady = computed(() => !currentUser?.value?.system || Boolean(tenantId.value));
const accountContext = computed(() =>
  createModuleContext<Record<string, unknown>>({
    http: scopedHttp.value,
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
const moduleLabels = ref<Record<string, string>>({}),
  counts = ref<Record<string, number>>({});
const receivedFrom = ref(''),
  receivedTo = ref('');
const editing = ref(false),
  panel = ref<InstanceType<typeof WorkflowRecordPanel>>();
const boards = computed(() =>
  [
    { key: 'todo', title: '待我办理' },
    { key: 'done', title: '我已办理' },
    { key: 'notice', title: '通知我的' },
    { key: 'tracking', title: '我发起的' },
    { key: 'delegation', title: '委托办理' },
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
    title: card.business?.readable === false ? '受限业务记录' : (card.business?.title ?? '未命名业务'),
    moduleTitle: card.business?.moduleTitle ?? '业务模块',
    status: workflowTitle(card.taskStatus ?? card.instanceStatus),
    received: formatTime(card.receivedAt),
    completed: formatTime(card.completedAt),
    assignees: card.currentAssigneeTitles.join('、'),
    urgency: workflowTitle(card.overtimeStatus),
  })),
);
const columns = computed(() => [
  { key: 'title', title: '业务事项', dataIndex: 'title', width: 240 },
  { key: 'moduleTitle', title: '业务模块', dataIndex: 'moduleTitle', width: 140 },
  { key: 'nodeTitle', title: '办理节点', dataIndex: 'nodeTitle', width: 160 },
  { key: 'submitterUserTitle', title: '发起人', dataIndex: 'submitterUserTitle', width: 110 },
  { key: 'status', title: '状态', dataIndex: 'status', width: 100 },
  { key: 'assignees', title: '办理人', dataIndex: 'assignees', width: 130 },
  { key: 'received', title: '接收时间', dataIndex: 'received', width: 170 },
  ...(board.value === 'done'
    ? [{ key: 'completed', title: '办理时间', dataIndex: 'completed', width: 170 }]
    : [{ key: 'urgency', title: '时效', dataIndex: 'urgency', width: 90 }]),
]);
const recordContext = computed(() =>
  selected.value
    ? createModuleContext<Record<string, unknown>>({
        http: scopedHttp.value,
        moduleAlias: selected.value.moduleAlias,
        runtimeAccess: 'VIEW',
      })
    : undefined,
);
const businessRecord = ref<RecordFormRecord>(),
  businessFields = ref(new Map<string, RecordFormFieldDescriptor>()),
  businessLoading = ref(false),
  businessError = ref('');
function formatTime(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—';
}
function pickerTitle(record: { id?: string; title?: string; username?: string }) {
  return record.title || record.username || record.id || '';
}
let listRevision = 0,
  businessRevision = 0;
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
    moduleLabels.value = { ...moduleLabels.value, ...result.navigation?.modules };
    counts.value[board.value] = result.total;
  } catch (cause) {
    if (revision === listRevision) {
      cards.value = [];
      total.value = 0;
      pages.value = 0;
      delete counts.value[board.value];
      error.value = '任务加载失败，请重试。';
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
    businessError.value = '你可以查看任务状态，但尚无该业务记录的读取权限。请联系业务管理员。';
    return;
  }
  businessLoading.value = true;
  try {
    const runtime = await business.runtime.ready;
    const stored = await business.crud.view(card.recordId);
    if (revision !== businessRevision) return;
    businessFields.value = resolveRecordFormFields(runtime.uiDescriptor);
    businessRecord.value = stored;
  } catch (cause) {
    if (revision === businessRevision) {
      businessError.value = '业务详情加载失败，请重试或检查业务读取权限。';
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
    moduleLabels.value = {};
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
    title: '审批代办',
    moduleScopeType: 'all',
    moduleAliases: [],
    principalCanProcess: false,
  }),
  policyInitial = ref('');
const policyDirty = computed(
  () => delegationOpen.value && JSON.stringify(policyDraft.value) !== policyInitial.value,
);
useWorkspaceViewUnsavedState(
  '个人委托',
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
    moduleLabels.value = { ...moduleLabels.value, ...labels };
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
    title: '审批代办',
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
    (await confirmAction({ title: '放弃未保存的委托设置？', okText: '放弃修改', danger: true }));
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
    policyError.value = '请填写规则名称、代办人，并选择适用业务模块。';
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
    showSuccessMessage('委托规则已保存，仅影响后续新任务');
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
      label="审批工作台分组"
      :disabled="editing"
      @update:active-key="
        board = $event;
        page = 1;
        selected = undefined;
      "
    />
    <UiError v-if="error" title="无法加载任务" :message="error" />
    <RecordQueryListSurface
      :title="boards.find((item) => item.key === board)?.title ?? '审批任务'"
      :columns="columns"
      :rows="rows"
      row-key="id"
      :clickable-rows="true"
      :selected-row-key="selected?.taskId ?? selected?.instanceId"
      :quick-search-visible="true"
      :quick-search-value="keyword"
      quick-search-placeholder="搜索业务标题"
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
        ><UiButton :loading="loading" :disabled="editing" @click="load">刷新任务</UiButton
        ><UiButton
          :disabled="editing || delegationOpen || delegationBusy || !accountScopeReady"
          @click="openDelegations"
          >个人代办设置</UiButton
        ></template
      >
      <template #conditions
        ><div class="workflow-query-fields">
          <label class="workflow-field" v-if="currentUser?.system"
            ><RecordFieldLabel>业务租户</RecordFieldLabel
            ><RecordPicker
              v-model:value="tenantId"
              :disabled="editing || delegationOpen || delegationBusy"
              :context="tenantContext"
              mode="list"
              :title-of="pickerTitle"
          /></label>
          <label class="workflow-field"
            ><RecordFieldLabel>业务模块</RecordFieldLabel
            ><UiSelect
              v-model:value="filters.moduleAlias"
              :options="moduleOptions"
              :disabled="editing"
              show-search
              placeholder="全部业务模块"
          /></label>
          <label class="workflow-field" v-if="accountScopeReady"
            ><RecordFieldLabel>发起人</RecordFieldLabel
            ><RecordPicker
              v-model:value="filters.submitterUserId"
              :disabled="editing"
              :context="accountContext"
              :load-options="accountOptions"
              mode="list"
              :title-of="pickerTitle"
          /></label>
          <label class="workflow-field"
            ><RecordFieldLabel>时效</RecordFieldLabel
            ><UiSelect
              v-model:value="filters.overtimeStatus"
              :disabled="editing"
              :options="[
                { value: 'normal', label: '正常' },
                { value: 'warned', label: '预警' },
                { value: 'overdue', label: '超期' },
              ]"
              placeholder="全部"
          /></label>
          <div class="date-filters">
            <label class="workflow-field"
              >接收开始日期<UiInput v-model:value="receivedFrom" type="date" :disabled="editing" /></label
            ><label class="workflow-field"
              >接收结束日期<UiInput v-model:value="receivedTo" type="date" :disabled="editing"
            /></label>
          </div>
          <div class="workflow-query-controls">
            <UiButton :disabled="editing || loading" @click="query">查询</UiButton
            ><UiButton :disabled="editing || loading" @click="resetQuery">重置</UiButton>
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
      :title="(businessRecord?.title as string) ?? selected?.business?.title ?? '业务办理详情'"
      width="wide"
      :before-close="mayClose"
      @close="selected = undefined"
    >
      <p v-if="businessLoading">加载业务详情…</p>
      <UiError v-if="businessError" title="无法读取业务详情" :message="businessError" /><UiButton
        v-if="businessError"
        @click="loadBusiness"
        >重试</UiButton
      >
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
      <template #operation><UiButton :disabled="editing" @click="closeDetail">关闭详情</UiButton></template>
    </RecordDetailDrawer>
    <RecordDetailDrawer
      :open="delegationOpen"
      :title="policyDraft.id ? '编辑个人委托' : '个人代办设置'"
      :before-close="mayClosePolicy"
      @close="delegationOpen = false"
    >
      <UiError v-if="policyError" title="请完善委托规则" :message="policyError" />
      <div class="policy-form">
        <label class="workflow-field"
          ><RecordFieldLabel required>规则名称</RecordFieldLabel
          ><UiInput v-model:value="policyDraft.title" :disabled="delegationBusy" /></label
        ><label class="workflow-field"
          ><RecordFieldLabel required>代办人</RecordFieldLabel
          ><RecordPicker
            v-model:value="policyDraft.delegateUserId"
            :context="accountContext"
            :load-options="accountOptions"
            mode="list"
            :title-of="pickerTitle"
            :disabled="delegationBusy" /></label
        ><label class="workflow-field"
          >适用范围<UiSelect
            v-model:value="policyDraft.moduleScopeType"
            :disabled="delegationBusy"
            :allow-clear="false"
            :options="[
              { value: 'all', label: '全部业务模块' },
              { value: 'include', label: '指定业务模块' },
            ]" /></label
        ><label class="workflow-field" v-if="policyDraft.moduleScopeType === 'include'"
          >业务模块<UiSelect
            v-model:value="policyDraft.moduleAliases"
            :disabled="delegationBusy"
            :options="moduleOptions"
            mode="multiple"
            show-search /></label
        ><UiCheckbox v-model:checked="policyDraft.principalCanProcess" :disabled="delegationBusy"
          >保留本人办理权限</UiCheckbox
        >
      </div>
      <p>规则仅影响后续新任务，已分派任务保留原人员与权限快照。</p>
      <h3>现有委托规则</h3>
      <div v-for="policy in policies" :key="policy.id" class="policy-row">
        <strong>{{ policy.title }} · {{ policy.enabled ? '启用' : '停用' }}</strong
        ><RecordPicker
          :value="policy.delegateUserId"
          :context="accountContext"
          :load-options="accountOptions"
          mode="list"
          :title-of="pickerTitle"
          disabled
        /><span>{{
          policy.moduleScopeType === 'all'
            ? '全部业务模块'
            : policy.moduleAliases?.map((alias) => moduleLabels[alias] ?? alias).join('、')
        }}</span>
        <div>
          <UiButton :disabled="delegationBusy" @click="editPolicy(policy)">编辑规则</UiButton
          ><UiButton :disabled="delegationBusy" @click="togglePolicy(policy)">{{
            policy.enabled ? '停用规则' : '启用规则'
          }}</UiButton>
        </div>
      </div>
      <template #operation
        ><UiButton type="primary" :loading="delegationBusy" @click="savePolicy">保存委托规则</UiButton
        ><UiButton :disabled="delegationBusy" @click="startNewPolicy">新建规则</UiButton></template
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
.workflow-empty-tasks {
  margin: 0;
  color: var(--muyun-text-muted);
}
.workflow-map summary {
  cursor: pointer;
  color: var(--muyun-primary);
  padding: 8px 0;
}
</style>
