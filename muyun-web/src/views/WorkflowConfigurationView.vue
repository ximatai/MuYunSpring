<script setup lang="ts">
import {
  AppError,
  normalizeError,
  userFacingErrorMessage,
  useLanguageContext,
  createWorkflowDefinitionClient,
} from '@muyun/web-core';
import {
  workflowParticipantRules,
  hasWorkflowParticipants,
} from '../platform-components/workflowParticipantPolicy';
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue';
import { useWorkflowConfigurationSession } from './useWorkflowConfigurationSession';
import { useWorkflowConfigurationAssistant } from './useWorkflowConfigurationAssistant';
import { copyWorkflowFragment, pasteWorkflowFragment } from '../platform-components/workflowDesignClipboard';
import { usePageDataChange } from '../platform-admin-runtime/pageRealtime';

import type {
  WorkflowDesign,
  WorkflowConfigurationCatalog,
  WorkflowDefinition,
  WorkflowVersion,
  WorkflowNode,
  WorkflowRoute,
} from '@muyun/web-contracts';
import {
  WorkflowParticipantEditor,
  WorkflowBusinessTaskEditor,
  WorkflowDesignProperties,
  WorkflowDiagram,
  presentPlatformError,
  workflowTitle as localizedWorkflowTitle,
  RecordDetailDrawer,
  RecordAdaptiveDetailPanel,
  useWorkspaceDetailSurface,
  MANAGEMENT_WORKSPACE_LAYOUT,
  RecordDetailPanel,
  AdaptiveHeaderActionBar,
} from '@muyun/platform-components';
import {
  UiButton,
  UiSelect,
  UiDropdown,
  UiTooltip,
  UiError,
  UiSpin,
  confirmAction,
  showSuccessMessage,
  showInfoMessage,
  uiDrawerWidths,
} from '@muyun/vue-ui-antdv';
import { useWorkspaceViewUnsavedState } from '@muyun/platform-workbench';
import {
  createWorkflowDesign,
  createWorkflowApprovalNode,
  insertWorkflowNode,
  removeWorkflowNode,
  insertWorkflowBranch,
  appendWorkflowBranchPath,
  removeWorkflowBranch,
} from '../platform-components/workflowDesignEditing';

import { useWorkflowConfigurationContext } from './useWorkflowConfigurationContext';

const workflowLanguage = useLanguageContext();
function workflowTitle(value?: string) {
  return localizedWorkflowTitle(value, workflowLanguage);
}
defineOptions({ name: 'WorkflowConfigurationView' });
const props = defineProps<{ moduleAlias: string; moduleTitle?: string; definitionId: string }>();
const { context, businessRuntime, businessLoading, fields, conditionTools } = useWorkflowConfigurationContext(
  () => props.moduleAlias,
);
let client = createWorkflowDefinitionClient(context.http, props.moduleAlias);
const catalog = ref<WorkflowConfigurationCatalog>({
  tasks: [],
  queries: [],
  generations: [],
  associations: [],
});
const actionOptions = computed(() =>
  (businessRuntime.value?.actions ?? []).map((action) => ({
    value: action.actionCode,
    label: action.title ?? workflowTitle(action.actionCode),
  })),
);
const minimumCanvasWidth = 720;
const designHost = ref<HTMLElement>();
const narrowProperties = useWorkspaceDetailSurface(
  designHost,
  () => minimumCanvasWidth + uiDrawerWidths.compact + MANAGEMENT_WORKSPACE_LAYOUT.columnGap,
);
const propertyOpen = ref(false),
  publishReviewOpen = ref(false);
const configurationActions = computed(() => [
  {
    key: 'save',
    title: workflowLanguage.t('platform.screen.WorkflowConfigurationView.save-draft', '保存草稿'),
    level: 'primary' as const,
    disabled: !editable.value || !dirty.value,
  },
  {
    key: 'read-current',
    title: workflowLanguage.t('platform.workflow-config.read-current', '读取当前版本'),
    level: 'secondary' as const,
    disabled: !selected.value || busy.value || (!dirty.value && !recoveryRequired.value),
  },
  {
    key: 'validate',
    title: workflowLanguage.t('platform.screen.WorkflowConfigurationView.validate-workflow', '校验流程'),
    level: 'secondary' as const,
    disabled: !editable.value,
  },
  {
    key: 'publish',
    title:
      version.value?.publishStatus === 'published'
        ? workflowLanguage.t('platform.screen.WorkflowConfigurationView.enable-published', '启用已发布版本')
        : workflowLanguage.t('platform.screen.WorkflowConfigurationView.publish-workflow', '发布流程'),
    disabled: !version.value || busy.value || recoveryRequired.value,
  },
  {
    key: 'upgrade',
    title: workflowLanguage.t('platform.screen.WorkflowConfigurationView.create-version', '创建新版本'),
    level: 'secondary' as const,
    disabled: !selected.value || busy.value || dirty.value || recoveryRequired.value,
  },
]);
const insertionActions = computed(() =>
  node.value?.nodeType === 'branch'
    ? [
        {
          key: 'path',
          title: workflowLanguage.t(
            'platform.screen.WorkflowDesignProperties.add-branch-route',
            '增加分支路径',
          ),
          disabled: !editable.value,
        },
      ]
    : [
        {
          key: 'node',
          title: workflowLanguage.t(
            'platform.screen.WorkflowConfigurationView.insert-approval-node',
            '插入节点',
          ),
          disabled: !editable.value || !insertionRoute(),
        },
        {
          key: 'branch',
          title: workflowLanguage.t(
            'platform.screen.WorkflowConfigurationView.insert-branch-and-convergence',
            '添加分支',
          ),
          disabled: !editable.value || !insertionRoute(),
        },
      ],
);
function insertElement(key: string) {
  if (key === 'node') addNode();
  else if (key === 'branch') addBranch();
  else if (key === 'path') appendBranchPath();
}
function configurationAction(key: string) {
  if (key === 'save') void save();
  else if (key === 'read-current') void readCurrentSave();
  else if (key === 'validate') void validate();
  else if (key === 'publish') publishReviewOpen.value = true;
  else if (key === 'upgrade') void upgrade();
}
function participantSummary(text?: string) {
  try {
    const rules = workflowParticipantRules(text);
    return (
      rules
        .map(
          (rule: { type: string; ids?: string[]; fieldName?: string }) =>
            (({
              INITIATOR_SELF: workflowLanguage.t('platform.workflow-participant.initiator', '提交人本人'),
              USER: workflowLanguage.t('platform.workflow-participant.named-accounts', '指定账号'),
              ROLE: workflowLanguage.t('platform.workflow-participant.role-members', '角色成员'),
              DEPT: workflowLanguage.t('platform.workflow-participant.department-members', '部门成员'),
              ORG: workflowLanguage.t('platform.workflow-participant.organization-members', '组织成员'),
              FIELD: workflowLanguage.t('platform.workflow-participant.participant-field', '业务人员字段'),
              RELATIVE: workflowLanguage.t('platform.workflow-participant.relative-participants', '相对人员'),
            })[rule.type] ?? rule.type) +
            (rule.ids?.length
              ? workflowLanguage.t('platform.workflow-config.target-count', '（{count} 个目标）', {
                  count: rule.ids.length,
                })
              : rule.fieldName
                ? `（${fields.value.find((field) => field.name === rule.fieldName)?.label ?? rule.fieldName}）`
                : ''),
        )
        .join('、') || workflowLanguage.t('platform.workflow-config.empty-participants', '尚未配置参与人')
    );
  } catch {
    return workflowLanguage.t('platform.workflow-config.invalid-participants', '人员配置格式无效');
  }
}
const loadingDefinition = ref(false);
const definitionLoadError = ref<() => string>();
const versions = ref<WorkflowVersion[]>([]);
const selected = ref<WorkflowDefinition>(),
  version = ref<WorkflowVersion>();
