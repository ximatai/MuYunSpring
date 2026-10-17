<script setup lang="ts">
import {
  useLanguageContext,
  businessContentLanguages,
  normalizeError,
  userFacingErrorMessage,
  createWorkflowClient,
  createModuleContext,
  displayModuleRuntime,
  presentationLabel,
  type ModuleContext,
  useWorkspaceViewUnsavedState,
} from '@muyun/web-core';
import { computed, ref, watch, nextTick } from 'vue';

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
  WorkflowRecordInstance,
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
import { formatPlatformDateTime } from './platformDateTime';
import { usePlatformTimeZoneContext } from './platformTimeZoneContext';
import { presentPlatformError } from './platformErrorFeedback';
import { workflowAccountOptions } from './workflowAccountOptions';
import RecordPicker from './RecordPicker.vue';
import RecordQueryListPanel from './RecordQueryListPanel.vue';
import RecordFormFields, { type RecordFormValidity } from './RecordFormFields.vue';
import RecordDetailDrawer from './RecordDetailDrawer.vue';
import RecordQueryListSurface from './RecordQueryListSurface.vue';
import RecordDetailFields from './RecordDetailFields.vue';
import AdaptiveHeaderActionBar from './AdaptiveHeaderActionBar.vue';

import { resolveRecordFormFields } from './recordFormFieldModel';
import type { RecordFormRecord, RecordFormFieldDescriptor } from './recordFormFieldModel';
import WorkflowDiagram from './WorkflowDiagram.vue';
import WorkflowTimeline from './WorkflowTimeline.vue';
import {
  workflowNodePresentation,
  refreshWorkflowTextResources,
  refreshWorkflowTaskTextResources,
  sameWorkflowTextSource,
  workflowTitle as localizedWorkflowTitle,
  workflowRouteSelectionTitle as localizedRouteSelectionTitle,
} from './workflowPresentation';

const workflowLanguage = useLanguageContext();
const timeZone = usePlatformTimeZoneContext();
function roundTime(value?: string) {
  return formatPlatformDateTime(value, { language: workflowLanguage, timeZone: timeZone?.value }).text;
}
function workflowTitle(value?: string) {
  return localizedWorkflowTitle(value, workflowLanguage);
}
function workflowRouteSelectionTitle(...args: Parameters<typeof localizedRouteSelectionTitle>) {
  return localizedRouteSelectionTitle(args[0], args[1], workflowLanguage);
}
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
const originalBundle = ref<WorkflowRenderBundle>();
const bundle = computed(() =>
  originalBundle.value
    ? {
        ...originalBundle.value,
        nodes: originalBundle.value.nodes.map((node) => workflowNodePresentation(node, workflowLanguage)),
      }
    : undefined,
);
const tasks = ref<WorkflowTask[]>([]),
  events = ref<WorkflowEvent[]>([]),
  actions = ref<WorkflowAction[]>([]),
  branches = ref<WorkflowBranch[]>([]);
const loading = ref(false),
  busy = ref(false),
  error = ref<() => string>();
