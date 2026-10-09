<script setup lang="ts">
import { computed, ref, watch, nextTick } from 'vue';
import { createWorkflowClient, createModuleContext, type ModuleContext } from '@muyun/web-core';
import type {
  WorkflowAction,
  WorkflowAddSignExplanation,
  WorkflowBranch,
  WorkflowEvent,
  WorkflowHistoryInstance,
  WorkflowRenderBundle,
  WorkflowSubmitPreview,
  WorkflowStatus,
  WorkflowTask,
  WorkflowTaskPreparation,
} from '@muyun/web-contracts';
import {
  UiButton,
  UiInput,
  UiSelect,
  UiTextArea,
  UiError,
  confirmAction,
  showSuccessMessage,
} from '@muyun/vue-ui-antdv';
import { presentPlatformError } from './platformErrorFeedback';
import { workflowAccountOptions } from './workflowAccountOptions';
import RecordPicker from './RecordPicker.vue';
import RecordQueryListPanel from './RecordQueryListPanel.vue';
import RecordFormFields, { type RecordFormValidity } from './RecordFormFields.vue';
import RecordDetailDrawer from './RecordDetailDrawer.vue';
import RecordQueryListSurface from './RecordQueryListSurface.vue';
import RecordDetailFields from './RecordDetailFields.vue';
import AdaptiveHeaderActionBar from './AdaptiveHeaderActionBar.vue';
import { useWorkspaceViewUnsavedState } from '@muyun/web-core';
import { resolveRecordFormFields } from './recordFormFieldModel';
import type { RecordFormRecord, RecordFormFieldDescriptor } from './recordFormFieldModel';
import WorkflowDiagram from './WorkflowDiagram.vue';
import WorkflowTimeline from './WorkflowTimeline.vue';
import { workflowTitle, workflowRouteSelectionTitle } from './workflowPresentation';
defineOptions({ name: 'WorkflowRecordPanel' });
const props = defineProps<{
  context: ModuleContext<Record<string, unknown>>;
  recordId: string;
  instanceId?: string;
}>();
const emit = defineEmits<{
  changed: [];
  edit: [];
  'editing-change': [editing: boolean];
  'interaction-change': [state: { editing: boolean; busy: boolean; dirty: boolean }];
}>();
const client = computed(() => createWorkflowClient(props.context.http));
const status = ref<WorkflowStatus>();
const bundle = ref<WorkflowRenderBundle>();
const tasks = ref<WorkflowTask[]>([]),
  events = ref<WorkflowEvent[]>([]),
  actions = ref<WorkflowAction[]>([]),
  branches = ref<WorkflowBranch[]>([]);
const loading = ref(false),
  busy = ref(false),
  error = ref('');
const activeAction = ref<WorkflowAction>();
const reason = ref(''),
  targetAssigneeId = ref(''),
  rejectMode = ref('restart');
const manualRoutes = ref<Record<string, string[]>>({}),
  manualReasons = ref<Record<string, string>>({});
const showHistory = ref(false),
  showTechnicalHistory = ref(false);
const taskValidity = ref<RecordFormValidity>({ valid: true, errors: {} });
const validationRequestKey = ref(0),
  taskInitial = ref('');
const manualChoicesReady = ref(true);
let branchRequest = 0;
const preparing = ref(false),
  previewReady = ref(false);
const previewBundle = ref<WorkflowSubmitPreview>(),
  previewTasks = ref<WorkflowTask[]>([]);
const dirty = computed(() =>
  Boolean(
    activeAction.value &&
    (reason.value ||
      targetAssigneeId.value ||
      addSignUserId.value ||
      Object.values(manualRoutes.value).some((keys) => keys.length) ||
      (taskDraft.value && JSON.stringify(taskDraft.value) !== taskInitial.value)),
  ),
);
useWorkspaceViewUnsavedState(
  '审批办理',
  () => dirty.value,
  () => busy.value || preparing.value,
);
watch(activeAction, (value) => emit('editing-change', Boolean(value)), { flush: 'sync' });
watch(
  [activeAction, busy, preparing, dirty],
  () =>
    emit('interaction-change', {
      editing: Boolean(activeAction.value),
      busy: busy.value || preparing.value,
      dirty: dirty.value,
    }),
  { flush: 'sync', immediate: true },
);
const operationItems = computed(() =>
  actions.value.map((action, index) => ({
    key: String(index),
    title: `${action.nodeTitle ? action.nodeTitle + ' · ' : ''}${action.actionCode === 'complete' ? '办理任务' : action.title}`,
    level: ['approve', 'complete', 'resubmit', 'read'].includes(action.actionCode)
      ? ('primary' as const)
      : ('secondary' as const),
    danger: ['reject', 'rollback'].includes(action.actionCode),
    disabled: busy.value || loading.value || Boolean(activeAction.value),
  })),
);
async function mayLeave() {
  if (busy.value || preparing.value) return false;
  return (
    !dirty.value ||
    (await confirmAction({
      title: '放弃未保存的办理内容？',
      content: '当前填写尚未提交，放弃后可重新进入任务办理。',
      okText: '放弃填写',
      danger: true,
    }))
  );
}
async function cancelAction() {
  if (await mayLeave()) activeAction.value = undefined;
}
defineExpose({ mayLeave });
const archives = ref<WorkflowHistoryInstance[]>([]),
  selectedHistory = ref<string>();