const session = useWorkflowConfigurationSession({
  moduleAlias: () => props.moduleAlias,
  definitionId: () => props.definitionId,
});
const { recoveryRequired, submissionStatus } = session;
const recoverySelection = ref('');
const recovery = ref<{ version: WorkflowVersion; design: WorkflowDesign }>();
const { design, history } = session;
const selectedNodeKey = ref(''),
  selectedRouteKey = ref('');
const busy = ref(false);
const dirty = history.dirty;
const selectedNodeKeys = ref<string[]>([]);
const frozen = computed(() => ['published', 'archived'].includes(version.value?.publishStatus ?? ''));
const editable = computed(
  () => version.value?.publishStatus === 'draft' && !busy.value && !recoveryRequired.value,
);
const loadedScope = ref('');
const interactionRevision = ref(0);
const assistant: ReturnType<typeof useWorkflowConfigurationAssistant> = useWorkflowConfigurationAssistant({
  session,
  http: context.http,
  definitionId: () => props.definitionId,
  interactionRevision: () => String(interactionRevision.value),
  revision: () =>
    JSON.stringify([
      session.scope.value,
      loadedScope.value,
      version.value,
      selected.value,
      design.value,
      fields.value,
      selectedNodeKeys.value,
      selectedRouteKey.value,
      busy.value,
      submissionStatus.value,
      loadingDefinition.value,
      businessLoading.value,
      assistant.readable.value,
      assistant.writable.value,
      session.active.value,
    ]),
  adapter: {
    language: workflowLanguage,
    validateCondition: (expression) => client.validateCondition(expression),
    state: () => ({
      ready:
        session.active.value &&
        assistant.readable.value &&
        loadedScope.value === session.scope.value &&
        Boolean(selected.value && version.value && businessRuntime.value) &&
        !loadingDefinition.value &&
        !definitionLoadError.value &&
        !busy.value,
      editable: editable.value && assistant.writable.value,
      busy: busy.value || loadingDefinition.value || businessLoading.value,
      moduleAlias: props.moduleAlias,
      definition: selected.value,
      version: version.value,
      design: design.value,
      fields: fields.value,
      selectedNodeKeys: selectedNodeKeys.value,
      selectedRouteKey: selectedRouteKey.value,
      dirty: dirty.value,
      submissionStatus: submissionStatus.value,
    }),
    prepare: (batch) => {
      const expected = session.scope.value;
      return session.prepare(
        batch,
        fields.value,
        () =>
          session.scope.value === expected &&
          session.active.value &&
          assistant.readable.value &&
          assistant.writable.value &&
          editable.value &&
          loadedScope.value === expected &&
          !loadingDefinition.value &&
          !definitionLoadError.value,
      );
    },
  },
});
const node = computed(() => design.value.nodes.find((item) => item.nodeKey === selectedNodeKey.value));
const route = computed(() => design.value.links.find((item) => item.routeKey === selectedRouteKey.value));
const deleting = ref(false);
const canDeleteSelection = computed(
  () =>
    editable.value &&
    !deleting.value &&
    selectedNodeKeys.value.length === 1 &&
    node.value != null &&
    !['start', 'end', 'converge'].includes(node.value.nodeType),
);
async function deleteSelection() {
  if (!canDeleteSelection.value) return;
  deleting.value = true;
  try {
    if (node.value?.nodeType === 'branch') await deleteBranch();
    else removeNode();
  } finally {
    deleting.value = false;
  }
}
function branchConvergenceSummary(branch: WorkflowNode) {
  const convergence = design.value.nodes.find((item) => item.nodeKey === branch.convergeNodeKey);
  const labels: Record<string, string> = {
    all: workflowLanguage.t('platform.workflow-config.all-arrive', '全部到达'),
    any: workflowLanguage.t('platform.workflow-config.any-arrive', '任一到达'),
    ratio: workflowLanguage.t('platform.workflow-config.ratio-arrive', '比例到达'),
  };
  const mode = convergence?.convergeMode ?? 'all';
  return `${labels[mode]}${mode === 'ratio' ? ` ${convergence?.convergeRatio ?? 100}%` : ''}`;
}
useWorkspaceViewUnsavedState(
  () => workflowLanguage.t('platform.workflow-config.configuration', '流程配置'),
  () => dirty.value,
  () => busy.value,
);
onMounted(() => {
  void reload();
});
let metadataRevision = 0;
let metadataRefreshPending = false;
let disposed = false;
let actionGeneration = 0;
let scopeGeneration = 0;
let persistencePending = false;
function persistenceCheckpoint() {
  const generation = scopeGeneration;
  return () => !disposed && scopeGeneration === generation;
}
function actionCheckpoint() {
  const generation = actionGeneration;
  const scope = session.scope.value;
  return () =>
    !disposed && session.active.value && actionGeneration === generation && session.scope.value === scope;
}
function editCheckpoint() {
  const current = actionCheckpoint();
  const unchanged = history.checkpoint();
  return () => current() && unchanged();
}
watch(
  session.scope,
  () => {
    actionGeneration++;
    scopeGeneration++;
    persistencePending = false;
    submissionStatus.value = 'idle';
    recovery.value = undefined;
    metadataRevision++;
    loadedScope.value = '';
    selected.value = undefined;
    version.value = undefined;
    versions.value = [];
    history.reset({ nodes: [], links: [] });
    selectedNodeKeys.value = [];
    selectedNodeKey.value = selectedRouteKey.value = '';
    propertyOpen.value = publishReviewOpen.value = false;
    busy.value = false;
    client = createWorkflowDefinitionClient(context.http, props.moduleAlias);
    if (session.active.value) void reload();
  },
  { flush: 'sync' },
);
onDeactivated(() => {
  actionGeneration++;
  metadataRevision++;
  if (!persistencePending) busy.value = false;
  loadingDefinition.value = false;
});
onBeforeUnmount(() => {
  disposed = true;
  actionGeneration++;
  metadataRevision++;
});
async function refreshDefinition() {
  if (disposed || !selected.value) return;
  if (busy.value) {
    metadataRefreshPending = true;
    return;
  }
  const revision = ++metadataRevision;
  const current = actionCheckpoint();
  try {
    const definition = await client.view(props.definitionId);
    if (current() && revision === metadataRevision) selected.value = definition;
  } catch (cause) {
    if (current() && revision === metadataRevision)
      presentPlatformError(cause, { source: 'workflow-design', phase: 'load' });
  }
}
onActivated(() => {
  if (!selected.value) void reload();
  else void refreshDefinition();
});
usePageDataChange({
  moduleAlias: 'platform.workflow.definition',
  recordId: () => props.definitionId,
  handler: refreshDefinition,
});
async function run(operation: (current: () => boolean) => Promise<void>, persistent = false) {
  if (busy.value) return;
  metadataRevision++;
  busy.value = true;
  const current = persistent ? persistenceCheckpoint() : actionCheckpoint();
  persistencePending = persistent;
  try {
    await operation(current);
  } catch (cause) {
    if (current()) presentPlatformError(cause, { source: 'workflow-design', phase: 'action' });
  } finally {
    if (current()) {
      busy.value = false;
      persistencePending = false;
    }
    if (current() && metadataRefreshPending) {
      metadataRefreshPending = false;
      await refreshDefinition();
    }
  }
}
async function reload() {
  if (busy.value || dirty.value) return;
  loadingDefinition.value = true;
  definitionLoadError.value = undefined;
  const currentLoad = actionCheckpoint();
  const currentClient = client;
  const definitionId = props.definitionId;
  await run(async (current) => {
    try {
      const definition = await currentClient.view(definitionId);
      if (!current()) return;
      const nextCatalog = await currentClient.catalog(definition.id);
      if (!current()) return;
      const nextVersions = (await currentClient.versions(definition.id)).sort(
        (a, b) => b.versionNo - a.versionNo,
      );
      if (!current()) return;
      const latest = nextVersions[0] ?? (await currentClient.upgrade(definition.id));
      if (!current()) return;
      if (!nextVersions.length) nextVersions.push(latest);
      let nextDesign = await currentClient.design(definition.id, latest.id);
      if (!current()) return;
      const initialize = latest.publishStatus === 'draft' && !nextDesign.nodes.length;
      if (initialize) nextDesign = createWorkflowDesign(definition.approvalEnabled, workflowLanguage);
      selected.value = definition;
      catalog.value = nextCatalog;
      versions.value = nextVersions;
      version.value = latest;
      history.reset(nextDesign, initialize ? { nodes: [], links: [] } : nextDesign);
      loadedScope.value = session.scope.value;
      selectedNodeKeys.value = [];
      selectedNodeKey.value = '';
      selectedRouteKey.value = '';
      propertyOpen.value = false;
    } catch (cause) {
      if (current())
        definitionLoadError.value = () => userFacingErrorMessage(normalizeError(cause), workflowLanguage);
    }
  });
  if (currentLoad()) loadingDefinition.value = false;
}
async function loadVersion(next: WorkflowVersion, current = actionCheckpoint()) {
  const nextDesign = await client.design(selected.value!.id, next.id);
  if (!current()) return;
  version.value = next;
  history.reset(nextDesign);
  selectedNodeKeys.value = [];
  selectedNodeKey.value = design.value.nodes[0]?.nodeKey ?? '';
  selectedRouteKey.value = '';
  propertyOpen.value = false;
}
async function switchVersion(id: string) {
  if (dirty.value || recoveryRequired.value || busy.value) return;
  const next = versions.value.find((item) => item.id === id);
  if (next) {
    interactionRevision.value++;
    await run(() => loadVersion(next));
  }
}
async function save() {
  if (!editable.value || !version.value || !selected.value) return;
  await run(async (current) => {
    const saved = await client
      .saveDesign(selected.value!.id, version.value!.id, version.value!.version, design.value)
      .catch((cause: unknown) => {
        if (current() && cause instanceof AppError && cause.status === 409)
          submissionStatus.value = 'conflict';
        if (
          current() &&
          !(cause instanceof AppError && [400, 401, 403, 404, 409, 422].includes(cause.status ?? 0))
        )
          submissionStatus.value = 'unknown';
        throw cause;
      });
    if (!current()) return;
    version.value = saved;
    versions.value = versions.value.map((item) => (item.id === version.value!.id ? version.value! : item));
    history.markSaved();
    showSuccessMessage(workflowLanguage.t('platform.workflow-config.draft-saved', '流程草稿已保存'));
  }, true);
}
async function readCurrentSave() {
  if ((!recoveryRequired.value && !dirty.value) || !selected.value || !version.value) return;
  const definitionId = selected.value.id;
  const versionId = version.value.id;
  if (busy.value) return;
  recovery.value = undefined;
  if (submissionStatus.value === 'idle') submissionStatus.value = 'review';
  await run(async (current) => {
    const latestVersions = await client.versions(definitionId);
    if (!current()) return;
    const latest = latestVersions.find((item) => item.id === versionId);
    if (!latest)
      throw new Error(workflowLanguage.t('platform.workflow-config.version-missing', '当前流程版本已不存在'));
    const latestDesign = await client.design(definitionId, versionId);
    if (!current()) return;
    versions.value = latestVersions.sort((a, b) => b.versionNo - a.versionNo);
    recovery.value = { version: latest, design: latestDesign };
    recoverySelection.value = `node:${latestDesign.nodes.find((node) => !['start', 'end'].includes(node.nodeType))?.nodeKey ?? latestDesign.nodes[0]?.nodeKey ?? ''}`;
  });
}
function resolveSave(keepDraft: boolean) {
  if (busy.value || !recovery.value || !recoveryRequired.value) return;
  if (keepDraft && recovery.value.version.publishStatus !== 'draft') return;
  const current = recovery.value;
  version.value = current.version;
  history.reset(keepDraft ? design.value : current.design, current.design);
  recovery.value = undefined;
  submissionStatus.value = 'idle';
  interactionRevision.value++;
}
async function validate() {
  if (recoveryRequired.value) return;
  const current = actionCheckpoint();
  if (dirty.value) await save();
  if (!current() || dirty.value || !version.value) return;
  await run(async (isCurrent) => {
    await client.validate(selected.value!.id, version.value!.id);
    if (!isCurrent()) return;
    showSuccessMessage(workflowLanguage.t('platform.workflow-config.validated', '流程发布校验通过'));
  });
}
async function publish() {
  if (recoveryRequired.value) return;
  const current = actionCheckpoint();
  if (
    design.value.nodes.some(
      (node) =>
        ['approval', 'task'].includes(node.nodeType) && !hasWorkflowParticipants(node.participantPolicyText),
    )
  ) {
    presentPlatformError(
      new Error(
        workflowLanguage.t(
          'platform.workflow-config.participants-required',
          '请为每个审批或业务任务节点明确配置参与人',
        ),
      ),
      {
        source: 'workflow-design',
        phase: 'validation',
      },
    );
    return;
  }
  if (dirty.value) await save();
  if (!current() || dirty.value || !selected.value || !version.value) return;
  await run(async (isCurrent) => {
    const published = await client.publish(
      selected.value!.id,
      version.value!.id,
      selected.value!.version,
      version.value!.version,
    );
    if (!isCurrent()) return;
    version.value = published;
    versions.value = versions.value.map((item) => (item.id === version.value!.id ? version.value! : item));
    history.markSaved();
    publishReviewOpen.value = false;
    showSuccessMessage(
      workflowLanguage.t('platform.workflow-config.published', '流程已发布，后续新申请使用该版本'),
    );
    // Publication is already acknowledged. A failed metadata refresh is a read failure,
    // and must not leave the successful command presented as an unsubmitted review.
    try {
      const definition = await client.view(selected.value!.id);
      if (isCurrent()) selected.value = definition;
    } catch (cause) {
      if (isCurrent()) presentPlatformError(cause, { source: 'workflow-design', phase: 'load' });
    }
  }, true);
}
async function upgrade() {
  if (recoveryRequired.value || dirty.value) return;
  if (!selected.value) return;
  await run(async (current) => {
    const next = await client.upgrade(selected.value!.id);
    if (!current()) return;
    versions.value = [next, ...versions.value.filter((item) => item.id !== next.id)];
    await loadVersion(next, current);
    if (!current()) return;
    if (!design.value.nodes.length) {
      design.value = createWorkflowDesign(selected.value!.approvalEnabled, workflowLanguage);
      selectNode('approval');
    }
  }, true);
}
function selectNode(key: string) {
  if (!design.value.nodes.some((item) => item.nodeKey === key)) key = '';
  selectedNodeKeys.value = key ? [key] : [];
  selectedNodeKey.value = key;
  selectedRouteKey.value = '';
  propertyOpen.value = Boolean(key);
}
function selectRoute(key: string) {
  if (!design.value.links.some((item) => item.routeKey === key)) key = '';
  selectedNodeKeys.value = [];
  selectedRouteKey.value = key;
  selectedNodeKey.value = '';
  propertyOpen.value = Boolean(key);
}
function updateLayout(layoutJson: string) {
  if (!editable.value || design.value.layoutJson === layoutJson) return;
  design.value = { ...design.value, layoutJson };
}
function updateNode(patch: Partial<WorkflowNode>) {
  if (node.value && editable.value) {
    session.updateNode(selectedNodeKey.value, patch);
  }
}
function updateRoute(patch: Partial<WorkflowRoute>) {
  if (route.value && editable.value) {
    session.updateRoute(route.value.routeKey, patch);
  }
}
function insertionRoute() {
  const outgoing = design.value.links.filter((item) => item.sourceNodeKey === selectedNodeKey.value);
  return selectedRouteKey.value || (outgoing.length === 1 ? outgoing[0]?.routeKey : undefined) || '';
}
function addBranch() {
  if (!editable.value) return;
  const key = `branch_${Date.now().toString(36)}`;
  try {
    design.value = insertWorkflowBranch(design.value, insertionRoute(), key, workflowLanguage);
    selectNode(key);
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-design', phase: 'validation' });
  }
}
function appendBranchPath() {
  if (!editable.value) return;
  try {
    design.value = appendWorkflowBranchPath(
      design.value,
      selectedNodeKey.value,
      `path_${Date.now().toString(36)}`,
      workflowLanguage,
    );
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-design', phase: 'validation' });
  }
}
async function deleteBranch() {
  if (!editable.value) return;
  try {
    const isCurrent = editCheckpoint();
    const next = removeWorkflowBranch(design.value, selectedNodeKey.value, workflowLanguage);
    if (
      !(await confirmAction(
        {
          title: workflowLanguage.t('platform.workflow-config.delete-branch-confirm', '删除整个分支？'),
          content: workflowLanguage.t(
            'platform.workflow-config.delete-branch-detail',
            '将删除分支、配对汇聚及其中 {count} 个节点，并连接前后路径。',
            { count: design.value.nodes.length - next.nodes.length - 2 },
          ),
          danger: true,
          okText: workflowLanguage.t('platform.workflow-config.delete-branch', '删除分支'),
        },
        workflowLanguage,
      ))
    )
      return;
    if (!mayApplyAsyncEdit(isCurrent)) return;
    design.value = next;
    selectedNodeKey.value = '';
    propertyOpen.value = false;
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-design', phase: 'validation' });
  }
}
function addNode() {
  if (!editable.value) return;
  const outgoing = design.value.links.filter((item) => item.sourceNodeKey === selectedNodeKey.value);
  const route = selectedRouteKey.value || (outgoing.length === 1 ? outgoing[0]?.routeKey : undefined);
  const key = `node_${Date.now().toString(36)}`;
  try {
    design.value = insertWorkflowNode(
      design.value,
      route ?? '',
      createWorkflowApprovalNode(
        key,
        workflowLanguage.t('platform.workflow-config.new-approval-node', '新审批节点'),
      ),
      workflowLanguage,
    );
    selectNode(key);
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-design', phase: 'validation' });
  }
}
function removeNode() {
  if (!editable.value) return;
  try {
    design.value = removeWorkflowNode(design.value, selectedNodeKey.value, workflowLanguage);
    selectedNodeKey.value = '';
    propertyOpen.value = false;
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-design', phase: 'validation' });
  }
}