const activeAction = ref<WorkflowAction>();
const reasonSourceLanguage = ref('__unknown__');
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
      (activeAction.value.actionCode === 'addSign' && addSignTitle.value !== initialAddSignTitle.value) ||
      Object.values(manualRoutes.value).some((keys) => keys.length) ||
      (taskDraft.value && JSON.stringify(taskDraft.value) !== taskInitial.value)),
  ),
);
useWorkspaceViewUnsavedState(
  () => workflowLanguage.t('platform.unsaved-source.WorkflowRecordPanel', '审批办理'),
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
function actionTitle(action: WorkflowAction) {
  const title = workflowTitle(action.actionCode);
  return title === action.actionCode ? action.title : title;
}
function actionNodeTitle(action: WorkflowAction) {
  return bundle.value?.nodes.find((node) => node.nodeKey === action.nodeKey)?.nodeTitle ?? action.nodeTitle;
}
const operationItems = computed(() =>
  actions.value.map((action, index) => ({
    key: String(index),
    title: `${actionNodeTitle(action) ? actionNodeTitle(action) + ' · ' : ''}${action.actionCode === 'complete' ? workflowLanguage.t('platform.screen.WorkflowRecordPanel.process-task', '办理任务') : actionTitle(action)}`,
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
    (await confirmAction(
      {
        title: workflowLanguage.t(
          'platform.screen.WorkflowRecordPanel.discard-unsaved-task-input',
          '放弃未保存的办理内容？',
        ),
        content: workflowLanguage.t(
          'platform.screen.WorkflowRecordPanel.this-input-has-not-been-submitted-you',
          '当前填写尚未提交，放弃后可重新进入任务办理。',
        ),
        okText: workflowLanguage.t('platform.screen.WorkflowRecordPanel.discard-input', '放弃填写'),
        danger: true,
      },
      workflowLanguage,
    ))
  );
}
async function cancelAction() {
  if (await mayLeave()) activeAction.value = undefined;
}
defineExpose({ mayLeave });
const archives = ref<WorkflowHistoryInstance[]>([]),
  selectedHistory = ref<string>();
const recordInstances = ref<WorkflowRecordInstance[]>([]);
const selectedInstance = ref<string>();
const instancePage = ref(1),
  instancePages = ref(0),
  instancePaging = ref(false);
const selectedInstanceView = ref<WorkflowRecordInstance>();
let instanceDirectoryRevision = 0;
const currentInstanceId = computed(
  () => props.instanceId ?? selectedInstance.value ?? status.value?.instanceId,
);
function instanceLabel(item: WorkflowRecordInstance) {
  return [
    workflowLanguage.t('platform.screen.WorkflowRecordPanel.current-workflow', '当前流程'),
    roundTime(item.startedAt),
    item.versionNo == null
      ? ''
      : workflowLanguage.t('platform.workflow.definition-version', '定义版本 v{version}', {
          version: item.versionNo,
        }),
    workflowTitle(item.instanceStatus),
  ]
    .filter(Boolean)
    .join(' · ');
}
const roundOptions = computed(() => {
  const current = recordInstances.value.map((item) => ({
    value: `current:${item.id}`,
    label: instanceLabel(item),
  }));
  const id = currentInstanceId.value;
  if (
    id &&
    !current.some((item) => item.value === `current:${id}`) &&
    !archives.value.some((item) => item.id === id)
  )
    current.unshift({
      value: `current:${id}`,
      label:
        selectedInstanceView.value?.id === id
          ? instanceLabel(selectedInstanceView.value)
          : workflowLanguage.t('platform.screen.WorkflowRecordPanel.current-workflow', '当前流程'),
    });
  if (status.value?.canSubmit || status.value?.displayStatus === 'UNSUBMITTED')
    current.unshift({
      value: '',
      label: workflowLanguage.t('platform.screen.WorkflowRecordPanel.current-workflow', '当前流程'),
    });
  return [
    ...current,
    ...archives.value.map((item) => ({
      value: `history:${item.id}`,
      label: workflowLanguage.t(
        'platform.workflow.archived-round',
        '已归档 · {time} · 定义版本 v{version} · 归档时：{status}',
        {
          version: item.versionNo,
          status: workflowTitle(item.instanceStatus),
          time: roundTime(item.startedAt),
        },
      ),
    })),
  ];
});
function selectRound(value: unknown) {
  if (
    busy.value ||
    preparing.value ||
    loading.value ||
    activeAction.value ||
    typeof value !== 'string' ||
    !roundOptions.value.some((item) => item.value === value)
  )
    return;
  selectedHistory.value = value.startsWith('history:') ? value.slice(8) : undefined;
  if (!selectedHistory.value) {
    selectedInstance.value = value.startsWith('current:') ? value.slice(8) : undefined;
    selectedInstanceView.value = recordInstances.value.find((item) => item.id === selectedInstance.value);
  }
  void load();
}
async function pageInstances(page: number) {
  if (instancePaging.value || activeAction.value || page < 1 || page > instancePages.value) return;
  const token = ++instanceDirectoryRevision,
    context = props.context,
    recordId = props.recordId;
  instancePaging.value = true;
  try {
    const response = await client.value.recordInstances(context.moduleAlias, recordId, page);
    if (token !== instanceDirectoryRevision || context !== props.context || recordId !== props.recordId)
      return;
    recordInstances.value = response.records;
    instancePage.value = response.pageNum;
    instancePages.value = response.pages;
  } catch (cause) {
    if (token === instanceDirectoryRevision)
      error.value = () => userFacingErrorMessage(normalizeError(cause), workflowLanguage);
  } finally {
    if (token === instanceDirectoryRevision) instancePaging.value = false;
  }
}
const accountOptions = computed(() => workflowAccountOptions(props.context.http));
const accountContext = computed(() =>
  createModuleContext<Record<string, unknown>>({ http: props.context.http, moduleAlias: 'iam.user' }),
);
const addSignUserId = ref<string>(),
  addSignTitle = ref(''),
  initialAddSignTitle = ref(''),
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
const displayTaskGuides = computed(
  () =>
    taskProcess.value?.evaluation.guides.map((guide) => ({
      ...guide,
      title:
        guide.title == null ? undefined : presentationLabel(guide.title, guide.titleText, workflowLanguage),
    })) ?? [],
);
const displayTaskFailure = computed(() => {
  const evaluation = taskProcess.value?.evaluation;
  return evaluation?.failureMessage
    ? presentationLabel(evaluation.failureMessage, evaluation.failureMessageText, workflowLanguage)
    : workflowLanguage.t(
        'platform.screen.WorkflowRecordPanel.business-completion-conditions-are-not-yet-met',
        '业务完成条件尚未满足',
      );
});
const displayTaskChecks = computed(
  () =>
    taskProcess.value?.evaluation.checkResults.map((check) => ({
      ...check,
      checkTitle: presentationLabel(
        check.checkTitle || check.checkKey,
        check.checkTitleText,
        workflowLanguage,
      ),
      failureMessage:
        check.failureMessage == null
          ? undefined
          : presentationLabel(check.failureMessage, check.failureMessageText, workflowLanguage),
    })) ?? [],
);
function guideInstruction(guide: WorkflowTaskPreparation['evaluation']['guides'][number]) {
  const original = guideConfig(guide).instruction;
  return typeof original === 'string'
    ? presentationLabel(original, guide.instructionText, workflowLanguage)
    : guide.title;
}

const taskDraft = ref<RecordFormRecord>(),
  originalTaskFields = ref(new Map<string, RecordFormFieldDescriptor>()),
  taskGuideKey = ref<string>();
const taskFields = computed(() => {
  const display = resolveRecordFormFields(displayModuleRuntime(props.context.runtime)?.uiDescriptor);
  return new Map([...originalTaskFields.value].map(([key, original]) => [key, display.get(key) ?? original]));
});
const guideListContext = ref<ModuleContext<Record<string, unknown>>>(),
  guideListKey = ref<string>();
const guideListTitle = computed(
  () =>
    displayTaskGuides.value.find((guide) => guide.guideKey === guideListKey.value)?.title ??
    workflowLanguage.t('platform.screen.WorkflowRecordPanel.business-records', '业务记录'),
);
const originalGuideReadFields = ref(new Map<string, RecordFormFieldDescriptor>()),
  guideReadRecord = ref<RecordFormRecord>();
const guideReadFields = computed(() => {
  const display = resolveRecordFormFields(displayModuleRuntime(props.context.runtime)?.uiDescriptor);
  return new Map(
    [...originalGuideReadFields.value].map(([key, original]) => [key, display.get(key) ?? original]),
  );
});
function guideConfig(guide: { guideConfigText?: string }): Record<string, unknown> {
  try {
    return JSON.parse(guide.guideConfigText ?? '{}');
  } catch {
    return {};
  }
}
async function openReadGuide(guide: {
  guideKey: string;
  title?: string;
  guideKind: string;
  targetModuleAlias?: string;
  guideConfigText?: string;
}) {
  guideListContext.value = undefined;
  guideReadRecord.value = undefined;
  if (guide.guideKind === 'open_list') {
    guideListKey.value = guide.guideKey;
    guideListContext.value = createModuleContext<Record<string, unknown>>({
      http: props.context.http,
      language: workflowLanguage,
      moduleAlias: guide.targetModuleAlias ?? props.context.moduleAlias,
    });
  } else {
    try {
      const runtime = await props.context.runtime.ready,
        key = String(guideConfig(guide).field ?? '');
      originalGuideReadFields.value = new Map(
        [...resolveRecordFormFields(runtime.uiDescriptor)].filter(([field]) => field === key),
      );
      if (!originalGuideReadFields.value.size)
        throw new Error(
          workflowLanguage.t(
            'platform.screen.WorkflowRecordPanel.the-guide-field-does-not-exist',
            '指引字段不存在',
          ),
        );
      guideReadRecord.value = await props.context.crud.view(props.recordId);
    } catch (cause) {
      presentPlatformError(cause, { source: 'workflow-task', phase: 'load', language: workflowLanguage });
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
      reasonSourceLanguage: reasonSourceLanguage.value === '__unknown__' ? null : reasonSourceLanguage.value,
      manualRouteSelections: routeSelections(),
    });
    activeAction.value = undefined;
    await load();
    completed = true;
    showSuccessMessage(
      workflowLanguage.t(
        'platform.screen.WorkflowRecordPanel.business-action-and-task-completed',
        '业务动作和任务已完成',
      ),
    );
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-task', phase: 'action', language: workflowLanguage });
  } finally {
    busy.value = false;
    if (completed) emit('changed');
  }
}

let revision = 0,
  formRequest = 0;
let textResourceRequest = 0;
watch(
  () => props.context.runtime.snapshot?.()?.textBindings,
  async () => {
    const current = originalBundle.value;
    if (!current) return;
    const request = ++textResourceRequest;
    const workflowRevision = revision;
    const history = selectedHistory.value;
    const context = props.context;
    const process = taskProcess.value;
    const taskId = activeAction.value?.taskId;
    try {
      const [next, nextEvents, nextProcess] = await Promise.all([
        history ? client.value.historyBundle(history) : client.value.bundle(current.instance.id),
        history ? client.value.historyEvents(history) : client.value.eventViews(current.instance.id),
        process && taskId ? client.value.prepareTask(taskId) : undefined,
      ]);
      if (
        request !== textResourceRequest ||
        revision !== workflowRevision ||
        selectedHistory.value !== history ||
        props.context !== context ||
        originalBundle.value?.instance.id !== current.instance.id
      )
        return;
      originalBundle.value = refreshWorkflowTextResources(originalBundle.value, next);
      if (process && nextProcess && taskProcess.value === process && activeAction.value?.taskId === taskId) {
        taskProcess.value = refreshWorkflowTaskTextResources(process, nextProcess);
      }

      events.value = events.value.map((event) => {
        const refreshed = nextEvents.find((item) => item.id === event.id);
        return sameWorkflowTextSource(event.messageText, refreshed?.messageText)
          ? { ...event, messageText: refreshed?.messageText }
          : event;
      });
    } catch (cause) {
      presentPlatformError(cause, {
        source: 'workflow-text-resources',
        phase: 'load',
        language: workflowLanguage,
      });
    }
  },
);
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
    selectedInstance.value = undefined;
    selectedInstanceView.value = undefined;
    instancePage.value = 1;
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
  ++instanceDirectoryRevision;
  instancePaging.value = false;
  ++formRequest;
  preparing.value = false;
  loading.value = true;
  error.value = undefined;
  // A new round is unusable until its entire authorized snapshot has loaded successfully.
  status.value = undefined;
  originalBundle.value = undefined;
  tasks.value = [];
  events.value = [];
  actions.value = [];
  branches.value = [];
  archives.value = [];
  recordInstances.value = [];
  instancePages.value = 0;
  activeAction.value = undefined;
  taskProcess.value = undefined;
  taskDraft.value = undefined;
  taskGuideKey.value = undefined;
  guideReadRecord.value = undefined;
  guideListContext.value = undefined;
  previewReady.value = false;
  previewBundle.value = undefined;
  previewTasks.value = [];
  try {
    const approvalEntry = !props.instanceId && props.context.abilities?.has('approval') !== false;
    const [next, history, directory] = await Promise.all([
      approvalEntry
        ? client.value.status(props.context.moduleAlias, props.recordId)
        : Promise.resolve(undefined),
      client.value.history(props.context.moduleAlias, props.recordId),
      !props.instanceId && props.context.abilities?.has('workflow') === true
        ? client.value.recordInstances(props.context.moduleAlias, props.recordId, instancePage.value)
        : Promise.resolve(undefined),
    ]);
    if (current !== revision) return;
    status.value = next;
    archives.value = history;
    recordInstances.value = directory?.records ?? [];
    instancePages.value = directory?.pages ?? 0;
    if (selectedHistory.value && !history.some((item) => item.id === selectedHistory.value))
      selectedHistory.value = undefined;
    const id = currentInstanceId.value;
    if (!selectedHistory.value && id && history.some((item) => item.id === id)) {
      selectedHistory.value = id;
      if (!props.instanceId) selectedInstance.value = undefined;
    }
    if (!props.instanceId && !selectedHistory.value && !selectedInstance.value) {
      selectedInstance.value = next?.instanceId ?? directory?.records[0]?.id;
      selectedInstanceView.value = directory?.records.find((item) => item.id === selectedInstance.value);
      if (!approvalEntry && !selectedInstance.value && history.length) selectedHistory.value = history[0]!.id;
    }
    activeAction.value = undefined;
    if (selectedHistory.value) {
      const [graph, taskList, eventList] = await Promise.all([
        client.value.historyBundle(selectedHistory.value),
        client.value.historyTasks(selectedHistory.value),
        client.value.historyEvents(selectedHistory.value),
      ]);
      if (current !== revision) return;
      originalBundle.value = graph;
      tasks.value = taskList;
      events.value = eventList;
      actions.value = [];
      branches.value = [];
      return;
    }
    const selectedId = currentInstanceId.value;
    if (selectedId) {
      const [graph, taskList, eventList, available, choices] = await Promise.all([
        client.value.bundle(selectedId),
        client.value.taskViews(selectedId),
        client.value.eventViews(selectedId),
        client.value.actions(selectedId),
        client.value.branches(selectedId),
      ]);
      if (current !== revision) return;
      originalBundle.value = graph;
      tasks.value = taskList;
      events.value = eventList;
      actions.value = available;
      branches.value = choices;
    } else {
      originalBundle.value = undefined;
      tasks.value = [];
      events.value = [];
      actions.value = [];
      branches.value = [];
    }
  } catch (cause) {
    if (current === revision) {
      const failure = normalizeError(cause);
      error.value = () => userFacingErrorMessage(failure, workflowLanguage);
    }
  } finally {
    if (current === revision) loading.value = false;
  }
}
async function choose(action: WorkflowAction | undefined) {
  const current = revision;
  if (!action || loading.value || preparing.value || selectedHistory.value) return;
  if (
    action.actionCode !== 'submitApproval' &&
    (!originalBundle.value || originalBundle.value.instance.id !== currentInstanceId.value)
  )
    return;
  if (activeAction.value && !(await mayLeave())) return;
  if (current !== revision || loading.value || selectedHistory.value) return;
  error.value = undefined;
  validationRequestKey.value = 0;
  previewReady.value = false;
  activeAction.value = action;
  reason.value = '';
  reasonSourceLanguage.value = '__unknown__';
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
  if (action.actionCode === 'addSign') {
    addSignTitle.value = workflowLanguage.t('platform.workflow-record.additional-approval', '追加审批');
    initialAddSignTitle.value = addSignTitle.value;
    await prepareAddSign(action);
  }
}
async function prepareAddSign(action: WorkflowAction) {
  const id = currentInstanceId.value,
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
      presentPlatformError(cause, { source: 'workflow-add-sign', phase: 'load', language: workflowLanguage });
  } finally {
    if (current === revision && action === activeAction.value) preparing.value = false;
  }
}
async function preview() {
  busy.value = true;
  try {
    branches.value = await client.value.submitBranches(props.context.moduleAlias, props.recordId);
    await choose({
      actionCode: 'submitApproval',
      title: '提交审批',
      reasonRequired: false,
    });
    if (!branches.value.length) await refreshPreview();
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow', phase: 'load', language: workflowLanguage });
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
    if (isCurrent())
      presentPlatformError(cause, { source: 'workflow', phase: 'validation', language: workflowLanguage });
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
  const instanceId = currentInstanceId.value;
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
      error.value = () =>
        workflowLanguage.t(
          'platform.screen.WorkflowRecordPanel.unable-to-load-available-routes-reopen-this-action-and-try',
          '路径候选加载失败，请重新进入操作后重试',
        );
      presentPlatformError(cause, { source: 'workflow', phase: 'load', language: workflowLanguage });
    }
  } finally {
    if (requestId === branchRequest) preparing.value = false;
  }
}
async function selectManualRoute(branchNodeKey: string, routeKey: unknown) {
  manualRoutes.value[branchNodeKey] = routeKey ? [String(routeKey)] : [];
  previewReady.value = false;
  error.value = undefined;
  await refreshManualChoices();
}
function manualSelectionValid() {
  if (preparing.value || !manualChoicesReady.value) {
    error.value = () =>
      workflowLanguage.t(
        'platform.screen.WorkflowRecordPanel.wait-for-available-routes-to-load-if-loading-fails-reopen',
        '请等待路径候选加载完成，失败时重新进入操作后重试',
      );
    return false;
  }
  for (const branch of actionBranches.value) {
    if (manualRoutes.value[branch.branchNodeKey]?.length !== 1) {
      error.value = () =>
        workflowLanguage.t(
          'platform.screen.WorkflowRecordPanel.select-a-route-for-value',
          '请为「{value1}」选择一条路径',
          {
            value1:
              branch.branchTitle ??
              workflowLanguage.t('platform.screen.WorkflowRecordPanel.manual-branch', '手工分支'),
          },
        );
      return false;
    }
    if (branch.requireManualSelectionReason && !manualReasons.value[branch.branchNodeKey]?.trim()) {
      error.value = () =>
        workflowLanguage.t(
          'platform.screen.WorkflowRecordPanel.enter-a-reason-for-the-route-selected-for-value',
          '请填写「{value1}」的路径选择原因',
          {
            value1:
              branch.branchTitle ??
              workflowLanguage.t('platform.screen.WorkflowRecordPanel.manual-branch', '手工分支'),
          },
        );
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
    candidate.conditionMatched ? workflowLanguage.t('platform.workflow.condition_matched', '条件命中') : '',
    candidate.defaultRoute ? workflowLanguage.t('platform.workflow-config.default-exit', '默认出口') : '',
    candidate.recommended
      ? workflowLanguage.t('platform.screen.WorkflowRecordPanel.suggested', '系统建议')
      : '',
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
    presentPlatformError(cause, { source: 'workflow', phase: 'validation', language: workflowLanguage });
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
    if (fields.size === 0)
      throw new Error(
        workflowLanguage.t(
          'platform.screen.WorkflowRecordPanel.no-editable-fields-are-configured-for-this-task',
          '当前任务未配置可编辑字段',
        ),
      );
    const draft = await context.crud.view(recordId);
    if (!isCurrent()) return;
    taskGuideKey.value = guideKey;
    originalTaskFields.value = fields;
    taskDraft.value = draft;
    taskInitial.value = JSON.stringify(draft);
    taskValidity.value = { valid: true, errors: {} };
    validationRequestKey.value = 0;
  } catch (cause) {
    if (isCurrent())
      presentPlatformError(cause, { source: 'workflow-task', phase: 'load', language: workflowLanguage });
  } finally {
    if (isCurrent()) preparing.value = false;
  }
}
async function saveAndComplete() {
  const taskId = activeAction.value?.taskId,
    draft = taskDraft.value;
  if (!taskId || !taskGuideKey.value || !draft || busy.value || preparing.value) return;
  if (!manualSelectionValid()) return;
  const current = revision,
    context = props.context,
    action = activeAction.value;
  validationRequestKey.value++;
  await nextTick();
  if (
    current !== revision ||
    context !== props.context ||
    action !== activeAction.value ||
    draft !== taskDraft.value
  )
    return;
  if (!taskValidity.value.valid) {
    error.value = () =>
      workflowLanguage.t(
        'platform.screen.WorkflowRecordPanel.correct-the-marked-business-fields-before-submitting',
        '请修正标记的业务字段后再提交',
      );
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
      reasonSourceLanguage: reasonSourceLanguage.value === '__unknown__' ? null : reasonSourceLanguage.value,
      contentLanguages: Object.fromEntries(
        Object.entries((draft.$contentLanguages ?? {}) as Record<string, unknown>).filter(
          ([field]) => taskFields.value.has(field) && Object.hasOwn(draft, field),
        ),
      ),
      manualRouteSelections: routeSelections(),
    });
    activeAction.value = undefined;
    taskDraft.value = undefined;
    await load();
    completed = true;
    showSuccessMessage(
      workflowLanguage.t(
        'platform.screen.WorkflowRecordPanel.business-data-saved-and-task-completed',
        '业务数据已保存，任务已完成',
      ),
    );
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-task', phase: 'action', language: workflowLanguage });
  } finally {
    busy.value = false;
    if (completed) emit('changed');
  }
}
async function execute() {
  const action = activeAction.value;
  if (!action || busy.value || preparing.value || loading.value || selectedHistory.value) return;
  const instanceId = originalBundle.value?.instance.id;
  if (action.actionCode !== 'submitApproval' && (!instanceId || instanceId !== currentInstanceId.value))
    return;
  if (!manualSelectionValid()) return;
  if (action.actionCode === 'submitApproval' && !previewReady.value) {
    error.value = () =>
      workflowLanguage.t(
        'platform.screen.WorkflowRecordPanel.preview-the-selected-approval-routes-first',
        '请先预览所选审批路径',
      );
    return;
  }
  if (action.reasonRequired && !reason.value.trim()) {
    error.value = () =>
      workflowLanguage.t(
        'platform.screen.WorkflowRecordPanel.enter-a-reason-for-this-action',
        '请填写操作原因',
      );
    return;
  }
  if (action.targetAssigneeRequired && !targetAssigneeId.value.trim()) {
    error.value = () =>
      workflowLanguage.t('platform.screen.WorkflowRecordPanel.select-a-recipient', '请选择接收人');
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
    error.value = () =>
      workflowLanguage.t(
        'platform.screen.WorkflowRecordPanel.select-an-additional-approver-and-confirm-an-available-downstream-route',
        '请选择加签人员并确认下游路径可用',
      );
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
              title: workflowLanguage.t('platform.workflow.add_sign', '加签'),
            },
            {
              routeKey: `${addedKey}_out`,
              sourceNodeKey: addedKey,
              targetNodeKey: nextRoute!.targetNodeKey,
              title: workflowLanguage.t('platform.screen.WorkflowRecordPanel.continue', '继续'),
            },
          ],
        }
      : undefined;
  const payload = {
    addSignSegment,
    reason: reason.value,
    reasonSourceLanguage: reasonSourceLanguage.value === '__unknown__' ? null : reasonSourceLanguage.value,
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
    else if (instanceId) await client.value.instanceAction(instanceId, action.actionCode, payload);
    else throw new Error('Workflow instance is unavailable');
    activeAction.value = undefined;
    showSuccessMessage(
      workflowLanguage.t('platform.screen.WorkflowRecordPanel.workflow-action-completed', '流程操作已完成'),
    );
    await load();
    completed = true;
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow', phase: 'action', language: workflowLanguage });
  } finally {
    busy.value = false;
    if (completed) emit('changed');
  }
}
</script>
<template>
  <section
    class="workflow-record-panel"
    :aria-label="workflowLanguage.t('platform.screen.WorkflowRecordPanel.approvals-and-tasks', '审批与任务')"
  >
    <header>
      <strong>{{
        workflowLanguage.t('platform.screen.WorkflowRecordPanel.approvals-and-tasks', '审批与任务')
      }}</strong
      ><span v-if="status">{{ workflowTitle(status.displayStatus) }}</span
      ><span v-if="bundle">{{
        workflowLanguage.t(
          selectedHistory ? 'platform.workflow.archived-status' : 'platform.workflow-record.status',
          selectedHistory ? '归档时状态：{status}' : '流程：{status}',
          {
            status: workflowTitle(bundle.instance.instanceStatus),
          },
        )
      }}</span
      ><UiButton :loading="loading" :disabled="busy || preparing || Boolean(activeAction)" @click="load">{{
        workflowLanguage.t('platform.screen.WorkflowRecordPanel.refresh-workflow', '刷新流程')
      }}</UiButton>
    </header>
    <p v-if="error" role="alert">{{ error?.() }}</p>
    <p v-if="status?.errorMessage">
      {{ presentationLabel(status.errorMessage, status.errorMessageText, workflowLanguage) }}
    </p>
    <label class="workflow-field" v-if="roundOptions.length"
      >{{ workflowLanguage.t('platform.screen.WorkflowRecordPanel.view-workflow-rounds', '查看流程轮次')
      }}<UiSelect
        :disabled="loading || busy || preparing || instancePaging || Boolean(activeAction)"
        :value="
          selectedHistory
            ? `history:${selectedHistory}`
            : currentInstanceId
              ? `current:${currentInstanceId}`
              : ''
        "
        :options="roundOptions"
        @update:value="selectRound"
    /></label>
    <div v-if="instancePages > 1" class="workflow-operations">
      <UiButton
        :disabled="instancePage <= 1 || instancePaging || busy || Boolean(activeAction)"
        @click="pageInstances(instancePage - 1)"
        >{{ workflowLanguage.t('platform.content.previous', '上一页') }}</UiButton
      >
      <span>{{ instancePage }} / {{ instancePages }}</span>
      <UiButton
        :disabled="instancePage >= instancePages || instancePaging || busy || Boolean(activeAction)"
        @click="pageInstances(instancePage + 1)"
        >{{ workflowLanguage.t('platform.content.next', '下一页') }}</UiButton
      >
    </div>
    <p v-if="selectedHistory">
      {{
        workflowLanguage.t(
          'platform.screen.WorkflowRecordPanel.this-archived-workflow-snapshot-includes-its-processing',
          '当前展示已归档的流程快照，可查看办理记录。',
        )
      }}
    </p>
    <div class="workflow-operations">
      <UiButton
        v-if="!selectedHistory && (status?.canSubmit || status?.displayStatus === 'UNSUBMITTED')"
        type="primary"
        :loading="busy"
        :disabled="loading || Boolean(activeAction)"
        @click="preview"
        >{{
          workflowLanguage.t(
            'platform.screen.WorkflowRecordPanel.preview-and-submit-approval',
            '预览并提交审批',
          )
        }}</UiButton
      >
      <AdaptiveHeaderActionBar
        v-if="operationItems.length"
        class="workflow-task-actions"
        :actions="operationItems"
        @action="choose(actions[Number($event.key)]!)"
      />
      <UiButton
        v-if="
          !selectedHistory &&
          status?.instanceId &&
          status.instanceId === currentInstanceId &&
          ['PROCESSING', 'REJECTED'].includes(status.displayStatus)
        "
        :disabled="busy || Boolean(activeAction)"
        @click="
          choose({
            actionCode: 'revoke',
            title: '撤回申请',
            reasonRequired: true,
          })
        "
        >{{
          workflowLanguage.t('platform.screen.WorkflowRecordPanel.withdraw-application', '撤回申请')
        }}</UiButton
      >
      <UiButton v-if="bundle?.instance.id" @click="showHistory = !showHistory">{{
        showHistory
          ? workflowLanguage.t('platform.screen.WorkflowRecordPanel.hide-processing-history', '收起办理记录')
          : workflowLanguage.t('platform.screen.WorkflowRecordPanel.processing-history', '办理记录')
      }}</UiButton>
    </div>
    <RecordDetailDrawer
      :open="Boolean(activeAction)"
      :title="
        activeAction
          ? actionTitle(activeAction)
          : workflowLanguage.t('platform.screen.WorkflowRecordPanel.workflow-operation', '审批操作')
      "
      width="wide"
      :before-close="mayLeave"
      @close="activeAction = undefined"
    >
      <form v-if="activeAction" class="workflow-action-form" @submit.prevent="execute">
        <UiError
          v-if="error"
          :title="
            workflowLanguage.t('platform.screen.WorkflowRecordPanel.check-task-input', '请检查办理内容')
          "
          :message="error?.()"
        />
        <p v-if="preparing">
          {{
            workflowLanguage.t(
              'platform.screen.WorkflowRecordPanel.loading-task-requirements',
              '正在加载办理要求…',
            )
          }}
        </p>
        <label class="workflow-field"
          >{{ workflowLanguage.t('platform.screen.WorkflowRecordPanel.processing-opinion', '操作意见')
          }}<UiTextArea
            v-model:value="reason"
            :aria-label="
              workflowLanguage.t('platform.screen.WorkflowRecordPanel.processing-opinion', '操作意见')
            "
            :rows="2"
        /></label>
        <label class="workflow-field">
          <span>{{ workflowLanguage.t('platform.content.source-language', '本次原文的填写语言') }}</span>
          <UiSelect
            v-model:value="reasonSourceLanguage"
            :aria-label="workflowLanguage.t('platform.content.source-language', '本次原文的填写语言')"
            :options="[
              {
                value: '__unknown__',
                label: workflowLanguage.t('platform.content.unknown-language', '来源语言未确定'),
              },
              ...businessContentLanguages,
            ]"
          />
        </label>
        <label class="workflow-field" v-if="activeAction.targetAssigneeRequired"
          >{{ workflowLanguage.t('platform.screen.WorkflowRecordPanel.recipient', '接收人')
          }}<RecordPicker
            :context="accountContext"
            :load-options="accountOptions"
            :value="targetAssigneeId || undefined"
            mode="list"
            :title-of="(record) => userTitle(record)"
            @update:value="targetAssigneeId = $event ?? ''"
        /></label>
        <template v-if="activeAction.actionCode === 'addSign'"
          ><label class="workflow-field"
            >{{
              workflowLanguage.t(
                'platform.screen.WorkflowRecordPanel.additional-approval-node-name',
                '加签节点名称',
              )
            }}<UiInput v-model:value="addSignTitle" /></label
          ><label class="workflow-field"
            >{{ workflowLanguage.t('platform.screen.WorkflowRecordPanel.additional-approver', '加签人员')
            }}<RecordPicker
              v-model:value="addSignUserId"
              :context="accountContext"
              :load-options="accountOptions"
              mode="list"
              :title-of="(record) => userTitle(record)" /></label
        ></template>
        <template v-if="activeAction.actionCode === 'addSign'"
          ><label class="workflow-field" v-if="addSignRoutes.length > 1"
            >{{ workflowLanguage.t('platform.screen.WorkflowRecordPanel.insertion-route', '插入路径')
            }}<UiSelect
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
            {{
              workflowLanguage.t(
                'platform.workflow-record.insertion-impact',
                '插入影响：{source} → {inserted} → {target}。仅改变本次实例。',
                {
                  source: actionNodeTitle(activeAction),
                  inserted: addSignTitle,
                  target:
                    bundle?.nodes.find((node) => node.nodeKey === selectedAddSignRoute?.targetNodeKey)
                      ?.nodeTitle ??
                    workflowLanguage.t(
                      'platform.screen.WorkflowRecordPanel.select-a-downstream-route',
                      '请选择下游路径',
                    ),
                },
              )
            }}
          </p></template
        >
        <label class="workflow-field" v-if="activeAction.actionCode === 'reject'"
          >{{ workflowLanguage.t('platform.screen.WorkflowRecordPanel.resubmission-mode', '重新提交方式')
          }}<UiSelect
            v-model:value="rejectMode"
            :options="[
              {
                value: 'restart',
                label: workflowLanguage.t(
                  'platform.screen.WorkflowRecordPanel.restart-the-workflow',
                  '重新开始流程',
                ),
              },
              ...(activeAction.rejectReturnToMeSupported
                ? [
                    {
                      value: 'return_to_me',
                      label: workflowLanguage.t(
                        'platform.screen.WorkflowRecordPanel.return-to-the-current-approver',
                        '回到当前审批人',
                      ),
                    },
                  ]
                : []),
            ]"
        /></label>
        <fieldset v-for="branch in actionBranches" :key="branch.branchNodeKey">
          <legend>
            {{
              workflowLanguage.t('platform.workflow-record.select-route', '选择路径：{branch}', {
                branch:
                  branch.branchTitle ??
                  bundle?.nodes.find((node) => node.nodeKey === branch.branchNodeKey)?.nodeTitle ??
                  branch.branchNodeKey,
              })
            }}
          </legend>
          <UiSelect
            :value="manualRoutes[branch.branchNodeKey]?.[0]"
            :aria-label="
              workflowLanguage.t(
                'platform.screen.WorkflowRecordPanel.select-a-route-value',
                '选择路径：{value1}',
                { value1: branch.branchTitle ?? branch.branchNodeKey },
              )
            "
            :options="
              branch.candidates
                .filter((item) => item.routeStatus === 'candidate')
                .map((candidate) => ({ value: candidate.routeKey, label: candidateTitle(candidate) }))
            "
            :disabled="busy || preparing"
            :placeholder="
              workflowLanguage.t(
                'platform.screen.WorkflowRecordPanel.choose-an-outgoing-route',
                '请选择一条出口',
              )
            "
            @update:value="selectManualRoute(branch.branchNodeKey, $event)"
          />
          <p>
            {{
              workflowLanguage.t(
                'platform.screen.WorkflowRecordPanel.choose-one-route-per-branch-system-suggestions',
                '每个分支单选一条路径；系统建议供参考，可以选择其他出口。',
              )
            }}
          </p>
          <UiInput
            v-model:value="manualReasons[branch.branchNodeKey]"
            :aria-label="
              workflowLanguage.t(
                'platform.screen.WorkflowRecordPanel.route-selection-reason-value',
                '路径选择原因：{value1}',
                { value1: branch.branchTitle ?? branch.branchNodeKey },
              )
            "
            :placeholder="
              branch.requireManualSelectionReason
                ? workflowLanguage.t(
                    'platform.screen.WorkflowRecordPanel.route-selection-reason-required',
                    '路径选择原因（必填）',
                  )
                : workflowLanguage.t(
                    'platform.screen.WorkflowRecordPanel.route-selection-reason-optional',
                    '路径选择原因（选填）',
                  )
            "
            :disabled="busy || preparing"
          />
        </fieldset>
        <template v-if="activeAction.actionCode === 'submitApproval'">
          <UiButton :loading="preparing" @click="refreshPreview">{{
            workflowLanguage.t(
              'platform.screen.WorkflowRecordPanel.preview-selected-approval-routes',
              '预览所选审批路径',
            )
          }}</UiButton>
          <p v-if="previewReady">
            {{
              workflowLanguage.t(
                'platform.workflow-record.preview-processors',
                '流程：{title}。以下为提交后首批办理人，后续节点到达时解析人员。',
                {
                  title:
                    previewBundle?.definition?.definitionTitle ??
                    previewBundle?.instance.definitionTitle ??
                    status?.definition?.definitionTitle,
                },
              )
            }}
          </p>
          <ul v-if="previewReady">
            <li v-for="task in previewTasks" :key="task.id">
              {{
                previewBundle?.nodes.find(
                  (node) => node.id === task.nodeInstanceId || node.nodeKey === task.nodeKey,
                )?.nodeTitle ?? task.nodeKey
              }}
              ·
              {{
                task.assigneeTitle ??
                workflowLanguage.t(
                  'platform.screen.WorkflowRecordPanel.determined-when-the-task-is-created',
                  '待任务创建时确定',
                )
              }}<span v-if="task.originalAssigneeTitle && task.originalAssigneeTitle !== task.assigneeTitle">
                {{
                  workflowLanguage.t('platform.workflow-record.delegated-by', '· 受 {person} 委托', {
                    person: task.originalAssigneeTitle,
                  })
                }}</span
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
          <UiButton @click="inspectTask(activeAction.taskId!)">{{
            workflowLanguage.t(
              'platform.screen.WorkflowRecordPanel.check-task-completion-requirements',
              '检查任务完成条件',
            )
          }}</UiButton>
          <template v-if="taskProcess"
            ><p>
              {{
                taskProcess.evaluation.passed
                  ? workflowLanguage.t(
                      'platform.screen.WorkflowRecordPanel.business-conditions-are-met',
                      '当前业务条件已满足',
                    )
                  : displayTaskFailure
              }}
            </p>
            <ul>
              <li v-for="check in displayTaskChecks" :key="check.checkKey">
                {{ check.checkTitle }} ·
                {{
                  check.passed
                    ? workflowLanguage.t('platform.workflow-record.check-passed', '通过')
                    : (check.failureMessage ??
                      workflowLanguage.t('platform.screen.WorkflowRecordPanel.not-completed', '尚未完成'))
                }}
              </li>
            </ul>
            <template v-for="guide in displayTaskGuides" :key="guide.guideKey"
              ><UiButton
                v-if="
                  guide.guideKind === 'open_form' ||
                  (guide.guideKind === 'execute_action' && guide.targetActionCode === 'update')
                "
                @click="openBusinessForm(guide.guideKey)"
                >{{
                  guide.title ??
                  workflowLanguage.t('platform.workflow-record.fill-complete', '填写业务数据并完成任务')
                }}</UiButton
              ><UiButton
                v-else-if="guide.guideKind === 'execute_action'"
                :disabled="busy || preparing || !manualChoicesReady"
                @click="executeBusinessAction(guide.guideKey)"
                >{{
                  guide.title ??
                  workflowLanguage.t(
                    'platform.workflow-task-editor.execute-complete',
                    '执行业务动作并完成任务',
                  )
                }}</UiButton
              >
              <UiButton
                v-else-if="guide.guideKind === 'open_list' || guide.guideKind === 'focus_field'"
                :disabled="busy"
                @click="openReadGuide(guide)"
                >{{
                  guide.title ??
                  workflowLanguage.t('platform.screen.WorkflowRecordPanel.view-task-guidance', '查看办理指引')
                }}</UiButton
              >
              <p v-else>{{ guideInstruction(guide) }}</p></template
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
          >{{
            workflowLanguage.t(
              'platform.screen.WorkflowRecordPanel.save-business-data-and-complete-task',
              '保存业务并完成任务',
            )
          }}</UiButton
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
          >{{
            workflowLanguage.t('platform.screen.WorkflowRecordPanel.confirm-action', '确认{action}', {
              action: actionTitle(activeAction),
            })
          }}</UiButton
        >
        <UiButton :disabled="busy || preparing" @click="cancelAction">{{
          workflowLanguage.t('platform.screen.WorkflowRecordPanel.cancel-operation', '取消操作')
        }}</UiButton>
      </template>
    </RecordDetailDrawer>
    <div v-if="showHistory" class="workflow-history">
      <h4>{{ workflowLanguage.t('platform.screen.WorkflowRecordPanel.task-history', '任务记录') }}</h4>
      <RecordQueryListSurface
        :header-visible="false"
        :fill-height="false"
        :pageable="false"
        :columns="[
          {
            key: 'nodeTitle',
            title: workflowLanguage.t('platform.screen.WorkflowAdministrationView.node', '节点'),
            dataIndex: 'nodeTitle',
          },
          {
            key: 'kind',
            title: workflowLanguage.t('platform.screen.WorkflowRecordPanel.type', '类型'),
            dataIndex: 'kind',
          },
          {
            key: 'processor',
            title: workflowLanguage.t('platform.screen.WorkflowRecordPanel.processor', '处理人'),
            dataIndex: 'processor',
          },
          {
            key: 'status',
            title: workflowLanguage.t('platform.screen.WorkflowWorkbenchView.status', '状态'),
            dataIndex: 'status',
          },
        ]"
        :rows="
          tasks.map((task) => ({
            ...task,
            nodeTitle: bundle?.nodes.find((node) => node.id === task.nodeInstanceId)?.nodeTitle ?? '—',
            kind: workflowTitle(task.taskKind),
            processor:
              (task.actualProcessUserTitle ?? task.assigneeTitle ?? '—') +
              (task.processedByDelegation
                ? workflowLanguage.t(
                    'platform.screen.WorkflowRecordPanel.processed-by-delegation',
                    ' · 委托办理',
                  )
                : ''),
            status: workflowTitle(task.taskStatus),
          }))
        "
      />
      <WorkflowTimeline
        v-model:technical="showTechnicalHistory"
        :events="events"
        :tasks="tasks"
        :nodes="bundle?.nodes ?? []"
        :context="context"
        :instance-id="selectedHistory ?? bundle?.instance?.id"
        :history="Boolean(selectedHistory)"
      />
    </div>
    <details v-if="bundle" class="workflow-map">
      <summary>
        {{
          workflowLanguage.t(
            'platform.screen.WorkflowRecordPanel.view-workflow-and-route-decisions',
            '查看流程图与路径判定',
          )
        }}
      </summary>
      <WorkflowDiagram
        presentation="detail"
        :key="bundle.instance.id"
        :nodes="bundle.nodes"
        :routes="bundle.routes"
        :layout-json="bundle.layoutJson"
        :semantic-json="bundle.semanticJson"
      />
      <details v-if="decidedBranchRoutes.length">
        <summary>
          {{
            workflowLanguage.t('platform.screen.WorkflowRecordPanel.route-decision-history', '路径判定记录')
          }}
        </summary>
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