const accountOptions = computed(() => workflowAccountOptions(props.context.http));
const accountContext = computed(() =>
  createModuleContext<Record<string, unknown>>({ http: props.context.http, moduleAlias: 'iam.user' }),
);
const addSignUserId = ref<string>(),
  addSignTitle = ref('追加审批'),
  addSignRouteKey = ref('');
const addSignExplanations = ref<WorkflowAddSignExplanation[]>([]),
  addSignReady = ref(false);
const addSignRoutes = computed(() => {
  if (!addSignReady.value) return [];
  const source = activeAction.value?.nodeKey;
  const segment = addSignExplanations.value.filter((item) => item.addSignSourceNodeKey === source);
  const nodes = new Set(segment.filter((item) => item.dimension === 'NODE').map((item) => item.nodeKey));
  if (nodes.size) {
    // A replacement reconnects to the original segment exit, never to a node being replaced.
    return segment
      .filter(
        (item) =>
          item.dimension === 'ROUTE' &&
          nodes.has(item.routeSourceNodeKey) &&
          !nodes.has(item.routeTargetNodeKey) &&
          item.routeTargetNodeKey,
      )
      .map((item) => ({
        routeKey: item.routeKey!,
        targetNodeKey: item.routeTargetNodeKey!,
        title: bundle.value?.routes.find((route) => route.routeKey === item.routeKey)?.title,
      }));
  }
  return (
    bundle.value?.routes.filter(
      (route) => route.sourceNodeKey === source && route.routeStatus === 'candidate',
    ) ?? []
  );
});
const selectedAddSignRoute = computed(
  () =>
    addSignRoutes.value.find((route) => route.routeKey === addSignRouteKey.value) ??
    (addSignRoutes.value.length === 1 ? addSignRoutes.value[0] : undefined),
);
const taskProcess = ref<WorkflowTaskPreparation>();
const taskDraft = ref<RecordFormRecord>(),
  taskFields = ref(new Map<string, RecordFormFieldDescriptor>()),
  taskGuideKey = ref<string>();
const guideListContext = ref<ModuleContext<Record<string, unknown>>>(),
  guideListTitle = ref('');
const guideReadFields = ref(new Map<string, RecordFormFieldDescriptor>()),
  guideReadRecord = ref<RecordFormRecord>();
function guideConfig(guide: { guideConfigText?: string }): Record<string, unknown> {
  try {
    return JSON.parse(guide.guideConfigText ?? '{}');
  } catch {
    return {};
  }
}
async function openReadGuide(guide: {
  title?: string;
  guideKind: string;
  targetModuleAlias?: string;
  guideConfigText?: string;
}) {
  guideListContext.value = undefined;
  guideReadRecord.value = undefined;
  if (guide.guideKind === 'open_list') {
    guideListTitle.value = guide.title ?? '业务记录';
    guideListContext.value = createModuleContext<Record<string, unknown>>({
      http: props.context.http,
      moduleAlias: guide.targetModuleAlias ?? props.context.moduleAlias,
    });
  } else {
    try {
      const runtime = await props.context.runtime.ready,
        key = String(guideConfig(guide).field ?? '');
      guideReadFields.value = new Map(
        [...resolveRecordFormFields(runtime.uiDescriptor)].filter(([field]) => field === key),
      );
      if (!guideReadFields.value.size) throw new Error('指引字段不存在');
      guideReadRecord.value = await props.context.crud.view(props.recordId);
    } catch (cause) {
      presentPlatformError(cause, { source: 'workflow-task', phase: 'load' });
    }
  }
}
async function executeBusinessAction(guideKey: string) {
  const taskId = activeAction.value?.taskId;
  if (!taskId || busy.value || preparing.value) return;
  if (!manualSelectionValid()) return;
  busy.value = true;
  let completed = false;
  try {
    await client.value.executeGuide(taskId, guideKey, {
      reason: reason.value,
      manualRouteSelections: routeSelections(),
    });
    activeAction.value = undefined;
    await load();
    completed = true;
    showSuccessMessage('业务动作和任务已完成');
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-task', phase: 'action' });
  } finally {
    busy.value = false;
    if (completed) emit('changed');
  }
}

let revision = 0,
  formRequest = 0;
