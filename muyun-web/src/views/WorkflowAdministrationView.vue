<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  useModuleContext,
  createModuleContext,
  withHttpHeaders,
  createWorkflowClient,
} from '@muyun/web-core';
import type { WorkflowRenderBundle, WorkflowHistoryInstance, WorkflowEvent } from '@muyun/web-contracts';
import {
  RecordPicker,
  RecordQueryListSurface,
  RecordDetailDrawer,
  ManagementTabs,
  RecordFieldLabel,
  AdaptiveHeaderActionBar,
  WorkflowDiagram,
  workflowTitle,
  presentPlatformError,
} from '@muyun/platform-components';
import {
  UiButton,
  UiInput,
  UiTextArea,
  UiSelect,
  UiError,
  confirmAction,
  showSuccessMessage,
} from '@muyun/vue-ui-antdv';
import { useWorkspaceViewUnsavedState } from '@muyun/platform-workbench';
import { useCurrentUserContext } from '../platform-admin-runtime/currentUserContext';
defineOptions({ name: 'WorkflowAdministrationView' });
const context = useModuleContext({ moduleAlias: 'platform.workflow_admin' }),
  user = useCurrentUserContext();
const tenantId = ref<string>(),
  moduleAlias = ref(''),
  recordId = ref(''),
  instanceStatus = ref('running'),
  page = ref(1),
  busy = ref(false),
  historyMode = ref(false),
  error = ref(''),
  detailError = ref(''),
  moduleLabels = ref<Record<string, string>>({});
const http = computed(() =>
  tenantId.value ? withHttpHeaders(context.http, { 'X-MuYun-Tenant-Id': tenantId.value }) : context.http,
);
const tenantContext = createModuleContext<Record<string, unknown>>({
  http: context.http,
  moduleAlias: 'iam.tenant',
  runtimeAccess: 'REFERENCE',
});
interface Instance {
  instanceId: string;
  moduleAlias: string;
  recordId: string;
  versionNo: number;
  instanceStatus: string;
  approvalStatus: string;
  startedByTitle?: string;
  activeNodeTitles: string[];
  currentAssigneeTitles: string[];
  overtimeStatus?: string;
}
interface Task {
  taskId: string;
  nodeTitle: string;
  assigneeTitle?: string;
  taskKind: string;
  canForceApprove: boolean;
}
const instances = ref<Instance[]>([]),
  history = ref<WorkflowHistoryInstance[]>([]),
  bundle = ref<WorkflowRenderBundle>(),
  tasks = ref<Task[]>([]),
  events = ref<WorkflowEvent[]>([]);
const selectedId = ref<string>(),
  reason = ref(''),
  pending = ref<{ code: string; taskId?: string }>();
const post = <T,>(path: string, body: unknown = {}) =>
  http.value.request<T>({ method: 'POST', path: `/workflow/runtime/admin${path}`, body });
