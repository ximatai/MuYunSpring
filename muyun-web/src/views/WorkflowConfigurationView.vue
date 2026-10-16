<script setup lang="ts">
import {
  displayModuleRuntime,
  useLanguageContext,
  useModuleContext,
  createWorkflowDefinitionClient,
  createModuleContext,
} from '@muyun/web-core';
import {
  workflowParticipantRules,
  hasWorkflowParticipants,
} from '../platform-components/workflowParticipantPolicy';
import { computed, onMounted, ref, watch } from 'vue';

import type {
  WorkflowConfigurationCatalog,
  WorkflowDefinition,
  WorkflowVersion,
  WorkflowDesign,
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
  ManagementWorkspace,
  ManagementExplorerColumn,
  RecordExplorerPanel,
  RecordListExplorer,
  RecordDetailPanel,
  RecordDetailDrawer,
  RecordPicker,
  RecordFieldLabel,
  FormulaExpressionEditor,
  resolveRecordFormFields,
  AdaptiveHeaderActionBar,
} from '@muyun/platform-components';
import {
  UiButton,
  UiInput,
  UiSelect,
  UiCheckbox,
  confirmAction,
  showSuccessMessage,
} from '@muyun/vue-ui-antdv';
import { usePageDataChange } from '../platform-admin-runtime/pageRealtime';
import { useWorkspaceViewUnsavedState } from '@muyun/platform-workbench';
import {
  createWorkflowDesign,
  createWorkflowApprovalNode,
  insertWorkflowNode,
  removeWorkflowNode,
  insertWorkflowBranch,
  appendWorkflowBranchPath,
  removeWorkflowBranch,
  updateWorkflowRoute,
} from '../platform-components/workflowDesignEditing';

const workflowLanguage = useLanguageContext();
function workflowTitle(value?: string) {
  return localizedWorkflowTitle(value, workflowLanguage);
}
defineOptions({ name: 'WorkflowConfigurationView' });
const props = defineProps<{ moduleAlias: string; moduleTitle?: string }>();
const context = useModuleContext({ moduleAlias: 'platform.workflow.definition' });
const client = createWorkflowDefinitionClient(context.http, props.moduleAlias);
const businessContext = createModuleContext({
  http: context.http,
  language: workflowLanguage,
  moduleAlias: props.moduleAlias,
  runtimeAccess: 'VIEW',
});
const organizationContext = createModuleContext<Record<string, unknown>>({
  http: context.http,
  language: workflowLanguage,
  moduleAlias: 'iam.organization',
  runtimeAccess: 'REFERENCE',
});
usePageDataChange({
  moduleAlias: props.moduleAlias,
  predicate: (change) => change.type === 'configuration-translations-changed',
  handler: async () => {
    try {
      await businessContext.runtime.refreshTexts?.();
    } catch (cause) {
      presentPlatformError(cause, { source: 'workflow-design', phase: 'load' });
    }
  },
});
const businessRuntime = computed(() => displayModuleRuntime(businessContext.runtime));
const fields = computed(() =>
  [...resolveRecordFormFields(businessRuntime.value?.uiDescriptor)].map(([name, field]) => ({
    name,
    label: field.label ?? name,
    valueType: field.valueType,
    referenceModule: field.reference?.targetModuleAlias,
  })),
);
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
async function loadCatalog(id: string) {
  catalog.value = await client.catalog(id);
}
const propertyOpen = ref(false),
  publishReviewOpen = ref(false),
  selectionDirty = ref(false);