const actionBranches = computed(() =>
  branches.value.filter(
    (branch) =>
      (activeAction.value?.actionCode === 'submitApproval' ||
        activeAction.value?.actionCode === 'complete' ||
        (activeAction.value?.actionCode === 'approve' &&
          branch.selectorNodeKey === activeAction.value?.nodeKey)) &&
      branch.candidates.some((candidate) => candidate.routeStatus === 'candidate'),
  ),
);
const decidedBranchRoutes = computed(
  () =>
    bundle.value?.routes.filter(
      (route) =>
        route.routeStatus !== 'candidate' &&
        bundle.value?.nodes.some(
          (node) => node.nodeType === 'branch' && node.nodeKey === route.sourceNodeKey,
        ),
    ) ?? [],
);
watch(
  () => [props.context, props.recordId, props.instanceId],
  () => {
    selectedHistory.value = undefined;
    void load();
  },
  { immediate: true },
);
function userTitle(record: { id?: string; title?: string }): string {
  const values = record as Record<string, unknown>;
  return String(record.title ?? values.account ?? values.username ?? record.id ?? '');
}
async function load() {
  const current = ++revision;
  ++formRequest;
  preparing.value = false;
  loading.value = true;
  error.value = '';
  try {
    const [next, history] = await Promise.all([
      client.value.status(props.context.moduleAlias, props.recordId),
      client.value.history(props.context.moduleAlias, props.recordId),
    ]);
    if (current !== revision) return;
    status.value = next;
    archives.value = history;
    activeAction.value = undefined;
    if (selectedHistory.value) {
      const [graph, taskList, eventList] = await Promise.all([
        client.value.historyBundle(selectedHistory.value),
        client.value.historyTasks(selectedHistory.value),
        client.value.historyEvents(selectedHistory.value),
      ]);
      if (current !== revision) return;
      bundle.value = graph;
      tasks.value = taskList;
      events.value = eventList;
      actions.value = [];
      branches.value = [];
      return;
    }
    const id = props.instanceId ?? next.instanceId;
    if (id) {
      const [graph, taskList, eventList, available, choices] = await Promise.all([
        client.value.bundle(id),
        client.value.taskViews(id),
        client.value.eventViews(id),
        client.value.actions(id),
        client.value.branches(id),
      ]);
      if (current !== revision) return;
      bundle.value = graph;
      tasks.value = taskList;
      events.value = eventList;
      actions.value = available;
      branches.value = choices;
    } else {
      bundle.value = undefined;
      tasks.value = [];
      events.value = [];
      actions.value = [];
      branches.value = [];
    }
  } catch (cause) {
    if (current === revision) {
      error.value = cause instanceof Error ? cause.message : '流程加载失败';
    }
  } finally {
    if (current === revision) loading.value = false;
  }
}
async function choose(action: WorkflowAction) {
  if (activeAction.value && !(await mayLeave())) return;
  error.value = '';
  validationRequestKey.value = 0;
  previewReady.value = false;
  activeAction.value = action;
  reason.value = '';
  targetAssigneeId.value = '';
  rejectMode.value = action.defaultRejectResubmitMode ?? 'restart';
  manualRoutes.value = {};
  manualReasons.value = {};
  guideListContext.value = undefined;
  guideReadRecord.value = undefined;
  taskProcess.value = undefined;
  taskDraft.value = undefined;
  taskGuideKey.value = undefined;
  addSignUserId.value = undefined;
  addSignRouteKey.value = '';
  addSignExplanations.value = [];
  addSignReady.value = false;
  manualChoicesReady.value = true;
  if (['approve', 'complete'].includes(action.actionCode) && action.taskId) await refreshManualChoices();
  if (action.actionCode === 'complete' && action.taskId) await inspectTask(action.taskId);
  if (action.actionCode === 'addSign') await prepareAddSign(action);
}
async function prepareAddSign(action: WorkflowAction) {
  const id = props.instanceId ?? status.value?.instanceId,
    current = revision;
  if (!id) return;
  preparing.value = true;
  try {
    const explanations = await client.value.addSignExplanations(id);
    if (current !== revision || action !== activeAction.value) return;
    addSignExplanations.value = explanations;
    addSignReady.value = true;
  } catch (cause) {
    if (current === revision && action === activeAction.value)
      presentPlatformError(cause, { source: 'workflow-add-sign', phase: 'load' });
  } finally {
    if (current === revision && action === activeAction.value) preparing.value = false;
  }
}
async function preview() {
  busy.value = true;
  try {
    branches.value = await client.value.submitBranches(props.context.moduleAlias, props.recordId);
    await choose({ actionCode: 'submitApproval', title: '提交审批', reasonRequired: false });
    if (!branches.value.length) await refreshPreview();
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow', phase: 'load' });
  } finally {
    busy.value = false;
  }
}
async function inspectTask(taskId: string) {
  if (busy.value || preparing.value || activeAction.value?.taskId !== taskId) return;
  const action = activeAction.value,
    context = props.context,
    recordId = props.recordId,
    current = revision;
  const isCurrent = () =>
    current === revision &&
    action === activeAction.value &&
    context === props.context &&
    recordId === props.recordId;
  preparing.value = true;
  try {
    const process = await client.value.prepareTask(taskId);
    if (isCurrent()) taskProcess.value = process;
  } catch (cause) {
    if (isCurrent()) presentPlatformError(cause, { source: 'workflow', phase: 'validation' });
  } finally {
    if (isCurrent()) preparing.value = false;
  }
}
function routeSelections() {
  return Object.entries(manualRoutes.value).flatMap(([branchNodeKey, keys]) =>
    keys.map((routeKey) => ({ branchNodeKey, routeKey, selectedReason: manualReasons.value[branchNodeKey] })),
  );
}
async function refreshManualChoices() {
  const action = activeAction.value;
  const instanceId = props.instanceId ?? status.value?.instanceId;
  if (
    !action ||
    (action.actionCode !== 'submitApproval' &&
      !(['approve', 'complete'].includes(action.actionCode) && action.taskId && instanceId))
  )
    return;
  const requestId = ++branchRequest;
  preparing.value = true;
  manualChoicesReady.value = false;
  try {
    const payload = { manualRouteSelections: routeSelections() };
    const choices =
      action.actionCode === 'submitApproval'
        ? await client.value.submitBranches(props.context.moduleAlias, props.recordId, payload)
        : await client.value.branchChoices(instanceId!, { ...payload, taskId: action.taskId });
    if (requestId !== branchRequest || action !== activeAction.value) return;
    branches.value = choices;
    const validKeys = new Set(choices.map((branch) => branch.branchNodeKey));
    manualRoutes.value = Object.fromEntries(
      Object.entries(manualRoutes.value).filter(([key]) => validKeys.has(key)),
    );
    manualReasons.value = Object.fromEntries(
      Object.entries(manualReasons.value).filter(([key]) => validKeys.has(key)),
    );
    manualChoicesReady.value = true;
  } catch (cause) {
    if (requestId === branchRequest && action === activeAction.value) {
      error.value = '路径候选加载失败，请重新进入操作后重试';
      presentPlatformError(cause, { source: 'workflow', phase: 'load' });
    }
  } finally {
    if (requestId === branchRequest) preparing.value = false;
  }
}
async function selectManualRoute(branchNodeKey: string, routeKey: unknown) {
  manualRoutes.value[branchNodeKey] = routeKey ? [String(routeKey)] : [];
  previewReady.value = false;
  error.value = '';
  await refreshManualChoices();
}
function manualSelectionValid() {
  if (preparing.value || !manualChoicesReady.value) {
    error.value = '请等待路径候选加载完成，失败时重新进入操作后重试';
    return false;
  }
  for (const branch of actionBranches.value) {
    if (manualRoutes.value[branch.branchNodeKey]?.length !== 1) {
      error.value = `请为「${branch.branchTitle ?? '手工分支'}」选择一条路径`;
      return false;
    }
    if (branch.requireManualSelectionReason && !manualReasons.value[branch.branchNodeKey]?.trim()) {
      error.value = `请填写「${branch.branchTitle ?? '手工分支'}」的路径选择原因`;
      return false;
    }
  }
  return true;
}
function candidateTitle(candidate: WorkflowBranch['candidates'][number]) {
  const name =
    candidate.title ??
    candidate.targetNodeTitle ??
    bundle.value?.nodes.find((item) => item.nodeKey === candidate.targetNodeKey)?.nodeTitle ??
    candidate.targetNodeKey;
  const labels = [
    candidate.conditionMatched ? '条件命中' : '',
    candidate.defaultRoute ? '默认出口' : '',
    candidate.recommended ? '系统建议' : '',
  ].filter(Boolean);
  return name + (labels.length ? `（${labels.join(' · ')}）` : '');
}
async function refreshPreview() {
  if (!manualSelectionValid()) return;
  preparing.value = true;
  previewReady.value = false;
  try {
    const result = await client.value.preview(props.context.moduleAlias, props.recordId, {
      manualRouteSelections: routeSelections(),
    });
    previewBundle.value = result;
    previewTasks.value = result.taskViews ?? result.tasks;
    previewReady.value = true;
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow', phase: 'validation' });
  } finally {
    preparing.value = false;
  }
}
watch(
  [manualRoutes, manualReasons],
  () => {
    if (activeAction.value?.actionCode === 'submitApproval') previewReady.value = false;
  },
  { deep: true },
);
async function openBusinessForm(guideKey: string) {
  if (busy.value || preparing.value || !activeAction.value) return;
  if (taskDraft.value && taskGuideKey.value === guideKey) return;
  const requestId = ++formRequest,
    action = activeAction.value,
    context = props.context,
    recordId = props.recordId,
    current = revision;
  const isCurrent = () =>
    requestId === formRequest &&
    current === revision &&
    action === activeAction.value &&
    context === props.context &&
    recordId === props.recordId;
  if (taskDraft.value && !(await mayLeave())) return;
  if (!isCurrent() || busy.value || preparing.value) return;
  preparing.value = true;
  try {
    const guide = taskProcess.value?.evaluation.guides.find((item) => item.guideKey === guideKey);
    const runtime = await context.runtime.ready;
    if (!isCurrent()) return;
    const allowed = new Set<string>(JSON.parse(guide?.guideConfigText ?? '{}').editableFields ?? []);
    const fields = new Map(
      [...resolveRecordFormFields(runtime.uiDescriptor)].filter(([key]) => allowed.has(key)),
    );
    if (fields.size === 0) throw new Error('当前任务未配置可编辑字段');
    const draft = await context.crud.view(recordId);
    if (!isCurrent()) return;
    taskGuideKey.value = guideKey;
    taskFields.value = fields;
    taskDraft.value = draft;
    taskInitial.value = JSON.stringify(draft);
    taskValidity.value = { valid: true, errors: {} };
    validationRequestKey.value = 0;
  } catch (cause) {
    if (isCurrent()) presentPlatformError(cause, { source: 'workflow-task', phase: 'load' });
  } finally {
    if (isCurrent()) preparing.value = false;
  }
}
async function saveAndComplete() {
  const taskId = activeAction.value?.taskId,
    draft = taskDraft.value;
  if (!taskId || !taskGuideKey.value || !draft || busy.value || preparing.value) return;
  if (!manualSelectionValid()) return;
  validationRequestKey.value++;
  await nextTick();
  if (!taskValidity.value.valid) {
    error.value = '请修正标记的业务字段后再提交';
    return;
  }
  busy.value = true;
  let completed = false;
  try {
    await client.value.executeGuide(taskId, taskGuideKey.value, {
      version: draft.version,
      values: Object.fromEntries(
        [...taskFields.value.keys()]
          .filter((key) => Object.hasOwn(draft, key))
          .map((key) => [key, draft[key]]),
      ),
      reason: reason.value,
      manualRouteSelections: routeSelections(),
    });
    activeAction.value = undefined;
    taskDraft.value = undefined;
    await load();
    completed = true;
    showSuccessMessage('业务数据已保存，任务已完成');
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-task', phase: 'action' });
  } finally {
    busy.value = false;
    if (completed) emit('changed');
  }
}
async function execute() {
  const action = activeAction.value;
  if (!action || busy.value || preparing.value) return;
  if (!manualSelectionValid()) return;
  if (action.actionCode === 'submitApproval' && !previewReady.value) {
    error.value = '请先预览所选审批路径';
    return;
  }
  if (action.reasonRequired && !reason.value.trim()) {
    error.value = '请填写操作原因';
    return;
  }
  if (action.targetAssigneeRequired && !targetAssigneeId.value.trim()) {
    error.value = '请选择接收人';
    return;
  }
  const selections = Object.entries(manualRoutes.value)
    .filter(([, keys]) => keys.length)
    .flatMap(([branchNodeKey, selectedRouteKeys]) =>
      selectedRouteKeys.map((routeKey) => ({
        branchNodeKey,
        routeKey,
        selectedReason: manualReasons.value[branchNodeKey],
      })),
    );
  const nextRoute = selectedAddSignRoute.value;
  const addedKey = `add_${Date.now().toString(36)}`;
  if (action.actionCode === 'addSign' && (!addSignUserId.value || !nextRoute)) {
    error.value = '请选择加签人员并确认下游路径可用';
    return;
  }
  const addSignSegment =
    action.actionCode === 'addSign'
      ? {
          nodeDefinitions: [
            {
              nodeKey: addedKey,
              nodeType: 'approval',
              approvalMode: 'all',
              title: addSignTitle.value,
              participantPolicyText: JSON.stringify({
                rules: [{ type: 'USER', ids: [addSignUserId.value] }],
              }),
              allowReject: true,
              requireRejectReason: true,
            },
          ],
          linkDefinitions: [
            {
              routeKey: `${addedKey}_in`,
              sourceNodeKey: action.nodeKey,
              targetNodeKey: addedKey,
              title: '加签',
            },
            {
              routeKey: `${addedKey}_out`,
              sourceNodeKey: addedKey,
              targetNodeKey: nextRoute!.targetNodeKey,
              title: '继续',
            },
          ],
        }
      : undefined;
  const payload = {
    addSignSegment,
    reason: reason.value,
    targetAssigneeId: action.targetAssigneeRequired ? targetAssigneeId.value : undefined,
    rejectResubmitMode: action.actionCode === 'reject' ? rejectMode.value : undefined,
    manualRouteSelections: selections,
  };
  busy.value = true;
  let completed = false;
  try {
    if (action.actionCode === 'submitApproval')
      await client.value.submit(props.context.moduleAlias, props.recordId, payload);
    else if (action.taskId) await client.value.taskAction(action.taskId, action.actionCode, payload);
    else if (status.value?.instanceId)
      await client.value.instanceAction(status.value.instanceId, action.actionCode, payload);
    activeAction.value = undefined;
    showSuccessMessage('流程操作已完成');
    await load();
    completed = true;
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow', phase: 'action' });
  } finally {
    busy.value = false;
    if (completed) emit('changed');
  }
}
</script>
<template>
  <section class="workflow-record-panel" aria-label="审批与任务">
    <header>
      <strong>审批与任务</strong><span>{{ workflowTitle(status?.displayStatus) }}</span
      ><span v-if="bundle">流程：{{ workflowTitle(bundle.instance.instanceStatus) }}</span
      ><UiButton :loading="loading" :disabled="busy || preparing || Boolean(activeAction)" @click="load"
        >刷新流程</UiButton
      >
    </header>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="status?.errorMessage">{{ status.errorMessage }}</p>
    <label class="workflow-field" v-if="archives.length"
      >查看流程轮次<UiSelect
        :disabled="busy || preparing || Boolean(activeAction)"
        :value="selectedHistory ?? ''"
        :options="[
          { value: '', label: '当前流程' },
          ...archives.map((item) => ({
            value: item.id,
            label: `历史 v${item.versionNo} · ${workflowTitle(item.instanceStatus)} · ${item.startedAt ?? ''}`,
          })),
        ]"
        @update:value="
          selectedHistory = String($event) || undefined;
          load();
        "
    /></label>
    <p v-if="selectedHistory">当前展示已归档的流程快照，可查看办理记录。</p>
    <div class="workflow-operations">
      <UiButton
        v-if="!selectedHistory && (status?.canSubmit || status?.displayStatus === 'UNSUBMITTED')"
        type="primary"
        :loading="busy"
        :disabled="loading || Boolean(activeAction)"
        @click="preview"
        >预览并提交审批</UiButton
      >
      <AdaptiveHeaderActionBar
        v-if="operationItems.length"
        class="workflow-task-actions"
        :actions="operationItems"
        @action="choose(actions[Number($event.key)]!)"
      />
      <UiButton
        v-if="
          !selectedHistory && status?.instanceId && ['PROCESSING', 'REJECTED'].includes(status.displayStatus)
        "
        :disabled="busy || Boolean(activeAction)"
        @click="choose({ actionCode: 'revoke', title: '撤回申请', reasonRequired: true })"
        >撤回申请</UiButton
      >
      <UiButton v-if="status?.instanceId" @click="showHistory = !showHistory">{{
        showHistory ? '收起办理记录' : '办理记录'
      }}</UiButton>
    </div>
    <RecordDetailDrawer
      :open="Boolean(activeAction)"
      :title="activeAction?.actionCode === 'complete' ? '办理业务任务' : (activeAction?.title ?? '审批操作')"
      width="wide"
      :before-close="mayLeave"
      @close="activeAction = undefined"
    >
      <form v-if="activeAction" class="workflow-action-form" @submit.prevent="execute">
        <UiError v-if="error" title="请检查办理内容" :message="error" />
        <p v-if="preparing">正在加载办理要求…</p>
        <label class="workflow-field"
          >操作意见<UiTextArea v-model:value="reason" aria-label="操作意见" :rows="2"
        /></label>
        <label class="workflow-field" v-if="activeAction.targetAssigneeRequired"
          >接收人<RecordPicker
            :context="accountContext"
            :load-options="accountOptions"
            :value="targetAssigneeId || undefined"
            mode="list"
            :title-of="(record) => userTitle(record)"
            @update:value="targetAssigneeId = $event ?? ''"
        /></label>
        <template v-if="activeAction.actionCode === 'addSign'"
          ><label class="workflow-field">加签节点名称<UiInput v-model:value="addSignTitle" /></label
          ><label class="workflow-field"
            >加签人员<RecordPicker
              v-model:value="addSignUserId"
              :context="accountContext"
              :load-options="accountOptions"
              mode="list"
              :title-of="(record) => userTitle(record)" /></label
        ></template>
        <template v-if="activeAction.actionCode === 'addSign'"
          ><label class="workflow-field" v-if="addSignRoutes.length > 1"
            >插入路径<UiSelect
              v-model:value="addSignRouteKey"
              :options="
                addSignRoutes.map((route) => ({
                  value: route.routeKey,
                  label:
                    bundle?.nodes.find((node) => node.nodeKey === route.targetNodeKey)?.nodeTitle ??
                    route.title ??
                    route.targetNodeKey,
                }))
              "
          /></label>
          <p>
            插入影响：{{ activeAction.nodeTitle }} → {{ addSignTitle }} →
            {{
              bundle?.nodes.find((node) => node.nodeKey === selectedAddSignRoute?.targetNodeKey)?.nodeTitle ??
              '请选择下游路径'
            }}。仅改变本次实例。
          </p></template
        >
        <label class="workflow-field" v-if="activeAction.actionCode === 'reject'"
          >重新提交方式<UiSelect
            v-model:value="rejectMode"
            :options="[
              { value: 'restart', label: '重新开始流程' },
              ...(activeAction.rejectReturnToMeSupported
                ? [{ value: 'return_to_me', label: '回到当前审批人' }]
                : []),
            ]"
        /></label>
        <fieldset v-for="branch in actionBranches" :key="branch.branchNodeKey">
          <legend>
            选择路径：{{
              branch.branchTitle ??
              bundle?.nodes.find((node) => node.nodeKey === branch.branchNodeKey)?.nodeTitle ??
              branch.branchNodeKey
            }}
          </legend>
          <UiSelect
            :value="manualRoutes[branch.branchNodeKey]?.[0]"
            :aria-label="`选择路径：${branch.branchTitle ?? branch.branchNodeKey}`"
            :options="
              branch.candidates
                .filter((item) => item.routeStatus === 'candidate')
                .map((candidate) => ({ value: candidate.routeKey, label: candidateTitle(candidate) }))
            "
            :disabled="busy || preparing"
            placeholder="请选择一条出口"
            @update:value="selectManualRoute(branch.branchNodeKey, $event)"
          />
          <p>每个分支单选一条路径；系统建议供参考，可以选择其他出口。</p>
          <UiInput
            v-model:value="manualReasons[branch.branchNodeKey]"
            :aria-label="`路径选择原因：${branch.branchTitle ?? branch.branchNodeKey}`"
            :placeholder="
              branch.requireManualSelectionReason ? '路径选择原因（必填）' : '路径选择原因（选填）'
            "
            :disabled="busy || preparing"
          />
        </fieldset>
        <template v-if="activeAction.actionCode === 'submitApproval'">
          <UiButton :loading="preparing" @click="refreshPreview">预览所选审批路径</UiButton>
          <p v-if="previewReady">
            流程：{{
              previewBundle?.definition?.definitionTitle ??
              previewBundle?.instance.definitionTitle ??
              status?.definition?.definitionTitle
            }}。以下为提交后首批办理人，后续节点到达时解析人员。
          </p>
          <ul v-if="previewReady">
            <li v-for="task in previewTasks" :key="task.id">
              {{
                previewBundle?.nodes.find(
                  (node) => node.id === task.nodeInstanceId || node.nodeKey === task.nodeKey,
                )?.nodeTitle ?? task.nodeKey
              }}
              · {{ task.assigneeTitle ?? '待任务创建时确定'
              }}<span v-if="task.originalAssigneeTitle && task.originalAssigneeTitle !== task.assigneeTitle">
                · 受 {{ task.originalAssigneeTitle }} 委托</span
              >
            </li>
          </ul>
          <WorkflowDiagram
            presentation="detail"
            v-if="previewReady && previewBundle"
            :nodes="previewBundle.nodes"
            :routes="previewBundle.routes"
            :layout-json="previewBundle.layoutJson"
            :semantic-json="previewBundle.semanticJson"
          />
        </template>
        <template v-if="activeAction.actionCode === 'complete' && activeAction.taskId">
          <UiButton @click="inspectTask(activeAction.taskId!)">检查任务完成条件</UiButton>
          <template v-if="taskProcess"
            ><p>
              {{
                taskProcess.evaluation.passed
                  ? '当前业务条件已满足'
                  : (taskProcess.evaluation.failureMessage ?? '业务完成条件尚未满足')
              }}
            </p>
            <ul>
              <li v-for="check in taskProcess.evaluation.checkResults" :key="check.checkKey">
                {{ check.checkKey }} · {{ check.passed ? '通过' : (check.failureMessage ?? '尚未完成') }}
              </li>
            </ul>
            <template v-for="guide in taskProcess.evaluation.guides" :key="guide.guideKey"
              ><UiButton
                v-if="
                  guide.guideKind === 'open_form' ||
                  (guide.guideKind === 'execute_action' && guide.targetActionCode === 'update')
                "
                @click="openBusinessForm(guide.guideKey)"
                >{{ guide.title ?? '填写业务数据并完成任务' }}</UiButton
              ><UiButton
                v-else-if="guide.guideKind === 'execute_action'"
                :disabled="busy || preparing || !manualChoicesReady"
                @click="executeBusinessAction(guide.guideKey)"
                >{{ guide.title ?? '执行业务动作并完成任务' }}</UiButton
              >
              <UiButton
                v-else-if="guide.guideKind === 'open_list' || guide.guideKind === 'focus_field'"
                :disabled="busy"
                @click="openReadGuide(guide)"
                >{{ guide.title ?? '查看办理指引' }}</UiButton
              >
              <p v-else>{{ guideConfig(guide).instruction ?? guide.title }}</p></template
            ></template
          >
          <RecordQueryListPanel
            v-if="guideListContext"
            :context="guideListContext"
            :title="guideListTitle"
            :standard-crud-actions="false"
            :standard-crud-row-actions="false"
          />
          <RecordDetailFields
            v-if="guideReadRecord"
            :record="guideReadRecord"
            :fields="guideReadFields"
            :option-context="context"
          />
          <template v-if="taskDraft"
            ><RecordFormFields
              :record="taskDraft"
              :fields="taskFields"
              mode="edit"
              :option-context="context"
              :disabled="busy"
              :validation-request-key="validationRequestKey"
              @validity-change="taskValidity = $event"
              @update:field="
                (field, value) => {
                  if (taskDraft) taskDraft = { ...taskDraft, [field]: value };
                }
              "
          /></template>
        </template>
      </form>
      <template #operation>
        <UiButton
          v-if="taskDraft"
          type="primary"
          :loading="busy"
          :disabled="preparing || !manualChoicesReady"
          @click="saveAndComplete"
          >保存业务并完成任务</UiButton
        >
        <UiButton
          v-else-if="activeAction"
          type="primary"
          :loading="busy"
          :disabled="
            preparing ||
            !manualChoicesReady ||
            (activeAction.actionCode === 'submitApproval' && !previewReady)
          "
          @click="execute"
          >确认{{ activeAction.title }}</UiButton
        >
        <UiButton :disabled="busy || preparing" @click="cancelAction">取消操作</UiButton>
      </template>
    </RecordDetailDrawer>
    <div v-if="showHistory" class="workflow-history">
      <h4>任务记录</h4>
      <RecordQueryListSurface
        :header-visible="false"
        :fill-height="false"
        :pageable="false"
        :columns="[
          { key: 'nodeTitle', title: '节点', dataIndex: 'nodeTitle' },
          { key: 'kind', title: '类型', dataIndex: 'kind' },
          { key: 'processor', title: '处理人', dataIndex: 'processor' },
          { key: 'status', title: '状态', dataIndex: 'status' },
        ]"
        :rows="
          tasks.map((task) => ({
            ...task,
            nodeTitle: bundle?.nodes.find((node) => node.id === task.nodeInstanceId)?.nodeTitle ?? '—',
            kind: workflowTitle(task.taskKind),
            processor:
              (task.actualProcessUserTitle ?? task.assigneeTitle ?? '—') +
              (task.processedByDelegation ? ' · 委托办理' : ''),
            status: workflowTitle(task.taskStatus),
          }))
        "
      />
      <WorkflowTimeline
        v-model:technical="showTechnicalHistory"
        :events="events"
        :tasks="tasks"
        :nodes="bundle?.nodes ?? []"
      />
    </div>
    <details v-if="bundle" class="workflow-map">
      <summary>查看流程图与路径判定</summary>
      <WorkflowDiagram
        presentation="detail"
        :key="bundle.instance.id"
        :nodes="bundle.nodes"
        :routes="bundle.routes"
        :layout-json="bundle.layoutJson"
        :semantic-json="bundle.semanticJson"
      />
      <details v-if="decidedBranchRoutes.length">
        <summary>路径判定记录</summary>
        <ul>
          <li v-for="route in decidedBranchRoutes" :key="route.routeKey">
            {{ bundle?.nodes.find((node) => node.nodeKey === route.sourceNodeKey)?.nodeTitle }} →
            {{ bundle?.nodes.find((node) => node.nodeKey === route.targetNodeKey)?.nodeTitle }}：
            {{ workflowTitle(route.routeStatus) }} ·
            {{
              workflowRouteSelectionTitle(
                route,
                bundle?.nodes.find((node) => node.nodeKey === route.sourceNodeKey),
              )
            }}
            {{
              ['normal_converged', 'converge_reached'].includes(route.routeReason ?? '')
                ? ` · ${workflowTitle(route.routeReason)}`
                : ''
            }}
            {{ route.selectedReason ? ` · ${route.selectedReason}` : '' }}
          </li>
        </ul>
      </details>
    </details>
  </section>
</template>
<style scoped>
.workflow-record-panel {
  display: grid;
  gap: 12px;
  padding: 16px 0;
  border-top: 1px solid var(--muyun-border-subtle);
}
header,
.workflow-operations {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.workflow-task-actions {
  flex: 1 1 280px;
  min-width: 0;
}
header strong {
  margin-right: auto;
}
.workflow-action-form {
  display: grid;
  gap: 12px;
  min-width: 0;
}
.workflow-field {
  display: grid;
  gap: 6px;
}
.workflow-history {
  display: grid;
  gap: 12px;
  min-width: 0;
}
.workflow-history h4 {
  margin: 0;
}
.workflow-map summary {
  padding: 8px 0;
  cursor: pointer;
  color: var(--muyun-primary);
}
</style>