function selectNodes(keys: string[]) {
  const available = new Set(design.value.nodes.map((item) => item.nodeKey));
  keys = [...new Set(keys)].filter((key) => available.has(key));
  selectedNodeKeys.value = keys;
  selectedNodeKey.value = keys.length === 1 ? keys[0] : '';
  selectedRouteKey.value = '';
  propertyOpen.value = false;
}
watch(
  () => JSON.stringify([design.value, selectedNodeKeys.value, selectedRouteKey.value]),
  () => {
    if (!busy.value && !loadingDefinition.value) interactionRevision.value++;
  },
  { flush: 'sync' },
);
watch(
  () =>
    [
      design.value.nodes.map((item) => item.nodeKey),
      design.value.links.map((item) => item.routeKey),
    ] as const,
  ([nodes, routes]) => {
    const availableNodes = new Set(nodes);
    selectedNodeKeys.value = selectedNodeKeys.value.filter((key) => availableNodes.has(key));
    if (selectedNodeKey.value && !availableNodes.has(selectedNodeKey.value)) {
      selectedNodeKey.value = '';
      propertyOpen.value = false;
    }
    if (selectedRouteKey.value && !routes.includes(selectedRouteKey.value)) {
      selectedRouteKey.value = '';
      propertyOpen.value = false;
    }
  },
  { flush: 'sync' },
);
function mayApplyAsyncEdit(isCurrent: () => boolean) {
  if (disposed || !session.active.value) return false;
  if (editable.value && isCurrent()) return true;
  showInfoMessage(
    workflowLanguage.t('platform.workflow-config.stale-edit', '流程已变更，本次操作未应用，请重新操作。'),
  );
  return false;
}
function undo() {
  if (editable.value) history.undo();
}
function redo() {
  if (editable.value) history.redo();
}
function copySelection(event: ClipboardEvent) {
  if (!event.clipboardData) return;
  try {
    const text = copyWorkflowFragment(design.value, selectedNodeKeys.value);
    event.clipboardData.setData('text/plain', text);
    event.preventDefault();
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-design', phase: 'action' });
  }
}
function pasteTarget() {
  const join = node.value?.nodeType === 'branch' ? node.value.convergeNodeKey : undefined;
  return (
    insertionRoute() ||
    (join ? design.value.links.find((link) => link.sourceNodeKey === join)?.routeKey : undefined) ||
    design.value.links.find((link) =>
      design.value.nodes.some((item) => item.nodeKey === link.targetNodeKey && item.nodeType === 'end'),
    )?.routeKey ||
    ''
  );
}
function pasteText(text: string, target: string) {
  if (!editable.value || !text.includes('muyun.workflow.fragment')) return;
  const pasted = pasteWorkflowFragment(
    design.value,
    target,
    text,
    `copy_${crypto.randomUUID().replaceAll('-', '')}`,
  );
  design.value = pasted.design;
  if (pasted.reconfigureSelectorKeys.length) {
    const names = pasted.reconfigureSelectorKeys
      .map((key) => pasted.design.nodes.find((node) => node.nodeKey === key)?.title ?? key)
      .join('、');
    showInfoMessage(
      workflowLanguage.t(
        'platform.workflow-config.paste-reconfigure-selectors',
        '已粘贴，以下人工分支需要重新配置选择来源：{names}。',
        { names },
      ),
    );
    selectNode(pasted.reconfigureSelectorKeys[0]);
  } else selectNodes(pasted.keys);
}
function pasteSelection(event: ClipboardEvent) {
  if (!editable.value || !event.clipboardData) return;
  const text = event.clipboardData.getData('text/plain');
  if (!text.includes('muyun.workflow.fragment')) return;
  event.preventDefault();
  try {
    pasteText(text, pasteTarget());
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-design', phase: 'action' });
  }
}
async function copyShortcut() {
  try {
    if (!navigator.clipboard) throw new Error('当前浏览器不支持访问剪贴板');
    await navigator.clipboard.writeText(copyWorkflowFragment(design.value, selectedNodeKeys.value));
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-design', phase: 'action' });
  }
}
async function pasteShortcut() {
  if (!editable.value) return;
  const isCurrent = editCheckpoint();
  const target = pasteTarget();
  try {
    if (!navigator.clipboard) throw new Error('当前浏览器不支持访问剪贴板');
    const text = await navigator.clipboard.readText();
    if (text.includes('muyun.workflow.fragment') && mayApplyAsyncEdit(isCurrent)) pasteText(text, target);
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-design', phase: 'action' });
  }
}
</script>
<template>
  <section class="workflow-configuration">
    <RecordDetailPanel
      :title="selected?.title ?? workflowLanguage.t('platform.workflow-config.configuration', '流程配置')"
    >
      <template #actions>
        <AdaptiveHeaderActionBar
          class="configuration-action-bar"
          :actions="configurationActions"
          @action="configurationAction($event.key)"
        />
      </template>
      <template v-if="selected" #status>
        <div class="configuration-status">
          <UiSelect
            class="configuration-version-selector"
            :value="version?.id"
            :options="
              versions.map((item) => ({
                value: item.id,
                label: workflowLanguage.t(
                  'platform.screen.WorkflowConfigurationView.version-value-value',
                  '版本 {value1} · {value2}',
                  { value1: item.versionNo, value2: workflowTitle(item.publishStatus) },
                ),
              }))
            "
            :disabled="busy || dirty || recoveryRequired"
            @update:value="switchVersion(String($event))"
          />
          <UiTooltip
            v-if="dirty"
            :title="
              workflowLanguage.t(
                'platform.screen.WorkflowConfigurationView.the-draft-has-unsaved-changes',
                '草稿有未保存的修改',
              )
            "
          >
            <span class="configuration-state configuration-state--dirty" role="status">{{
              workflowLanguage.t('platform.workflow-config.unsaved', '未保存')
            }}</span>
          </UiTooltip>
          <UiTooltip
            v-else-if="frozen"
            :title="
              workflowLanguage.t(
                'platform.screen.WorkflowConfigurationView.this-version-is-frozen-create-a-new',
                '此版本已冻结。修改流程请创建新版本；已有实例继续使用原版本。',
              )
            "
          >
            <span class="configuration-state">{{
              workflowLanguage.t('platform.workflow-config.readonly', '只读')
            }}</span>
          </UiTooltip>
        </div>
      </template>
      <div v-if="recoveryRequired" class="configuration-recovery" role="alert">
        <p>
          {{
            submissionStatus === 'review'
              ? workflowLanguage.t(
                  'platform.workflow-config.review-current',
                  '本地草稿已保留，请读取当前版本后核对。',
                )
              : workflowLanguage.t(
                  'platform.workflow-config.save-unknown',
                  '上次保存未能确认，本地草稿已保留。请读取当前版本后重新核对。',
                )
          }}
        </p>
        <UiButton :disabled="busy" @click="readCurrentSave">{{
          workflowLanguage.t('platform.workflow-config.read-current', '读取当前版本')
        }}</UiButton>
        <UiButton
          v-if="submissionStatus === 'review'"
          :disabled="busy"
          @click="
            submissionStatus = 'idle';
            recovery = undefined;
          "
          >{{ workflowLanguage.t('platform.workflow-config.cancel-review', '取消核对，继续编辑') }}</UiButton
        >
        <template v-if="recovery">
          <details>
            <summary>
              {{
                workflowLanguage.t('platform.workflow-config.view-current', '查看服务端当前流程与完整配置')
              }}
            </summary>
            <WorkflowDiagram
              :nodes="recovery.design.nodes"
              :routes="recovery.design.links"
              :layout-json="recovery.design.layoutJson"
              :editable="false"
              interactive
              @select="recoverySelection = `node:${$event}`"
              @select-route="recoverySelection = `route:${$event}`"
            />
            <UiSelect
              v-model:value="recoverySelection"
              :options="[
                ...recovery.design.nodes.map((node) => ({
                  value: `node:${node.nodeKey}`,
                  label: node.title ?? node.nodeKey,
                })),
                ...recovery.design.links.map((route) => ({
                  value: `route:${route.routeKey}`,
                  label: route.title ?? route.routeKey,
                })),
              ]"
            />
            <WorkflowDesignProperties
              :design="recovery.design"
              :node-key="recoverySelection.startsWith('node:') ? recoverySelection.slice(5) : ''"
              :route-key="recoverySelection.startsWith('route:') ? recoverySelection.slice(6) : ''"
              :editable="false"
              :condition-tools="conditionTools"
              :http="context.http"
              :module-alias="moduleAlias"
              :fields="fields"
              :catalog="catalog"
              :actions="actionOptions"
              @select-route="recoverySelection = `route:${$event}`"
            />
          </details>
          <p>
            {{
              workflowLanguage.t(
                'platform.workflow-config.current-read',
                '已读取当前版本；这不代表原保存请求的回执。采用当前版本将替换本地草稿，保留本地草稿则需重新核对后保存。',
              )
            }}
          </p>
          <UiButton :disabled="busy" @click="resolveSave(false)">{{
            workflowLanguage.t('platform.workflow-config.use-current', '采用当前版本')
          }}</UiButton>
          <UiButton
            :disabled="busy || recovery.version.publishStatus !== 'draft'"
            @click="resolveSave(true)"
            >{{
              workflowLanguage.t('platform.workflow-config.keep-draft', '保留本地草稿并重新核对')
            }}</UiButton
          >
        </template>
      </div>
      <UiSpin v-if="loadingDefinition" />
      <UiError v-if="definitionLoadError" :message="definitionLoadError()" />
      <UiButton v-if="definitionLoadError" @click="reload">{{
        workflowLanguage.t('platform.screen.CrudRecordListExplorer.reload', '重新加载')
      }}</UiButton>
      <template v-if="selected">
        <div
          ref="designHost"
          class="configuration-design"
          :style="{ gap: `${MANAGEMENT_WORKSPACE_LAYOUT.columnGap}px` }"
        >
          <WorkflowDiagram
            :key="version?.id"
            :nodes="design.nodes"
            :routes="design.links"
            :selected-node-key="selectedNodeKey"
            :selected-node-keys="selectedNodeKeys"
            :can-undo="history.canUndo.value"
            :can-redo="history.canRedo.value"
            @undo="undo"
            @redo="redo"
            @selection-change="selectNodes"
            @copy="copySelection"
            @copy-shortcut="copyShortcut"
            @paste-shortcut="pasteShortcut"
            @delete-selection="deleteSelection"
            @paste="pasteSelection"
            :selected-route-key="selectedRouteKey"
            :layout-json="design.layoutJson"
            :editable="editable"
            interactive
            presentation="designer"
            @select="selectNode"
            @select-route="selectRoute"
            @layout-change="updateLayout"
          >
            <template #actions>
              <UiDropdown :items="insertionActions" align="start" @select="insertElement">
                <UiButton icon-name="plus" :disabled="!editable">{{
                  workflowLanguage.t('platform.workflow-config.add-element', '添加节点 / 分支')
                }}</UiButton>
              </UiDropdown>
            </template>
            <template #navigation>
              <UiSelect
                class="configuration-route-locator"
                :value="selectedRouteKey || undefined"
                :placeholder="
                  workflowLanguage.t('platform.screen.WorkflowConfigurationView.select-link', '选择连线')
                "
                :options="
                  design.links.map((item) => ({
                    value: item.routeKey,
                    label: `${item.title ?? workflowLanguage.t('platform.workflow-config.route', '路径')}：${design.nodes.find((node) => node.nodeKey === item.sourceNodeKey)?.title} → ${design.nodes.find((node) => node.nodeKey === item.targetNodeKey)?.title}`,
                  }))
                "
                @update:value="selectRoute(String($event))"
              />
            </template>
          </WorkflowDiagram>
          <RecordAdaptiveDetailPanel
            :docked="!narrowProperties"
            width="compact"
            :open="propertyOpen"
            :title="
              node?.title
                ? workflowLanguage.t('platform.workflow-config.node-properties', '节点属性 · {title}', {
                    title: node.title,
                  })
                : workflowLanguage.t('platform.workflow-config.route-properties', '路径属性')
            "
            @close="propertyOpen = false"
          >
            <WorkflowDesignProperties
              :condition-tools="conditionTools"
              :design="design"
              :node-key="selectedNodeKey"
              :route-key="selectedRouteKey"
              :editable="editable"
              :http="context.http"
              :module-alias="moduleAlias"
              :fields="fields"
              :catalog="catalog"
              :actions="actionOptions"
              @update-node="updateNode"
              @update-route="updateRoute"
              @select-route="selectRoute"
              @append-branch-path="appendBranchPath"
            />
            <template #operation>
              <div class="configuration-property-operations">
                <UiButton
                  v-if="node?.nodeType === 'branch'"
                  danger
                  :disabled="!canDeleteSelection"
                  @click="deleteSelection"
                  >{{
                    workflowLanguage.t(
                      'platform.screen.WorkflowDesignProperties.delete-entire-branch',
                      '删除整个分支',
                    )
                  }}</UiButton
                >
                <UiButton v-else-if="node" danger :disabled="!canDeleteSelection" @click="deleteSelection">{{
                  workflowLanguage.t(
                    'platform.screen.WorkflowDesignProperties.delete-and-reconnect-adjacent-nodes',
                    '删除节点',
                  )
                }}</UiButton>
                <UiButton :disabled="busy" @click="propertyOpen = false">{{
                  workflowLanguage.t(
                    'platform.screen.WorkflowConfigurationView.finish-configuration',
                    '完成配置',
                  )
                }}</UiButton>
              </div>
            </template>
          </RecordAdaptiveDetailPanel>
        </div>
      </template>
    </RecordDetailPanel>
    <RecordDetailDrawer
      width="extraWide"
      :open="publishReviewOpen"
      :title="
        workflowLanguage.t(
          'platform.screen.WorkflowConfigurationView.confirm-workflow-publication',
          '确认发布流程',
        )
      "
      @close="publishReviewOpen = false"
    >
      <p>
        {{
          workflowLanguage.t(
            'platform.workflow-config.publication-frozen',
            '流程：{title} · 版本 {version}。发布后配置冻结；已有实例继续使用原版本。',
            { title: selected?.title, version: version?.versionNo },
          )
        }}
      </p>
      <p>
        {{ workflowLanguage.t('platform.workflow-config.matching-scope', '匹配范围') }}：{{
          selected?.organizationId
            ? workflowLanguage.t('platform.workflow-config.scoped-organizations', '指定组织及下级组织')
            : workflowLanguage.t('platform.workflow-config.all-organizations', '模块内全部组织')
        }}；{{
          selected?.defaultDefinition
            ? workflowLanguage.t('platform.workflow-config.default-workflow', '作为默认兜底流程')
            : workflowLanguage.t('platform.workflow-config.conditional-workflow', '按匹配条件选择')
        }}。
        {{
          selected?.matchExpression
            ? workflowLanguage.t('platform.workflow-config.matching-expression', '匹配条件：{expression}', {
                expression: selected.matchExpression,
              })
            : workflowLanguage.t('platform.workflow-config.no-matching-condition', '未设置额外条件')
        }}
      </p>
      <ul class="publish-nodes">
        <li
          v-for="item in design.nodes.filter((node) => ['approval', 'task'].includes(node.nodeType))"
          :key="item.nodeKey"
        >
          <strong>{{ item.title }}</strong> · {{ participantSummary(item.participantPolicyText) }}
          <details>
            <summary>
              {{
                workflowLanguage.t(
                  'platform.screen.WorkflowConfigurationView.view-complete-participant-rules',
                  '查看完整人员规则',
                )
              }}
            </summary>
            <WorkflowParticipantEditor
              :value="item.participantPolicyText"
              :http="context.http"
              :fields="fields"
              disabled
            />
          </details>
          <details v-if="item.nodeType === 'task'">
            <summary>
              {{
                workflowLanguage.t(
                  'platform.screen.WorkflowConfigurationView.view-business-task-checks-and-instructions',
                  '查看业务任务检查与办理指引',
                )
              }}
            </summary>
            <p v-if="item.taskDefinitionId">
              {{ workflowLanguage.t('platform.workflow-config.referenced-task', '引用任务') }}：{{
                catalog.tasks.find((task) => task.id === item.taskDefinitionId)?.title ??
                item.taskDefinitionId
              }}
            </p>
            <WorkflowBusinessTaskEditor
              :condition-tools="conditionTools"
              :value="item.nodeConfigText"
              :module-alias="moduleAlias"
              :fields="fields"
              :catalog="catalog"
              :actions="actionOptions"
              :associations="catalog.associations.map((item) => ({ value: item.id, label: item.title }))"
              disabled
            />
          </details>
          <p v-if="item.participantPolicyText?.includes('INITIATOR_SELF')">
            {{
              workflowLanguage.t(
                'platform.screen.WorkflowConfigurationView.the-submitter-processes-this-node-confirm-that',
                '此节点由提交人本人办理，请确认符合业务要求。',
              )
            }}
          </p>
        </li>
      </ul>
      <div v-for="branch in design.nodes.filter((item) => item.nodeType === 'branch')" :key="branch.nodeKey">
        <strong
          >{{ branch.title }} ·
          {{
            branch.routeMode === 'manual'
              ? workflowLanguage.t('platform.workflow-config.manual-route', '人工单选')
              : workflowLanguage.t('platform.workflow-config.auto-route', '自动判定（全部命中出口）')
          }}</strong
        >
        <p v-if="branch.routeMode === 'manual'">
          {{
            workflowLanguage.t(
              'platform.workflow-config.selector-summary',
              '由{title}的实际办理人选路；{reason}。',
              {
                title:
                  design.nodes.find((item) => item.nodeKey === branch.selectorNodeKey)?.title ??
                  workflowLanguage.t('platform.workflow-config.missing-selector', '未配置选择节点'),
                reason: branch.requireManualSelectionReason
                  ? workflowLanguage.t('platform.workflow-config.reason-required', '必须填写原因')
                  : workflowLanguage.t('platform.workflow-config.reason-optional', '原因选填'),
              },
            )
          }}
        </p>
        <ul>
          <li
            v-for="link in design.links.filter((item) => item.sourceNodeKey === branch.nodeKey)"
            :key="link.routeKey"
          >
            {{ link.title ?? workflowLanguage.t('platform.workflow-config.exit', '出口') }}：{{
              link.defaultRoute
                ? workflowLanguage.t('platform.workflow-config.fallback-exit', '默认兜底')
                : link.conditionExpression ||
                  workflowLanguage.t('platform.workflow-config.unconditional', '无条件（始终命中）')
            }}
          </li>
        </ul>
        <p>
          {{
            workflowLanguage.t(
              'platform.workflow-config.convergence-counting',
              '汇聚：{summary}，只计算有效出口。',
              { summary: branchConvergenceSummary(branch) },
            )
          }}
        </p>
      </div>
      <details>
        <summary>
          {{
            workflowLanguage.t(
              'platform.screen.WorkflowConfigurationView.view-published-workflow',
              '查看发布流程图',
            )
          }}
        </summary>
        <WorkflowDiagram
          presentation="detail"
          :nodes="design.nodes"
          :routes="design.links"
          :layout-json="design.layoutJson"
        />
      </details>
      <template #operation
        ><UiButton type="primary" :loading="busy" @click="publish">{{
          workflowLanguage.t('platform.screen.WorkflowConfigurationView.confirm-publication', '确认发布')
        }}</UiButton></template
      >
    </RecordDetailDrawer>
  </section>