async function loadFields() {
  try {
    await businessContext.runtime.ready;
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-design', phase: 'load' });
  }
}
const configurationActions = computed(() => [
  {
    key: 'save',
    title: workflowLanguage.t('platform.screen.WorkflowConfigurationView.save-draft', '保存草稿'),
    level: 'primary' as const,
    disabled: !editable.value || !dirty.value,
  },
  {
    key: 'validate',
    title: workflowLanguage.t('platform.screen.WorkflowConfigurationView.validate-workflow', '校验流程'),
    disabled: !editable.value,
  },
  {
    key: 'publish',
    title:
      version.value?.publishStatus === 'published'
        ? workflowLanguage.t('platform.screen.WorkflowConfigurationView.enable-published', '启用已发布版本')
        : workflowLanguage.t('platform.screen.WorkflowConfigurationView.publish-workflow', '发布流程'),
    disabled: !version.value || busy.value || selectionDirty.value,
  },
  {
    key: 'upgrade',
    title: workflowLanguage.t('platform.screen.WorkflowConfigurationView.create-version', '创建新版本'),
    level: 'secondary' as const,
    disabled: busy.value || dirty.value || selectionDirty.value,
  },
  {
    key: 'selection',
    title: workflowLanguage.t('platform.screen.WorkflowConfigurationView.matching-rules', '匹配规则'),
    level: 'secondary' as const,
    disabled: busy.value || dirty.value,
  },
  {
    key: 'disable',
    title: workflowLanguage.t('platform.screen.WorkflowConfigurationView.disable', '停用'),
    level: 'secondary' as const,
    danger: true,
    disabled: busy.value || dirty.value || selectionDirty.value,
  },
  {
    key: 'archive',
    title: workflowLanguage.t('platform.screen.WorkflowConfigurationView.archive', '归档'),
    level: 'secondary' as const,
    danger: true,
    disabled: busy.value || dirty.value || selectionDirty.value,
  },
]);
function configurationAction(key: string) {
  if (key === 'save') void save();
  else if (key === 'validate') void validate();
  else if (key === 'publish') publishReviewOpen.value = true;
  else if (key === 'upgrade') void upgrade();
  else if (key === 'selection') void toggleSelection();
  else void statusAction(key as 'disable' | 'archive');
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
const definitions = ref<WorkflowDefinition[]>([]),
  versions = ref<WorkflowVersion[]>([]);
const selected = ref<WorkflowDefinition>(),
  version = ref<WorkflowVersion>();
const design = ref<WorkflowDesign>({ nodes: [], links: [] });
const selectedNodeKey = ref(''),
  selectedRouteKey = ref('');
const busy = ref(false),
  dirty = ref(false),
  creating = ref(false),
  configuringSelection = ref(false);
const newAlias = ref(''),
  newTitle = ref(''),
  organizationId = ref(''),
  matchExpression = ref(''),
  matchPriority = ref('0'),
  fallback = ref(false),
  approvalEnabled = ref(true);
const frozen = computed(() => ['published', 'archived'].includes(version.value?.publishStatus ?? ''));
const editable = computed(() => version.value?.publishStatus === 'draft' && !busy.value);
const node = computed(() => design.value.nodes.find((item) => item.nodeKey === selectedNodeKey.value));
const route = computed(() => design.value.links.find((item) => item.routeKey === selectedRouteKey.value));
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
  () => dirty.value || selectionDirty.value,
  () => busy.value,
);
watch([newTitle, organizationId, matchExpression, matchPriority, fallback], () => {
  if (configuringSelection.value || creating.value) selectionDirty.value = true;
});
onMounted(() => {
  void reload();
  void loadFields();
});
async function run(operation: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  try {
    await operation();
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-design', phase: 'action' });
  } finally {
    busy.value = false;
  }
}
async function reload() {
  await run(async () => {
    definitions.value = await client.query();
  });
}
async function openDefinition(definition: WorkflowDefinition) {
  if (dirty.value || selectionDirty.value) {
    presentPlatformError(
      new Error(workflowLanguage.t('platform.workflow-config.save-first', '请先保存流程设计')),
      { source: 'workflow-design', phase: 'validation' },
    );
    return;
  }
  selectionDirty.value = false;
  await run(async () => {
    selected.value = definition;
    await loadCatalog(definition.id);
    configuringSelection.value = false;
    newTitle.value = definition.title ?? definition.alias;
    organizationId.value = definition.organizationId ?? '';
    matchExpression.value = definition.matchExpression ?? '';
    matchPriority.value = String(definition.matchPriority ?? 0);
    fallback.value = definition.defaultDefinition === true;
    creating.value = false;
    versions.value = (await client.versions(definition.id)).sort((a, b) => b.versionNo - a.versionNo);
    const latest = versions.value[0];
    if (latest) await loadVersion(latest);
    else {
      version.value = undefined;
      design.value = { nodes: [], links: [] };
    }
  });
}
async function loadVersion(next: WorkflowVersion) {
  version.value = next;
  design.value = await client.design(selected.value!.id, next.id);
  dirty.value = false;
  selectedNodeKey.value = design.value.nodes[0]?.nodeKey ?? '';
  selectedRouteKey.value = '';
  propertyOpen.value = false;
}
async function switchVersion(id: string) {
  if (dirty.value || selectionDirty.value) return;
  const next = versions.value.find((item) => item.id === id);
  if (next) await run(() => loadVersion(next));
}
async function createDefinition() {
  if (!newAlias.value.trim() || !newTitle.value.trim()) return;
  await run(async () => {
    const definition = await client.create({
      alias: newAlias.value.trim(),
      title: newTitle.value.trim(),
      approvalEnabled: approvalEnabled.value,
      organizationId: organizationId.value || null,
      matchExpression: matchExpression.value || null,
      matchPriority: Number(matchPriority.value),
      defaultDefinition: fallback.value,
      enabled: true,
    });
    selected.value = definition;
    definitions.value.push(definition);
    await loadCatalog(definition.id);
    creating.value = false;
    selectionDirty.value = false;
    const next = await client.upgrade(definition.id);
    versions.value = [next];
    version.value = next;
    design.value = createWorkflowDesign(approvalEnabled.value);
    dirty.value = true;
    selectNode('approval');
  });
}
async function discardSettings() {
  if (
    selectionDirty.value &&
    !(await confirmAction(
      {
        title: workflowLanguage.t('platform.workflow-config.discard-confirm', '放弃未保存的流程设置？'),
        danger: true,
        okText: workflowLanguage.t('platform.workflow-config.discard', '放弃修改'),
      },
      workflowLanguage,
    ))
  )
    return;
  creating.value = false;
  configuringSelection.value = false;
  selectionDirty.value = false;
  if (selected.value) {
    newTitle.value = selected.value.title ?? selected.value.alias;
    organizationId.value = selected.value.organizationId ?? '';
    matchExpression.value = selected.value.matchExpression ?? '';
    matchPriority.value = String(selected.value.matchPriority ?? 0);
    fallback.value = selected.value.defaultDefinition === true;
  }
}
async function toggleSelection() {
  if (configuringSelection.value) {
    await discardSettings();
    return;
  }
  configuringSelection.value = true;
}
async function saveSelection() {
  if (busy.value || !selected.value) return;
  await run(async () => {
    const saved = await client.saveSelection(selected.value!.id, {
      version: selected.value!.version,
      title: newTitle.value,
      organizationId: organizationId.value || null,
      matchExpression: matchExpression.value || null,
      matchPriority: Number(matchPriority.value),
      defaultDefinition: fallback.value,
    });
    definitions.value = definitions.value.map((item) => (item.id === saved.id ? saved : item));
    selected.value = saved;
    configuringSelection.value = false;
    selectionDirty.value = false;
    showSuccessMessage(
      workflowLanguage.t('platform.workflow-config.matching-saved', '流程匹配规则已保存，后续提交生效'),
    );
  });
}
async function save() {
  if (!version.value || !selected.value) return;
  await run(async () => {
    version.value = await client.saveDesign(
      selected.value!.id,
      version.value!.id,
      version.value!.version,
      design.value,
    );
    versions.value = versions.value.map((item) => (item.id === version.value!.id ? version.value! : item));
    dirty.value = false;
    showSuccessMessage(workflowLanguage.t('platform.workflow-config.draft-saved', '流程草稿已保存'));
  });
}
async function validate() {
  if (dirty.value) await save();
  if (dirty.value || !version.value) return;
  await run(async () => {
    await client.validate(selected.value!.id, version.value!.id);
    showSuccessMessage(workflowLanguage.t('platform.workflow-config.validated', '流程发布校验通过'));
  });
}
async function publish() {
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
  if (dirty.value || !selected.value || !version.value) return;
  await run(async () => {
    version.value = await client.publish(
      selected.value!.id,
      version.value!.id,
      selected.value!.version,
      version.value!.version,
    );
    versions.value = versions.value.map((item) => (item.id === version.value!.id ? version.value! : item));
    selected.value = await client.view(selected.value!.id);
    dirty.value = false;
    publishReviewOpen.value = false;
    showSuccessMessage(
      workflowLanguage.t('platform.workflow-config.published', '流程已发布，后续新申请使用该版本'),
    );
  });
  await reload();
}
async function upgrade() {
  if (!selected.value) return;
  await run(async () => {
    const next = await client.upgrade(selected.value!.id);
    versions.value = [next, ...versions.value.filter((item) => item.id !== next.id)];
    await loadVersion(next);
    if (!design.value.nodes.length) {
      design.value = createWorkflowDesign(selected.value!.approvalEnabled);
      dirty.value = true;
      selectNode('approval');
    }
  });
}
async function statusAction(action: 'disable' | 'archive') {
  if (!selected.value) return;
  if (
    !(await confirmAction(
      {
        title:
          action === 'archive'
            ? workflowLanguage.t('platform.workflow-config.archive-confirm', '归档此流程？')
            : workflowLanguage.t('platform.workflow-config.disable-confirm', '停用此流程？'),
        content: workflowLanguage.t(
          'platform.workflow-config.future-only',
          '此操作影响后续新申请，已有实例继续使用冻结版本。',
        ),
        danger: true,
      },
      workflowLanguage,
    ))
  )
    return;
  await run(async () => {
    selected.value = await client.changeStatus(selected.value!.id, action, selected.value!.version);
    showSuccessMessage(workflowLanguage.t('platform.workflow-config.status-updated', '流程状态已更新'));
  });
  await reload();
}
function selectNode(key: string) {
  selectedNodeKey.value = key;
  selectedRouteKey.value = '';
  propertyOpen.value = Boolean(key);
}
function selectRoute(key: string) {
  selectedRouteKey.value = key;
  selectedNodeKey.value = '';
  propertyOpen.value = Boolean(key);
}
function updateLayout(layoutJson: string) {
  if (!editable.value || design.value.layoutJson === layoutJson) return;
  design.value = { ...design.value, layoutJson };
  dirty.value = true;
}
function updateNode(patch: Partial<WorkflowNode>) {
  if (node.value && editable.value) {
    Object.assign(node.value, patch);
    dirty.value = true;
  }
}
function updateRoute(patch: Partial<WorkflowRoute>) {
  if (route.value && editable.value) {
    design.value = updateWorkflowRoute(design.value, route.value.routeKey, patch);
    dirty.value = true;
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
    design.value = insertWorkflowBranch(design.value, insertionRoute(), key);
    dirty.value = true;
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
    );
    dirty.value = true;
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-design', phase: 'validation' });
  }
}
async function deleteBranch() {
  if (!editable.value) return;
  try {
    const next = removeWorkflowBranch(design.value, selectedNodeKey.value);
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
    design.value = next;
    dirty.value = true;
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
      createWorkflowApprovalNode(key, '新审批节点'),
    );
    dirty.value = true;
    selectNode(key);
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-design', phase: 'validation' });
  }
}
function removeNode() {
  if (!editable.value) return;
  try {
    design.value = removeWorkflowNode(design.value, selectedNodeKey.value);
    dirty.value = true;
    selectedNodeKey.value = '';
    propertyOpen.value = false;
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-design', phase: 'validation' });
  }
}
function removeRoute() {
  if (!editable.value) return;
  design.value.links = design.value.links.filter((item) => item.routeKey !== selectedRouteKey.value);
  selectedRouteKey.value = '';
  dirty.value = true;
}
function addRoute() {
  if (!editable.value) return;
  const first = design.value.nodes[0],
    last = design.value.nodes.find((item) => item.nodeType === 'end');
  if (!first || !last) return;
  const key = `route_${Date.now().toString(36)}`;
  design.value.links.push({
    routeKey: key,
    sourceNodeKey: node.value?.nodeKey ?? first.nodeKey,
    targetNodeKey: last.nodeKey,
    title: '新路径',
  });
  selectedRouteKey.value = key;
  selectedNodeKey.value = '';
  propertyOpen.value = true;
  dirty.value = true;
}
</script>
<template>
  <section class="workflow-configuration">
    <ManagementWorkspace :explorer-count="1" :editing="dirty || selectionDirty || busy">
      <ManagementExplorerColumn
        ><RecordExplorerPanel
          :title="
            workflowLanguage.t('platform.screen.WorkflowConfigurationView.approval-workflow', '审批流程')
          "
          :searchable="false"
          :refresh-disabled="busy || dirty"
          @refresh="reload"
        >
          <template #actions
            ><UiButton
              :disabled="busy || dirty || selectionDirty"
              @click="
                creating = true;
                newTitle = '';
                newAlias = '';
              "
              >{{
                workflowLanguage.t('platform.screen.WorkflowConfigurationView.new-workflow', '新建流程')
              }}</UiButton
            ></template
          >
          <RecordListExplorer
            :records="definitions"
            :selected-id="selected?.id"
            :title-of="(item) => String(item.title ?? item.alias ?? '')"
            :code-of="(item) => String(item.alias ?? '')"
            :tag-of="(item) => workflowTitle(String(item.definitionStatus ?? ''))"
            @select="openDefinition(definitions.find((item) => item.id === $event.id)!)"
          /> </RecordExplorerPanel
      ></ManagementExplorerColumn>
      <RecordDetailPanel
        :title="selected?.title ?? workflowLanguage.t('platform.workflow-config.configuration', '流程配置')"
      >
        <form v-if="creating" class="property-form" @submit.prevent="createDefinition">
          <h3>
            {{ workflowLanguage.t('platform.screen.WorkflowConfigurationView.new-workflow', '新建流程') }}
          </h3>
          <label class="workflow-field"
            ><RecordFieldLabel required>{{
              workflowLanguage.t('platform.screen.WorkflowConfigurationView.workflow-name', '流程名称')
            }}</RecordFieldLabel
            ><UiInput
              :disabled="busy"
              v-model:value="newTitle"
              :aria-label="
                workflowLanguage.t('platform.screen.WorkflowConfigurationView.workflow-name', '流程名称')
              " /></label
          ><label class="workflow-field"
            ><RecordFieldLabel required>{{
              workflowLanguage.t('platform.screen.WorkflowConfigurationView.workflow-code', '流程编码')
            }}</RecordFieldLabel
            ><UiInput
              :disabled="busy"
              v-model:value="newAlias"
              :aria-label="
                workflowLanguage.t('platform.screen.WorkflowConfigurationView.workflow-code', '流程编码')
              "
          /></label>
          <UiCheckbox :disabled="busy" v-model:checked="approvalEnabled">{{
            workflowLanguage.t(
              'platform.screen.WorkflowConfigurationView.govern-business-approval-status',
              '驱动业务审批状态',
            )
          }}</UiCheckbox
          ><label class="workflow-field"
            >{{
              workflowLanguage.t(
                'platform.screen.WorkflowConfigurationView.applicable-organization',
                '适用组织',
              )
            }}<RecordPicker
              :value="organizationId || undefined"
              :context="organizationContext"
              :disabled="busy"
              mode="tree"
              @update:value="organizationId = $event ?? ''" /></label
          ><label class="workflow-field"
            >{{
              workflowLanguage.t('platform.screen.WorkflowConfigurationView.matching-condition', '匹配条件')
            }}<FormulaExpressionEditor
              :disabled="busy"
              v-model:value="matchExpression"
              :fields="fields"
              :placeholder="
                workflowLanguage.t(
                  'platform.screen.WorkflowConfigurationView.leave-empty-to-match-all-records',
                  '留空匹配所有记录',
                )
              " /></label
          ><label class="workflow-field"
            >{{ workflowLanguage.t('platform.screen.WorkflowConfigurationView.priority', '优先级')
            }}<UiInput :disabled="busy" v-model:value="matchPriority" type="number" /></label
          ><UiCheckbox :disabled="busy" v-model:checked="fallback">{{
            workflowLanguage.t(
              'platform.screen.WorkflowConfigurationView.use-as-the-fallback-workflow-for-this',
              '作为当前组织范围的兜底流程',
            )
          }}</UiCheckbox
          ><UiButton
            type="primary"
            :loading="busy"
            :disabled="busy || !newTitle.trim() || !newAlias.trim()"
            @click="createDefinition"
            >{{
              workflowLanguage.t(
                'platform.screen.WorkflowConfigurationView.create-workflow-draft',
                '创建流程草稿',
              )
            }}</UiButton
          ><UiButton :disabled="busy" @click="discardSettings">{{
            workflowLanguage.t('platform.screen.WorkflowConfigurationView.cancel-creation', '取消新建')
          }}</UiButton>
        </form>
        <template v-else-if="selected">
          <div class="toolbar" :class="{ 'configuration-toolbar--properties': propertyOpen }">
            <UiSelect
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
              :disabled="busy || dirty"
              @update:value="switchVersion(String($event))"
            /><AdaptiveHeaderActionBar
              class="configuration-action-bar"
              :actions="configurationActions"
              @action="configurationAction($event.key)"
            />
          </div>
          <form v-if="configuringSelection" class="property-form" @submit.prevent="saveSelection">
            <h3>
              {{
                workflowLanguage.t(
                  'platform.screen.WorkflowConfigurationView.workflow-matching-rules',
                  '流程匹配规则',
                )
              }}
            </h3>
            <label class="workflow-field"
              ><RecordFieldLabel required>{{
                workflowLanguage.t('platform.screen.WorkflowConfigurationView.workflow-name', '流程名称')
              }}</RecordFieldLabel
              ><UiInput :disabled="busy" v-model:value="newTitle"
            /></label>
            <label class="workflow-field"
              >{{
                workflowLanguage.t(
                  'platform.screen.WorkflowConfigurationView.applicable-organization',
                  '适用组织',
                )
              }}<RecordPicker
                :value="organizationId || undefined"
                :context="organizationContext"
                :disabled="busy"
                mode="tree"
                @update:value="organizationId = $event ?? ''"
            /></label>
            <label class="workflow-field"
              >{{
                workflowLanguage.t(
                  'platform.screen.WorkflowConfigurationView.matching-condition',
                  '匹配条件',
                )
              }}<FormulaExpressionEditor :disabled="busy" v-model:value="matchExpression" :fields="fields"
            /></label>
            <label class="workflow-field"
              >{{ workflowLanguage.t('platform.screen.WorkflowConfigurationView.priority', '优先级')
              }}<UiInput :disabled="busy" v-model:value="matchPriority" type="number"
            /></label>
            <UiCheckbox :disabled="busy" v-model:checked="fallback">{{
              workflowLanguage.t(
                'platform.screen.WorkflowConfigurationView.use-as-the-fallback-workflow-for-this',
                '作为当前组织范围的兜底流程',
              )
            }}</UiCheckbox>
            <p>
              {{
                workflowLanguage.t(
                  'platform.screen.WorkflowConfigurationView.changes-affect-future-submissions-existing-instances-retain',
                  '调整仅影响后续提交，已有实例保留原发布版本和分派快照。',
                )
              }}
            </p>
            <UiButton type="primary" :loading="busy" :disabled="busy" @click="saveSelection">{{
              workflowLanguage.t(
                'platform.screen.WorkflowConfigurationView.save-matching-rules',
                '保存匹配规则',
              )
            }}</UiButton
            ><UiButton :disabled="busy" @click="discardSettings">{{
              workflowLanguage.t('platform.screen.WorkflowConfigurationView.cancel-changes', '取消修改')
            }}</UiButton>
          </form>
          <p v-if="frozen">
            {{
              workflowLanguage.t(
                'platform.screen.WorkflowConfigurationView.this-version-is-frozen-create-a-new',
                '此版本已冻结。修改流程请创建新版本；已有实例继续使用原版本。',
              )
            }}
          </p>
          <p v-if="dirty">
            {{
              workflowLanguage.t(
                'platform.screen.WorkflowConfigurationView.the-draft-has-unsaved-changes',
                '草稿有未保存的修改',
              )
            }}
          </p>
          <div class="configuration-design" :class="{ 'configuration-design--properties': propertyOpen }">
            <WorkflowDiagram
              :key="version?.id"
              :nodes="design.nodes"
              :routes="design.links"
              :selected-node-key="selectedNodeKey"
              :selected-route-key="selectedRouteKey"
              :layout-json="design.layoutJson"
              :editable="editable"
              interactive
              @select="selectNode"
              @select-route="selectRoute"
              @layout-change="updateLayout"
            />
            <div class="toolbar">
              <UiButton :disabled="!editable" @click="addNode">{{
                workflowLanguage.t(
                  'platform.screen.WorkflowConfigurationView.insert-approval-node',
                  '插入审批节点',
                )
              }}</UiButton
              ><UiButton :disabled="!editable" @click="addBranch">{{
                workflowLanguage.t(
                  'platform.screen.WorkflowConfigurationView.insert-branch-and-convergence',
                  '插入分支与汇聚',
                )
              }}</UiButton
              ><UiButton :disabled="!editable" @click="addRoute">{{
                workflowLanguage.t('platform.screen.WorkflowConfigurationView.add-link', '添加连线')
              }}</UiButton
              ><UiSelect
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
            </div>
          </div>
          <RecordDetailDrawer
            width="wide"
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
              @remove-node="removeNode"
              @remove-route="removeRoute"
              @append-branch-path="appendBranchPath"
              @delete-branch="deleteBranch"
            />
            <template #operation
              ><UiButton :disabled="busy" @click="propertyOpen = false">{{
                workflowLanguage.t(
                  'platform.screen.WorkflowConfigurationView.finish-configuration',
                  '完成配置',
                )
              }}</UiButton></template
            >
          </RecordDetailDrawer>
        </template>
        <p v-else>
          {{
            workflowLanguage.t(
              'platform.screen.WorkflowConfigurationView.select-or-create-a-workflow-to-configure',
              '选择或新建流程，配置节点、参与人、路径和业务完成条件。',
            )
          }}
        </p>
      </RecordDetailPanel>
    </ManagementWorkspace>
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
        }}； {{ workflowLanguage.t('platform.workflow-config.priority', '优先级') }}
        {{ selected?.matchPriority ?? 0 }}；{{
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
  padding: 12px;
  height: 100%;
  min-width: 0;
  min-height: 0;
  box-sizing: border-box;
  overflow: hidden;
  container-type: inline-size;
}
header,
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 16px;
}
h2 {
  margin: 0 auto 0 0;
  font-size: 18px;
}
.property-form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(240px, 100%), 1fr));
  gap: 16px;
  max-width: 850px;
}
.property-form h3 {
  grid-column: 1/-1;
}
.property-form p {
  grid-column: 1/-1;
}
.workflow-field {
  display: grid;
  gap: 6px;
}
.toolbar {
  margin-top: 16px;
}
.configuration-action-bar {
  flex: 1 1 420px;
  min-width: 0;
}
.configuration-design {
  min-width: 0;
}
.configuration-design :deep(.diagram-viewport) {
  height: clamp(360px, calc(100dvh - 420px), 720px);
}
@container (min-width: 1600px) {
  .configuration-toolbar--properties,
  .configuration-design--properties {
    margin-right: 760px;
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
