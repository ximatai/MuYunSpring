<script setup lang="ts">
import {
  useLanguageContext,
  useModuleContext,
  presentationLabel,
  createModuleContext,
  withHttpHeaders,
  createWorkflowClient,
  createWorkflowAdminClient,
} from '@muyun/web-core';
import { computed, ref, watch } from 'vue';

import type {
  ModuleTitleView,
  WorkflowRenderBundle,
  WorkflowHistoryInstance,
  WorkflowEvent,
  WorkflowAdminInstance,
  WorkflowAdminTask,
} from '@muyun/web-contracts';
import {
  RecordPicker,
  RecordQueryListSurface,
  RecordDetailDrawer,
  ManagementTabs,
  RecordFieldLabel,
  WorkflowDiagram,
  workflowTitle as localizedWorkflowTitle,
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
import { usePageDataChangeHandler } from '../platform-admin-runtime/pageRealtime';
import {
  refreshWorkflowTextResources,
  sameWorkflowTextSource,
} from '../platform-components/workflowPresentation';

const workflowLanguage = useLanguageContext();
function workflowTitle(value?: string) {
  return localizedWorkflowTitle(value, workflowLanguage);
}
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
  moduleTitles = ref<Record<string, ModuleTitleView>>({});
const moduleLabels = computed(() =>
  Object.fromEntries(
    Object.entries(moduleTitles.value).map(([alias, view]) => [
      alias,
      presentationLabel(view.title, view.titleText, workflowLanguage),
    ]),
  ),
);
const http = computed(() =>
  tenantId.value ? withHttpHeaders(context.http, { 'X-MuYun-Tenant-Id': tenantId.value }) : context.http,
);
const tenantContext = createModuleContext<Record<string, unknown>>({
  http: context.http,
  language: workflowLanguage,
  moduleAlias: 'iam.tenant',
  runtimeAccess: 'REFERENCE',
});
const instances = ref<WorkflowAdminInstance[]>([]),
  history = ref<WorkflowHistoryInstance[]>([]),
  bundle = ref<WorkflowRenderBundle>(),
  tasks = ref<WorkflowAdminTask[]>([]),
  events = ref<WorkflowEvent[]>([]);
const selectedId = ref<string>(),
  reason = ref(''),
  pending = ref<{ code: string; taskId?: string }>();
const client = computed(() => createWorkflowAdminClient(http.value));
let revision = 0;
let textRevision = 0;
usePageDataChangeHandler(async (changeSet) => {
  const modules = new Set(
    changeSet.changes
      .filter((change) => change.type === 'configuration-translations-changed')
      .map((change) => change.moduleAlias),
  );
  const original = bundle.value;
  const refreshList = !historyMode.value && instances.value.some((item) => modules.has(item.moduleAlias));
  const refreshDetail = original && modules.has(original.instance.moduleAlias);
  const refreshDirectory = Object.keys(moduleTitles.value).some((alias) => modules.has(alias));
  if (!refreshList && !refreshDetail && !refreshDirectory) return;
  const request = ++textRevision,
    current = revision,
    history = historyMode.value,
    id = selectedId.value,
    owner = user?.value?.userId;
  try {
    const [nextInstances, next, nextEvents, directory] = await Promise.all([
      refreshList
        ? client.value.instances({
            moduleAlias: moduleAlias.value || undefined,
            recordId: recordId.value || undefined,
            instanceStatus: instanceStatus.value,
            page: { pageNum: page.value, pageSize: 30 },
          })
        : undefined,
      refreshDetail && id ? client.value.bundle(id, history) : undefined,
      refreshDetail && id ? client.value.events(id, history) : undefined,
      refreshDirectory ? createWorkflowClient(http.value).workbenchModules() : undefined,
    ]);
    if (
      request !== textRevision ||
      current !== revision ||
      id !== selectedId.value ||
      history !== historyMode.value ||
      owner !== user?.value?.userId
    )
      return;
    if (directory) {
      moduleTitles.value = Object.fromEntries(
        Object.entries(moduleTitles.value).map(([alias, view]) => {
          const text = directory.modules[alias]?.titleText;
          return [alias, sameWorkflowTextSource(view.titleText, text) ? { ...view, titleText: text } : view];
        }),
      );
    }
    if (nextInstances)
      instances.value = instances.value.map((item) => {
        const updated = nextInstances.find(
          (row) => row.instanceId === item.instanceId && row.workflowVersionId === item.workflowVersionId,
        );
        return {
          ...item,
          activeNodeLabels: item.activeNodeLabels?.map((label) => {
            const refreshed = updated?.activeNodeLabels?.find(
              (candidate) => candidate.nodeKey === label.nodeKey,
            );
            return sameWorkflowTextSource(label.titleText, refreshed?.titleText)
              ? { ...label, titleText: refreshed?.titleText }
              : label;
          }),
        };
      });
    if (!original || !next || bundle.value?.instance.id !== original.instance.id) return;
    bundle.value = refreshWorkflowTextResources(bundle.value, next);
    tasks.value = tasks.value.map((task) => {
      const node = next.nodes.find(
        (candidate) => candidate.id === task.nodeInstanceId && candidate.nodeKey === task.nodeKey,
      );
      return sameWorkflowTextSource(task.nodeTitleText, node?.nodeTitleText)
        ? { ...task, nodeTitleText: node?.nodeTitleText }
        : task;
    });
    events.value = events.value.map((event) => {
      const nextEvent = nextEvents?.find((item) => item.id === event.id);
      return sameWorkflowTextSource(event.messageText, nextEvent?.messageText)
        ? { ...event, messageText: nextEvent?.messageText }
        : event;
    });
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-text-resources', phase: 'load' });
  }
});

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
      const result = await client.value.history({
        moduleAlias: moduleAlias.value,
        recordId: recordId.value || undefined,
        page: { pageNum: page.value, pageSize: 30 },
      });
      if (current === revision) history.value = result;
    } else {
      const result = await client.value.instances({
        moduleAlias: moduleAlias.value || undefined,
        recordId: recordId.value || undefined,
        instanceStatus: instanceStatus.value,
        page: { pageNum: page.value, pageSize: 30 },
      });
      if (current === revision) instances.value = result;
    }
  } catch (cause) {
    if (current !== revision) return;
    error.value = workflowLanguage.t(
      'platform.screen.WorkflowAdministrationView.load-failed',
      '流程加载失败，请重试。',
    );
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
    const [graph, timeline] = await Promise.all([
      client.value.bundle(id, historyMode.value),
      client.value.events(id, historyMode.value),
    ]);
    const active =
      historyMode.value || graph.instance?.instanceStatus !== 'running'
        ? []
        : await client.value.activeTasks(id);
    if (current !== revision) return;
    bundle.value = graph;
    events.value = timeline;
    tasks.value = active;
  } catch (cause) {
    if (current !== revision) return;
    detailError.value = workflowLanguage.t(
      'platform.screen.WorkflowAdministrationView.detail-load-failed',
      '流程详情加载失败，请重试。',
    );
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
      !(await confirmAction(
        {
          title: workflowLanguage.t(
            'platform.screen.WorkflowAdministrationView.confirm-action-title',
            '确认{operation}？',
            { operation: operationTitle(pending.value.code) },
          ),
          content: workflowLanguage.t(
            'platform.screen.WorkflowAdministrationView.confirm-action-content',
            '{impact} 原因：{reason}',
            { impact: operationImpact(pending.value.code), reason: reason.value.trim() },
          ),
          danger: true,
          okText: workflowLanguage.t(
            'platform.screen.WorkflowAdministrationView.confirm-execute',
            '确认执行',
          ),
        },
        workflowLanguage,
      ))
    )
      return;
    await client.value.execute(selectedId.value, pending.value.code, reason.value, pending.value.taskId);
    showSuccessMessage(
      workflowLanguage.t(
        'platform.screen.WorkflowAdministrationView.operation-completed',
        '运维操作已完成并记录审计',
      ),
    );
    await reload();
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-admin', phase: 'action' });
  } finally {
    busy.value = false;
  }
}
watch(
  tenantId,
  async (_, __, onCleanup) => {
    let active = true;
    onCleanup(() => {
      active = false;
    });
    moduleTitles.value = {};
    moduleAlias.value = '';
    recordId.value = '';
    page.value = 1;
    try {
      const catalog = await createWorkflowClient(http.value).workbenchModules();
      if (active) moduleTitles.value = catalog.modules ?? {};
    } catch (cause) {
      if (active) presentPlatformError(cause, { source: 'workflow-module-titles', phase: 'load' });
    }
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
        active: workflowTitle('archived'),
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
        active: item.activeNodeLabels?.length
          ? item.activeNodeLabels
              .map((label) => presentationLabel(label.title, label.titleText, workflowLanguage))
              .join('、')
          : item.activeNodeTitles.join('、'),
        assignees: item.currentAssigneeTitles.join('、'),
        starter: item.startedByTitle,
        urgency: workflowTitle(item.overtimeStatus),
      })),
);
const columns = computed(() => [
  {
    key: 'moduleTitle',
    title: workflowLanguage.t('platform.screen.WorkflowAdministrationView.business-module', '业务模块'),
    dataIndex: 'moduleTitle',
    width: 160,
  },
  {
    key: 'versionNo',
    title: workflowLanguage.t('platform.screen.WorkflowAdministrationView.workflow-version', '流程版本'),
    dataIndex: 'versionNo',
    width: 100,
  },
  {
    key: 'status',
    title: workflowLanguage.t('platform.screen.WorkflowAdministrationView.status', '状态'),
    dataIndex: 'status',
    width: 100,
  },
  {
    key: 'active',
    title: workflowLanguage.t('platform.screen.WorkflowAdministrationView.current-node', '当前节点'),
    dataIndex: 'active',
    width: 180,
  },
  {
    key: 'assignees',
    title: workflowLanguage.t('platform.screen.WorkflowAdministrationView.processors', '办理人'),
    dataIndex: 'assignees',
    width: 180,
  },
  {
    key: 'starter',
    title: workflowLanguage.t('platform.screen.WorkflowAdministrationView.submitter', '发起人'),
    dataIndex: 'starter',
    width: 110,
  },
  {
    key: 'recordId',
    title: workflowLanguage.t('platform.screen.WorkflowAdministrationView.record-id', '业务记录标识'),
    dataIndex: 'recordId',
    width: 220,
  },
]);
const taskColumns = computed(() => [
  {
    key: 'nodeTitle',
    title: workflowLanguage.t('platform.screen.WorkflowAdministrationView.node', '节点'),
    dataIndex: 'nodeTitle',
  },
  {
    key: 'assigneeTitle',
    title: workflowLanguage.t('platform.screen.WorkflowAdministrationView.processors', '办理人'),
    dataIndex: 'assigneeTitle',
  },
]);
const detailActions = computed(() => [
  ...(bundle.value?.instance?.instanceStatus === 'running'
    ? [
        {
          key: 'forceTerminate',
          title: workflowLanguage.t('platform.screen.WorkflowAdministrationView.terminate', '终止流程'),
          danger: true,
        },
      ]
    : []),
  {
    key: 'reset',
    title: workflowLanguage.t('platform.screen.WorkflowAdministrationView.reset-approval', '重置业务审批'),
    danger: true,
  },
]);
function formatTime(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—';
}
function operationTitle(code: string) {
  return code === 'forceApprove'
    ? workflowLanguage.t('platform.screen.WorkflowAdministrationView.force-process', '强制办理')
    : code === 'reset'
      ? workflowLanguage.t('platform.screen.WorkflowAdministrationView.reset-approval', '重置业务审批')
      : workflowLanguage.t('platform.screen.WorkflowAdministrationView.terminate', '终止流程');
}
function operationImpact(code: string) {
  return code === 'forceApprove'
    ? workflowLanguage.t(
        'platform.screen.WorkflowAdministrationView.force-impact',
        '将替代原办理人推进当前任务，可能触发后续业务动作。',
      )
    : code === 'reset'
      ? workflowLanguage.t(
          'platform.screen.WorkflowAdministrationView.reset-impact',
          '将归档当前流程并清除业务审批状态，允许重新发起。',
        )
      : workflowLanguage.t(
          'platform.screen.WorkflowAdministrationView.terminate-impact',
          '将终止当前流程并取消未完成任务。',
        );
}
function chooseOperation(code: string, taskId?: string) {
  pending.value = { code, taskId };
  reason.value = '';
}
useWorkspaceViewUnsavedState(
  () =>
    workflowLanguage.t('platform.screen.WorkflowAdministrationView.workflow-administration', '工作流运维'),
  () => Boolean(pending.value && reason.value.trim()),
  () => busy.value,
);
async function mayCloseOperation() {
  if (busy.value) return false;
  const operation = pending.value;
  const current = revision;
  const accepted =
    !reason.value.trim() ||
    (await confirmAction(
      {
        title: workflowLanguage.t(
          'platform.screen.WorkflowAdministrationView.discard-operation',
          '放弃未执行的运维操作？',
        ),
        danger: true,
        okText: workflowLanguage.t('platform.screen.WorkflowAdministrationView.discard', '放弃填写'),
      },
      workflowLanguage,
    ));
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
        {
          key: 'current',
          title: workflowLanguage.t(
            'platform.screen.WorkflowAdministrationView.current-instances',
            '当前实例',
          ),
        },
        {
          key: 'history',
          title: workflowLanguage.t('platform.screen.WorkflowAdministrationView.history', '历史归档'),
        },
      ]"
      :active-key="historyMode ? 'history' : 'current'"
      :label="
        workflowLanguage.t(
          'platform.screen.WorkflowAdministrationView.workflow-administration-scope',
          '工作流运维范围',
        )
      "
      :disabled="busy || Boolean(pending)"
      @update:active-key="
        historyMode = $event === 'history';
        page = 1;
      "
    />
    <UiError
      v-if="error"
      :title="
        workflowLanguage.t(
          'platform.screen.WorkflowAdministrationView.unable-to-load-workflows',
          '无法加载流程',
        )
      "
      :message="error"
    />
    <p v-if="historyMode && !moduleAlias">
      {{
        workflowLanguage.t(
          'platform.screen.WorkflowAdministrationView.select-a-business-module-to-view-archived',
          '选择业务模块后查看归档流程。',
        )
      }}
    </p>
    <RecordQueryListSurface
      :title="
        workflowLanguage.t('platform.screen.WorkflowAdministrationView.workflow-administration', '工作流运维')
      "
      :subtitle="
        workflowLanguage.t(
          'platform.screen.WorkflowAdministrationView.a-reason-is-required-for-exception-handling-all-operations-are',
          '异常处理需要明确原因，所有操作记录审计',
        )
      "
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
      :page-size-options="[30]"
      :pagination-disabled="busy || Boolean(pending)"
      @row-click="select(String($event.id))"
      @page-change="page = $event"
    >
      <template #operations
        ><UiButton :loading="busy" @click="load">{{
          workflowLanguage.t('platform.screen.WorkflowAdministrationView.refresh', '刷新')
        }}</UiButton></template
      >
      <template #conditions
        ><div class="workflow-query-fields">
          <label class="workflow-field" v-if="user?.system"
            ><RecordFieldLabel>{{
              workflowLanguage.t('platform.screen.WorkflowAdministrationView.business-tenant', '业务租户')
            }}</RecordFieldLabel
            ><RecordPicker
              v-model:value="tenantId"
              :context="tenantContext"
              :disabled="busy || Boolean(pending)"
              :title-of="title"
              mode="list" /></label
          ><label class="workflow-field"
            ><RecordFieldLabel :required="historyMode">{{
              workflowLanguage.t('platform.screen.WorkflowAdministrationView.business-module', '业务模块')
            }}</RecordFieldLabel
            ><UiSelect
              v-model:value="moduleAlias"
              :disabled="busy || Boolean(pending)"
              show-search
              :options="Object.entries(moduleLabels).map(([value, label]) => ({ value, label }))"
              :placeholder="
                historyMode
                  ? workflowLanguage.t(
                      'platform.screen.WorkflowAdministrationView.select-a-business-module',
                      '请选择业务模块',
                    )
                  : workflowLanguage.t(
                      'platform.screen.WorkflowWorkbenchView.all-business-modules',
                      '全部业务模块',
                    )
              " /></label
          ><label class="workflow-field" v-if="!historyMode"
            ><RecordFieldLabel>{{
              workflowLanguage.t('platform.screen.WorkflowAdministrationView.workflow-status', '流程状态')
            }}</RecordFieldLabel
            ><UiSelect
              v-model:value="instanceStatus"
              :disabled="busy || Boolean(pending)"
              :options="[
                { value: 'running', label: workflowLanguage.t('platform.workflow.running', '进行中') },
                { value: 'completed', label: workflowLanguage.t('platform.workflow.completed', '已完成') },
                { value: 'rejected', label: workflowLanguage.t('platform.workflow.rejected', '已驳回') },
                { value: 'revoked', label: workflowLanguage.t('platform.workflow.revoked', '已撤回') },
                { value: 'terminated', label: workflowLanguage.t('platform.workflow.terminated', '已终止') },
              ]" /></label
          ><label class="workflow-field"
            >{{
              workflowLanguage.t(
                'platform.screen.WorkflowAdministrationView.business-record-identifier',
                '业务记录标识',
              )
            }}<UiInput
              v-model:value="recordId"
              :disabled="busy || Boolean(pending)"
              :placeholder="
                workflowLanguage.t(
                  'platform.screen.WorkflowAdministrationView.locate-a-specific-business-issue',
                  '精确定位异常业务',
                )
              "
          /></label>
          <div class="workflow-query-controls">
            <UiButton :disabled="busy || (historyMode && !moduleAlias)" @click="query()">{{
              workflowLanguage.t('platform.screen.WorkflowAdministrationView.search', '查询')
            }}</UiButton
            ><UiButton :disabled="busy" @click="query(true)">{{
              workflowLanguage.t('platform.screen.WorkflowAdministrationView.reset', '重置')
            }}</UiButton>
          </div>
        </div></template
      >
    </RecordQueryListSurface>
    <RecordDetailDrawer
      :open="Boolean(selectedId)"
      :title="
        workflowLanguage.t(
          'platform.screen.WorkflowAdministrationView.workflow-administration-details',
          '流程运维详情',
        )
      "
      :subtitle="
        bundle
          ? workflowLanguage.t(
              'platform.screen.WorkflowAdministrationView.instance-title',
              '{module} · {status} · 版本 {version}',
              {
                module: moduleLabels[bundle.instance.moduleAlias] ?? bundle.instance.moduleAlias,
                status: workflowTitle(bundle.instance.instanceStatus),
                version: bundle.instance.versionNo,
              },
            )
          : undefined
      "
      width="extraWide"
      :before-close="mayCloseDetail"
      @close="selectedId = undefined"
    >
      <UiError
        v-if="detailError"
        :title="
          workflowLanguage.t(
            'platform.screen.WorkflowAdministrationView.unable-to-load-details',
            '无法加载详情',
          )
        "
        :message="detailError"
      /><UiButton v-if="detailError" @click="select(selectedId!)">{{
        workflowLanguage.t('platform.screen.WorkflowAdministrationView.retry', '重试')
      }}</UiButton>
      <p v-if="busy">
        {{ workflowLanguage.t('platform.screen.WorkflowAdministrationView.loading', '正在加载…') }}
      </p>
      <template v-if="bundle">
        <RecordQueryListSurface
          v-if="!historyMode && tasks.length"
          embedded
          :title="
            workflowLanguage.t('platform.screen.WorkflowAdministrationView.current-pending-tasks', '当前待办')
          "
          :columns="taskColumns"
          :rows="
            tasks.map((task) => ({
              ...task,
              id: task.taskId,
              nodeTitle: presentationLabel(task.nodeTitle, task.nodeTitleText, workflowLanguage),
            }))
          "
          :pageable="false"
          :fill-height="false"
          :show-action-column="true"
          ><template #rowActions="{ record }"
            ><UiButton
              v-if="record.canForceApprove"
              danger
              :disabled="busy || Boolean(pending)"
              @click="chooseOperation('forceApprove', String(record.taskId))"
              >{{
                workflowLanguage.t('platform.screen.WorkflowAdministrationView.force-processing', '强制办理')
              }}</UiButton
            ></template
          ></RecordQueryListSurface
        >
        <p v-if="!historyMode && !tasks.length" class="workflow-empty-tasks">
          {{
            workflowLanguage.t(
              'platform.screen.WorkflowAdministrationView.there-are-no-pending-tasks',
              '当前没有待办任务',
            )
          }}
        </p>
        <details class="workflow-map">
          <summary>
            {{
              workflowLanguage.t(
                'platform.screen.WorkflowAdministrationView.view-workflow-diagram',
                '查看流程图',
              )
            }}
          </summary>
          <WorkflowDiagram
            presentation="detail"
            :nodes="bundle.nodes"
            :routes="bundle.routes"
            :layout-json="bundle.layoutJson"
            :semantic-json="bundle.semanticJson"
          />
        </details>
        <RecordQueryListSurface
          embedded
          :title="
            workflowLanguage.t('platform.screen.WorkflowAdministrationView.administration-audit', '运维审计')
          "
          :columns="[
            {
              key: 'occurred',
              title: workflowLanguage.t('platform.screen.WorkflowAdministrationView.time', '时间'),
              dataIndex: 'occurred',
              width: 170,
            },
            {
              key: 'action',
              title: workflowLanguage.t('platform.screen.WorkflowAdministrationView.operation', '操作'),
              dataIndex: 'action',
              width: 120,
            },
            {
              key: 'operatorTitle',
              title: workflowLanguage.t('platform.screen.WorkflowAdministrationView.operator', '操作人'),
              dataIndex: 'operatorTitle',
              width: 110,
            },
            {
              key: 'message',
              title: workflowLanguage.t(
                'platform.screen.WorkflowAdministrationView.audit-reason',
                '原因与审计',
              ),
              dataIndex: 'message',
            },
          ]"
          :rows="
            events.map((event) => ({
              ...event,
              action: workflowTitle(event.actionCode ?? event.eventType),
              message: presentationLabel(event.message ?? '', event.messageText, workflowLanguage),
              occurred: formatTime(event.occurredAt),
            }))
          "
          :fill-height="false"
          :pageable="false"
        />
      </template>
      <template #operation
        ><template v-if="bundle && !historyMode"
          ><UiButton
            v-for="item in detailActions"
            :key="item.key"
            :danger="item.danger"
            :disabled="busy || Boolean(pending)"
            @click="chooseOperation(item.key)"
            >{{ item.title }}</UiButton
          ></template
        ><UiButton :disabled="busy || Boolean(pending)" @click="selectedId = undefined">{{
          workflowLanguage.t('platform.screen.WorkflowAdministrationView.close-details', '关闭详情')
        }}</UiButton></template
      >
    </RecordDetailDrawer>
    <RecordDetailDrawer
      :open="Boolean(pending)"
      :title="operationTitle(pending?.code ?? '')"
      :before-close="mayCloseOperation"
      @close="pending = undefined"
      ><p v-if="bundle">
        {{
          workflowLanguage.t(
            'platform.screen.WorkflowAdministrationView.operation-record',
            '业务：{module} · 记录 {record}',
            {
              module: moduleLabels[bundle.instance.moduleAlias] ?? bundle.instance.moduleAlias,
              record: bundle.instance.recordId,
            },
          )
        }}
      </p>
      <p>{{ operationImpact(pending?.code ?? '') }}</p>
      <p>
        {{
          workflowLanguage.t(
            'platform.screen.WorkflowAdministrationView.operation-version-warning',
            '当前流程版本：{version}。操作成功后不可直接撤销，并记录操作人、时间和原因。',
            {
              version:
                bundle?.instance.versionNo ??
                workflowLanguage.t('platform.screen.WorkflowAdministrationView.unknown-version', '未知'),
            },
          )
        }}
      </p>
      <label class="workflow-field"
        ><RecordFieldLabel required>{{
          workflowLanguage.t('platform.screen.WorkflowAdministrationView.administration-reason', '运维原因')
        }}</RecordFieldLabel
        ><UiTextArea
          v-model:value="reason"
          :disabled="busy"
          :aria-label="
            workflowLanguage.t('platform.screen.WorkflowAdministrationView.administration-reason', '运维原因')
          " /></label
      ><template #operation
        ><UiButton type="primary" danger :loading="busy" :disabled="!reason.trim()" @click="execute">{{
          workflowLanguage.t('platform.screen.WorkflowAdministrationView.confirm-operation', '确认操作')
        }}</UiButton
        ><UiButton
          :disabled="busy"
          @click="
            mayCloseOperation().then((ok) => {
              if (ok) pending = undefined;
            })
          "
          >{{ workflowLanguage.t('platform.screen.WorkflowAdministrationView.cancel', '取消') }}</UiButton
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
  box-sizing: border-box;
  overflow: hidden;
}
.workflow-admin > .record-query-list-surface {
  flex: 1;
  min-height: 240px;
}
.workflow-field {
  display: grid;
  gap: 8px;
  min-width: 0;
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