let revision = 0;
async function load() {
  if (busy.value || (pending.value && !(await mayCloseOperation()))) return;
  await reload();
}
async function reload() {
  const current = ++revision;
  busy.value = true;
  error.value = '';
  selectedId.value = undefined;
  bundle.value = undefined;
  tasks.value = [];
  events.value = [];
  pending.value = undefined;
  try {
    if (historyMode.value) {
      if (!moduleAlias.value.trim()) {
        history.value = [];
        return;
      }
      const result = await post<{ records: WorkflowHistoryInstance[] }>('/history/query', {
        moduleAlias: moduleAlias.value,
        recordId: recordId.value || undefined,
        page: { pageNum: page.value, pageSize: 30 },
      });
      if (current === revision) history.value = result.records;
    } else {
      const result = await post<{ records: Instance[] }>('/instance/query', {
        moduleAlias: moduleAlias.value || undefined,
        recordId: recordId.value || undefined,
        instanceStatus: instanceStatus.value,
        page: { pageNum: page.value, pageSize: 30 },
      });
      if (current === revision) instances.value = result.records;
    }
  } catch (cause) {
    if (current !== revision) return;
    error.value = '流程加载失败，请重试。';
    instances.value = [];
    history.value = [];
    presentPlatformError(cause, { source: 'workflow-admin', phase: 'load' });
  } finally {
    if (current === revision) busy.value = false;
  }
}
async function select(id: string) {
  if (busy.value || (pending.value && !(await mayCloseOperation()))) return;
  const current = ++revision;
  busy.value = true;
  selectedId.value = id;
  pending.value = undefined;
  bundle.value = undefined;
  tasks.value = [];
  events.value = [];
  detailError.value = '';
  try {
    const path = historyMode.value ? `/history/${id}` : `/instance/${id}`;
    const [graph, timeline] = await Promise.all([
      post<WorkflowRenderBundle>(`${path}/bundle`),
      post<{ records: WorkflowEvent[] }>(`${path}/events/view`),
    ]);
    const active =
      historyMode.value || graph.instance?.instanceStatus !== 'running'
        ? []
        : (
            await http.value.request<{ records: Task[] }>({
              path: `/workflow/runtime/admin/instance/${id}/active-tasks`,
            })
          ).records;
    if (current !== revision) return;
    bundle.value = graph;
    events.value = timeline.records;
    tasks.value = active;
  } catch (cause) {
    if (current !== revision) return;
    detailError.value = '流程详情加载失败，请重试。';
    presentPlatformError(cause, { source: 'workflow-admin', phase: 'load' });
  } finally {
    if (current === revision) busy.value = false;
  }
}
async function query(reset = false) {
  if (busy.value || (pending.value && !(await mayCloseOperation()))) return;
  pending.value = undefined;
  if (reset) {
    moduleAlias.value = '';
    recordId.value = '';
    instanceStatus.value = 'running';
  }
  if (page.value !== 1) page.value = 1;
  else await reload();
}
async function execute() {
  if (busy.value || !pending.value || !selectedId.value || !reason.value.trim()) return;
  busy.value = true;
  try {
    if (
      !(await confirmAction({
        title: `确认${operationTitle(pending.value.code)}？`,
        content: operationImpact(pending.value.code) + ` 原因：${reason.value.trim()}`,
        danger: true,
        okText: '确认执行',
      }))
    )
      return;
    const path = pending.value.taskId ? `/task/${pending.value.taskId}` : `/instance/${selectedId.value}`;
    await post(`${path}/actions/${pending.value.code}`, { reason: reason.value });
    showSuccessMessage('运维操作已完成并记录审计');
    await reload();
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-admin', phase: 'action' });
  } finally {
    busy.value = false;
  }
}
watch(
  tenantId,
  async () => {
    moduleAlias.value = '';
    recordId.value = '';
    page.value = 1;
    moduleLabels.value = await createWorkflowClient(http.value).workbenchModules();
  },
  { immediate: true },
);
watch([tenantId, historyMode, page], () => void load(), { immediate: true });
const rows = computed(() =>
  historyMode.value
    ? history.value.map((item) => ({
        ...item,
        id: item.id,
        moduleTitle: moduleLabels.value[item.moduleAlias] ?? item.moduleAlias,
        status: workflowTitle(item.instanceStatus),
        active: '已归档',
        assignees: '—',
        starter: item.startedByTitle ?? '—',
        started: formatTime(item.startedAt),
      }))
    : instances.value.map((item) => ({
        ...item,
        id: item.instanceId,
        moduleTitle: moduleLabels.value[item.moduleAlias] ?? item.moduleAlias,
        status: workflowTitle(item.instanceStatus),
        approval: workflowTitle(item.approvalStatus),
        active: item.activeNodeTitles.join('、'),
        assignees: item.currentAssigneeTitles.join('、'),
        starter: item.startedByTitle,
        urgency: workflowTitle(item.overtimeStatus),
      })),
);
const columns = [
  { key: 'moduleTitle', title: '业务模块', dataIndex: 'moduleTitle', width: 160 },
  { key: 'versionNo', title: '流程版本', dataIndex: 'versionNo', width: 100 },
  { key: 'status', title: '状态', dataIndex: 'status', width: 100 },
  { key: 'active', title: '当前节点', dataIndex: 'active', width: 180 },
  { key: 'assignees', title: '办理人', dataIndex: 'assignees', width: 180 },
  { key: 'starter', title: '发起人', dataIndex: 'starter', width: 110 },
  { key: 'recordId', title: '业务记录标识', dataIndex: 'recordId', width: 220 },
];
const taskColumns = [
  { key: 'nodeTitle', title: '节点', dataIndex: 'nodeTitle' },
  { key: 'assigneeTitle', title: '办理人', dataIndex: 'assigneeTitle' },
];
const detailActions = computed(() => [
  ...(bundle.value?.instance?.instanceStatus === 'running'
    ? [{ key: 'forceTerminate', title: '终止流程', danger: true }]
    : []),
  { key: 'reset', title: '重置业务审批', danger: true },
]);
function formatTime(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—';
}
function operationTitle(code: string) {
  return code === 'forceApprove' ? '强制办理' : code === 'reset' ? '重置业务审批' : '终止流程';
}
function operationImpact(code: string) {
  return code === 'forceApprove'
    ? '将替代原办理人推进当前任务，可能触发后续业务动作。'
    : code === 'reset'
      ? '将归档当前流程并清除业务审批状态，允许重新发起。'
      : '将终止当前流程并取消未完成任务。';
}
function chooseOperation(code: string, taskId?: string) {
  pending.value = { code, taskId };
  reason.value = '';
}
useWorkspaceViewUnsavedState(
  '工作流运维',
  () => Boolean(pending.value && reason.value.trim()),
  () => busy.value,
);
async function mayCloseOperation() {
  if (busy.value) return false;
  const operation = pending.value;
  const current = revision;
  const accepted =
    !reason.value.trim() ||
    (await confirmAction({ title: '放弃未执行的运维操作？', danger: true, okText: '放弃填写' }));
  return accepted && !busy.value && current === revision && operation === pending.value;
}
async function mayCloseDetail() {
  return !pending.value && !busy.value;
}
function title(record: { id?: string; title?: string }) {
  return record.title ?? record.id ?? '';
}
</script>
<template>
  <section class="workflow-admin">
    <ManagementTabs
      :tabs="[
        { key: 'current', title: '当前实例' },
        { key: 'history', title: '历史归档' },
      ]"
      :active-key="historyMode ? 'history' : 'current'"
      label="工作流运维范围"
      :disabled="busy || Boolean(pending)"
      @update:active-key="
        historyMode = $event === 'history';
        page = 1;
      "
    />
    <UiError v-if="error" title="无法加载流程" :message="error" />
    <p v-if="historyMode && !moduleAlias">选择业务模块后查看归档流程。</p>
    <RecordQueryListSurface
      title="工作流运维"
      subtitle="异常处理需要明确原因，所有操作记录审计"
      :columns="columns"
      :rows="rows"
      clickable-rows
      :selected-row-key="selectedId"
      :pageable="true"
      :total-known="false"
      :total="(page - 1) * 30 + rows.length"
      :pages="page + (rows.length === 30 ? 1 : 0)"
      :page-num="page"
      :page-size="30"
      :pagination-disabled="busy || Boolean(pending)"
      @row-click="select(String($event.id))"
      @page-change="page = $event"
    >
      <template #operations><UiButton :loading="busy" @click="load">刷新</UiButton></template>
      <template #persistentQueries
        ><label v-if="user?.system"
          ><RecordFieldLabel>业务租户</RecordFieldLabel
          ><RecordPicker
            v-model:value="tenantId"
            :context="tenantContext"
            :disabled="busy || Boolean(pending)"
            :title-of="title"
            mode="list" /></label
        ><label
          ><RecordFieldLabel :required="historyMode">业务模块</RecordFieldLabel
          ><UiSelect
            v-model:value="moduleAlias"
            :disabled="busy || Boolean(pending)"
            show-search
            :options="Object.entries(moduleLabels).map(([value, label]) => ({ value, label }))"
            :placeholder="historyMode ? '请选择业务模块' : '全部业务模块'" /></label
        ><label v-if="!historyMode"
          ><RecordFieldLabel>流程状态</RecordFieldLabel
          ><UiSelect
            v-model:value="instanceStatus"
            :disabled="busy || Boolean(pending)"
            :options="[
              { value: 'running', label: '进行中' },
              { value: 'completed', label: '已完成' },
              { value: 'rejected', label: '已驳回' },
              { value: 'revoked', label: '已撤回' },
              { value: 'terminated', label: '已终止' },
            ]" /></label
        ><label
          >业务记录标识<UiInput
            v-model:value="recordId"
            :disabled="busy || Boolean(pending)"
            placeholder="精确定位异常业务" /></label
      ></template>
      <template #queryControls
        ><UiButton :disabled="busy || (historyMode && !moduleAlias)" @click="query()">查询</UiButton
        ><UiButton :disabled="busy" @click="query(true)">重置</UiButton></template
      >
    </RecordQueryListSurface>
    <RecordDetailDrawer
      render-mode="inline"
      :open="Boolean(selectedId)"
      title="流程运维详情"
      width="extraWide"
      :before-close="mayCloseDetail"
      @close="selectedId = undefined"
    >
      <UiError v-if="detailError" title="无法加载详情" :message="detailError" /><UiButton
        v-if="detailError"
        @click="select(selectedId!)"
        >重试</UiButton
      >
      <p v-if="busy">正在加载…</p>
      <template v-if="bundle"
        ><WorkflowDiagram :nodes="bundle.nodes" :routes="bundle.routes" />
        <AdaptiveHeaderActionBar
          v-if="!historyMode"
          :actions="detailActions.map((item) => ({ ...item, disabled: busy || Boolean(pending) }))"
          @action="chooseOperation($event.key)"
        />
        <RecordQueryListSurface
          v-if="!historyMode"
          title="当前待办"
          :columns="taskColumns"
          :rows="tasks.map((task) => ({ ...task, id: task.taskId }))"
          :pageable="false"
          :fill-height="false"
          :show-action-column="true"
          ><template #rowActions="{ record }"
            ><UiButton
              v-if="record.canForceApprove"
              danger
              :disabled="busy || Boolean(pending)"
              @click="chooseOperation('forceApprove', String(record.taskId))"
              >强制办理</UiButton
            ></template
          ></RecordQueryListSurface
        >
        <RecordQueryListSurface
          title="运维审计"
          :columns="[
            { key: 'occurred', title: '时间', dataIndex: 'occurred', width: 170 },
            { key: 'action', title: '操作', dataIndex: 'action', width: 120 },
            { key: 'operatorTitle', title: '操作人', dataIndex: 'operatorTitle', width: 110 },
            { key: 'message', title: '原因与审计', dataIndex: 'message' },
          ]"
          :rows="
            events.map((event) => ({
              ...event,
              action: workflowTitle(event.actionCode ?? event.eventType),
              occurred: formatTime(event.occurredAt),
            }))
          "
          :fill-height="false"
          :pageable="false"
        />
      </template>
      <template #operation
        ><UiButton :disabled="busy || Boolean(pending)" @click="selectedId = undefined"
          >关闭详情</UiButton
        ></template
      >
    </RecordDetailDrawer>
    <RecordDetailDrawer
      render-mode="inline"
      :open="Boolean(pending)"
      :title="operationTitle(pending?.code ?? '')"
      :before-close="mayCloseOperation"
      @close="pending = undefined"
      ><p>{{ operationImpact(pending?.code ?? '') }}</p>
      <p>
        当前流程版本：{{
          bundle?.instance.versionNo ?? '未知'
        }}。操作成功后不可直接撤销，并记录操作人、时间和原因。
      </p>
      <label
        ><RecordFieldLabel required>运维原因</RecordFieldLabel
        ><UiTextArea v-model:value="reason" :disabled="busy" aria-label="运维原因" /></label
      ><template #operation
        ><UiButton type="primary" danger :loading="busy" :disabled="!reason.trim()" @click="execute"
          >确认操作</UiButton
        ><UiButton
          :disabled="busy"
          @click="
            mayCloseOperation().then((ok) => {
              if (ok) pending = undefined;
            })
          "
          >取消</UiButton
        ></template
      ></RecordDetailDrawer
    >
  </section>
</template>
<style scoped>
.workflow-admin {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 12px;
  height: 100%;
  min-height: 0;
  overflow: auto;
}
.workflow-admin :deep(.record-query-list-surface) {
  flex: 1;
  min-height: 240px;
}
label {
  display: grid;
  gap: 8px;
  min-width: 0;
}
</style>