</template>
<style scoped>
.workflow-configuration {
  height: 100%;
  min-width: 0;
  min-height: 0;
  box-sizing: border-box;
  overflow: hidden;
  container-type: inline-size;
}
.configuration-recovery {
  flex: 0 0 auto;
  max-height: 45%;
  overflow: auto;
  padding: 8px;
}
.configuration-status {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.configuration-version-selector {
  width: 150px;
}
.configuration-state {
  white-space: nowrap;
  color: var(--muyun-text-muted);
  font-size: 12px;
}
.configuration-state--dirty {
  color: var(--muyun-warning-text);
}
.workflow-configuration :deep(.management-panel-header-actions) {
  flex: 0 1 320px;
  max-width: 320px;
}
.configuration-action-bar {
  width: 320px;
  max-width: min(320px, 100%);
  min-width: 0;
}
.workflow-configuration :deep(.record-detail-layout--workspace > .record-detail-layout-content) {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.configuration-design {
  display: flex;
  flex: 1;
  min-width: 0;
  min-height: 0;
}
.configuration-design :deep(.workflow-diagram) {
  flex: 1;
  min-width: 0;
}
.configuration-property-operations {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.configuration-property-operations > :last-child {
  margin-inline-start: auto;
}
.configuration-route-locator {
  width: 160px;
}
@container (max-width: 640px) {
  .workflow-configuration :deep(.management-panel-header) {
    flex-wrap: wrap;
  }
  .configuration-action-bar {
    width: 240px;
  }
}
.publish-nodes {
  display: grid;
  gap: 16px;
  padding-left: 20px;
}
.publish-nodes details {
  margin-top: 8px;
}
summary {
  cursor: pointer;
  color: var(--muyun-primary);
}
</style>
