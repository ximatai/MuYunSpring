<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useModuleContext, createWorkflowClient, createModuleContext } from '@muyun/web-core';
import type {
  WorkflowDefinition,
  WorkflowVersion,
  WorkflowDesign,
  WorkflowNode,
  WorkflowRoute,
} from '@muyun/web-contracts';
import {
  WorkflowParticipantEditor,
  WorkflowBusinessTaskEditor,
  WorkflowDiagram,
  presentPlatformError,
  workflowTitle,
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
import { useWorkspaceViewUnsavedState } from '@muyun/platform-workbench';
import {
  insertWorkflowNode,
  removeWorkflowNode,
  insertWorkflowBranch,
  appendWorkflowBranchPath,
  removeWorkflowBranch,
  updateWorkflowRoute,
} from '../platform-components/workflowDesignEditing';
defineOptions({ name: 'WorkflowConfigurationView' });
const props = defineProps<{ moduleAlias: string; moduleTitle?: string }>();
const context = useModuleContext({ moduleAlias: 'platform.workflow.definition' });
const client = createWorkflowClient(context.http);
const businessContext = createModuleContext({
  http: context.http,
  moduleAlias: props.moduleAlias,
  runtimeAccess: 'VIEW',
});
const organizationContext = createModuleContext<Record<string, unknown>>({
  http: context.http,
  moduleAlias: 'iam.organization',
  runtimeAccess: 'REFERENCE',
});
const fields = ref<Array<{ name: string; label: string; valueType?: string; referenceModule?: string }>>([]);
const catalog = ref<{
  tasks: { id: string; title: string }[];
  queries: { id: string; title: string }[];
  generations: { id: string; title: string; targetModuleAlias: string }[];
  associations: { id: string; title: string }[];
}>({ tasks: [], queries: [], generations: [], associations: [] });
const actionOptions = ref<{ value: string; label: string }[]>([]),
  associationOptions = ref<{ value: string; label: string }[]>([]);
async function loadCatalog(id: string) {
  catalog.value = await context.http.request({ path: `${base}/${id}/configuration-catalog` });
  associationOptions.value = catalog.value.associations.map((item) => ({
    value: item.id,
    label: item.title,
  }));
}
const propertyOpen = ref(false),
  publishReviewOpen = ref(false),
  selectionDirty = ref(false);
async function loadFields() {
  try {
    const runtime = await businessContext.runtime.ready;
    actionOptions.value = runtime.actions.map((action) => ({
      value: action.actionCode,
      label: action.title ?? workflowTitle(action.actionCode),
    }));
    fields.value = [...resolveRecordFormFields(runtime.uiDescriptor)].map(([name, field]) => ({
      name,
      label: field.label ?? name,
      valueType: field.valueType,
      referenceModule: field.reference?.targetModuleAlias,
    }));
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-design', phase: 'load' });
  }
}
const configurationActions = computed(() => [
  { key: 'save', title: '保存草稿', level: 'primary' as const, disabled: !editable.value || !dirty.value },
  { key: 'validate', title: '校验流程', disabled: !editable.value },
  {
    key: 'publish',
    title: version.value?.publishStatus === 'published' ? '启用已发布版本' : '发布流程',
    disabled: !version.value || busy.value || selectionDirty.value,
  },
  {
    key: 'upgrade',
    title: '创建新版本',
    level: 'secondary' as const,
    disabled: busy.value || dirty.value || selectionDirty.value,
  },
  { key: 'selection', title: '匹配规则', level: 'secondary' as const, disabled: busy.value || dirty.value },
  {
    key: 'disable',
    title: '停用',
    level: 'secondary' as const,
    danger: true,
    disabled: busy.value || dirty.value || selectionDirty.value,
  },
  {
    key: 'archive',
    title: '归档',
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
    const rules = JSON.parse(text ?? '{}').rules ?? [];
    return (
      rules
        .map(
          (rule: { type: string; ids?: string[]; fieldName?: string }) =>
            (({
              INITIATOR_SELF: '提交人本人',
              USER: '指定账号',
              ROLE: '角色成员',
              DEPT: '部门成员',
              ORG: '组织成员',
              FIELD: '业务人员字段',
              RELATIVE: '相对人员',
            })[rule.type] ?? rule.type) +
            (rule.ids?.length
              ? `（${rule.ids.length} 个目标）`
              : rule.fieldName
                ? `（${fields.value.find((field) => field.name === rule.fieldName)?.label ?? rule.fieldName}）`
                : ''),
        )
        .join('、') || '尚未配置参与人'
    );
  } catch {
    return '人员配置格式无效';
  }
}
const base = `/platform.module/${encodeURIComponent(props.moduleAlias)}/workflow-definitions`;
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
const editable = computed(() => version.value?.publishStatus === 'draft' && !busy.value);
const node = computed(() => design.value.nodes.find((item) => item.nodeKey === selectedNodeKey.value));
const route = computed(() => design.value.links.find((item) => item.routeKey === selectedRouteKey.value));
const typeOptions = ['approval', 'task', 'milestone'].map((value) => ({
  value,
  label: workflowTitle(value),
}));
const nodeOptions = computed(() =>
  design.value.nodes.map((item) => ({ value: item.nodeKey, label: item.title ?? item.nodeKey })),
);
const branchRoutes = computed(() =>
  design.value.links.filter((link) => link.sourceNodeKey === node.value?.nodeKey),
);
const routeSource = computed(() =>
  design.value.nodes.find((item) => item.nodeKey === route.value?.sourceNodeKey),
);
const selectorNodeOptions = computed(() =>
  design.value.nodes
    .filter((item) => ['start', 'approval', 'task'].includes(item.nodeType))
    .map((item) => ({ value: item.nodeKey, label: item.title ?? item.nodeKey })),
);
function branchConvergenceSummary(branch: WorkflowNode) {
  const convergence = design.value.nodes.find((item) => item.nodeKey === branch.convergeNodeKey);
  const labels: Record<string, string> = { all: '全部到达', any: '任一到达', ratio: '比例到达' };
  const mode = convergence?.convergeMode ?? 'all';
  return `${labels[mode]}${mode === 'ratio' ? ` ${convergence?.convergeRatio ?? 100}%` : ''}`;
}
useWorkspaceViewUnsavedState(
  '流程配置',
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
async function post<T>(path: string, body: unknown = {}) {
  return context.http.request<T>({ method: 'POST', path, body });
}
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
    const response = await post<{ records: WorkflowDefinition[] }>(`${base}/query`, {
      page: { pageNum: 1, pageSize: 200 },
    });
    definitions.value = response.records;
  });
}
async function openDefinition(definition: WorkflowDefinition) {
  if (dirty.value || selectionDirty.value) {
    presentPlatformError(new Error('请先保存流程设计'), { source: 'workflow-design', phase: 'validation' });
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
    versions.value = (
      await post<{ records: WorkflowVersion[] }>(`${base}/${definition.id}/versions/query`, {
        page: { pageNum: 1, pageSize: 200 },
      })
    ).records.sort((a, b) => b.versionNo - a.versionNo);
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
  design.value = await client.design(base, selected.value!.id, next.id);
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
    const definition = await post<WorkflowDefinition>(`${base}/insert`, {
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
    const next = await post<WorkflowVersion>(`${base}/${definition.id}/upgrade`);
    versions.value = [next];
    version.value = next;
    design.value = basicDesign(approvalEnabled.value);
    dirty.value = true;
    selectNode('approval');
  });
}
async function discardSettings() {
  if (
    selectionDirty.value &&
    !(await confirmAction({ title: '放弃未保存的流程设置？', danger: true, okText: '放弃修改' }))
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
    const saved = await post<WorkflowDefinition>(`${base}/${selected.value!.id}/selection`, {
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
    showSuccessMessage('流程匹配规则已保存，后续提交生效');
  });
}
function basicDesign(approval: boolean): WorkflowDesign {
  const nodes: WorkflowNode[] = [
    { nodeKey: 'start', nodeType: 'start', title: '提交' },
    {
      nodeKey: 'approval',
      nodeType: 'approval',
      title: '审批',
      approvalMode: 'all',
      participantPolicyText: '{"rules":[]}',
      allowReject: true,
      requireRejectReason: true,
      allowRejectReturnToMe: true,
      allowRollback: true,
      requireRollbackReason: true,
      allowAddSign: true,
    },
    ...(approval
      ? [
          {
            nodeKey: 'approved',
            nodeType: 'milestone',
            title: '审批完成',
            milestoneType: 'approval_completed',
          },
        ]
      : []),
    { nodeKey: 'end', nodeType: 'end', title: '完成' },
  ];
  return {
    nodes,
    links: nodes.slice(1).map((item, index) => ({
      routeKey: `route_${index + 1}`,
      sourceNodeKey: nodes[index]!.nodeKey,
      targetNodeKey: item.nodeKey,
      title: '继续',
    })),
  };
}
async function save() {
  if (!version.value || !selected.value) return;
  await run(async () => {
    version.value = await post<WorkflowVersion>(
      `${base}/${selected.value!.id}/versions/${version.value!.id}/design`,
      { version: version.value!.version, design: design.value },
    );
    dirty.value = false;
    showSuccessMessage('流程草稿已保存');
  });
}
async function validate() {
  if (dirty.value) await save();
  if (dirty.value || !version.value) return;
  await run(async () => {
    await post(`${base}/${selected.value!.id}/versions/${version.value!.id}/validate`);
    showSuccessMessage('流程发布校验通过');
  });
}
async function publish() {
  if (
    design.value.nodes.some(
      (node) =>
        ['approval', 'task'].includes(node.nodeType) &&
        participantSummary(node.participantPolicyText).startsWith('尚未'),
    )
  ) {
    presentPlatformError(new Error('请为每个审批或业务任务节点明确配置参与人'), {
      source: 'workflow-design',
      phase: 'validation',
    });
    return;
  }
  if (dirty.value) await save();
  if (dirty.value || !selected.value || !version.value) return;
  await run(async () => {
    version.value = await post<WorkflowVersion>(
      `${base}/${selected.value!.id}/versions/${version.value!.id}/publish`,
      { definitionVersion: selected.value!.version, version: version.value!.version },
    );
    versions.value = versions.value.map((item) => (item.id === version.value!.id ? version.value! : item));
    selected.value = await context.http.request<WorkflowDefinition>({
      path: `${base}/view/${selected.value!.id}`,
    });
    dirty.value = false;
    publishReviewOpen.value = false;
    showSuccessMessage('流程已发布，后续新申请使用该版本');
  });
  await reload();
}
async function upgrade() {
  if (!selected.value) return;
  await run(async () => {
    const next = await post<WorkflowVersion>(`${base}/${selected.value!.id}/upgrade`);
    versions.value = [next, ...versions.value.filter((item) => item.id !== next.id)];
    await loadVersion(next);
    if (!design.value.nodes.length) {
      design.value = basicDesign(selected.value!.approvalEnabled);
      dirty.value = true;
      selectNode('approval');
    }
  });
}
async function statusAction(action: 'disable' | 'archive') {
  if (!selected.value) return;
  if (
    !(await confirmAction({
      title: action === 'archive' ? '归档此流程？' : '停用此流程？',
      content: '此操作影响后续新申请，已有实例继续使用冻结版本。',
      danger: true,
    }))
  )
    return;
  await run(async () => {
    selected.value = await post<WorkflowDefinition>(`${base}/${selected.value!.id}/${action}`, {
      version: selected.value!.version,
    });
    showSuccessMessage('流程状态已更新');
  });
  await reload();
}
function selectNode(key: string) {
  selectedNodeKey.value = key;
  selectedRouteKey.value = '';
  propertyOpen.value = Boolean(key);
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
  try {
    const next = removeWorkflowBranch(design.value, selectedNodeKey.value);
    if (
      !(await confirmAction({
        title: '删除整个分支？',
        content: `将删除分支、配对汇聚及其中 ${design.value.nodes.length - next.nodes.length - 2} 个节点，并连接前后路径。`,
        danger: true,
        okText: '删除分支',
      }))
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
  const outgoing = design.value.links.filter((item) => item.sourceNodeKey === selectedNodeKey.value);
  const route = selectedRouteKey.value || (outgoing.length === 1 ? outgoing[0]?.routeKey : undefined);
  const key = `node_${Date.now().toString(36)}`;
  try {
    design.value = insertWorkflowNode(design.value, route ?? '', {
      nodeKey: key,
      nodeType: 'approval',
      title: '新审批节点',
      approvalMode: 'all',
      participantPolicyText: '{"rules":[]}',
      allowReject: true,
    });
    dirty.value = true;
    selectNode(key);
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-design', phase: 'validation' });
  }
}
function removeNode() {
  try {
    design.value = removeWorkflowNode(design.value, selectedNodeKey.value);
    dirty.value = true;
    selectedNodeKey.value = '';
    propertyOpen.value = false;
  } catch (cause) {
    presentPlatformError(cause, { source: 'workflow-design', phase: 'validation' });
  }
}
function addRoute() {
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
          title="审批流程"
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
              >新建流程</UiButton
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
      <RecordDetailPanel :title="selected?.title ?? '流程配置'">
        <form v-if="creating" class="property-form" @submit.prevent="createDefinition">
          <h3>新建流程</h3>
          <label
            ><RecordFieldLabel required>流程名称</RecordFieldLabel
            ><UiInput :disabled="busy" v-model:value="newTitle" aria-label="流程名称" /></label
          ><label
            ><RecordFieldLabel required>流程编码</RecordFieldLabel
            ><UiInput :disabled="busy" v-model:value="newAlias" aria-label="流程编码"
          /></label>
          <UiCheckbox :disabled="busy" v-model:checked="approvalEnabled">驱动业务审批状态</UiCheckbox
          ><label
            >适用组织<RecordPicker
              :value="organizationId || undefined"
              :context="organizationContext"
              :disabled="busy"
              mode="tree"
              @update:value="organizationId = $event ?? ''" /></label
          ><label
            >匹配条件<FormulaExpressionEditor
              :disabled="busy"
              v-model:value="matchExpression"
              :fields="fields"
              placeholder="留空匹配所有记录" /></label
          ><label>优先级<UiInput :disabled="busy" v-model:value="matchPriority" type="number" /></label
          ><UiCheckbox :disabled="busy" v-model:checked="fallback">作为当前组织范围的兜底流程</UiCheckbox
          ><UiButton
            type="primary"
            :loading="busy"
            :disabled="busy || !newTitle.trim() || !newAlias.trim()"
            @click="createDefinition"
            >创建流程草稿</UiButton
          ><UiButton :disabled="busy" @click="discardSettings">取消新建</UiButton>
        </form>
        <template v-else-if="selected">
          <div class="toolbar">
            <UiSelect
              :value="version?.id"
              :options="
                versions.map((item) => ({
                  value: item.id,
                  label: `版本 ${item.versionNo} · ${workflowTitle(item.publishStatus)}`,
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
            <h3>流程匹配规则</h3>
            <label
              ><RecordFieldLabel required>流程名称</RecordFieldLabel
              ><UiInput :disabled="busy" v-model:value="newTitle"
            /></label>
            <label
              >适用组织<RecordPicker
                :value="organizationId || undefined"
                :context="organizationContext"
                :disabled="busy"
                mode="tree"
                @update:value="organizationId = $event ?? ''"
            /></label>
            <label
              >匹配条件<FormulaExpressionEditor
                :disabled="busy"
                v-model:value="matchExpression"
                :fields="fields"
            /></label>
            <label>优先级<UiInput :disabled="busy" v-model:value="matchPriority" type="number" /></label>
            <UiCheckbox :disabled="busy" v-model:checked="fallback">作为当前组织范围的兜底流程</UiCheckbox>
            <p>调整仅影响后续提交，已有实例保留原发布版本和分派快照。</p>
            <UiButton type="primary" :loading="busy" :disabled="busy" @click="saveSelection"
              >保存匹配规则</UiButton
            ><UiButton :disabled="busy" @click="discardSettings">取消修改</UiButton>
          </form>
          <p v-if="!editable">此版本已冻结。修改流程请创建新版本；已有实例继续使用原版本。</p>
          <p v-if="dirty">草稿有未保存的修改</p>
          <WorkflowDiagram
            :nodes="design.nodes"
            :routes="design.links"
            :selected-node-key="selectedNodeKey"
            interactive
            @select="selectNode"
          />
          <div class="toolbar">
            <UiButton :disabled="!editable" @click="addNode">插入审批节点</UiButton
            ><UiButton :disabled="!editable" @click="addBranch">插入分支与汇聚</UiButton
            ><UiButton :disabled="!editable" @click="addRoute">添加连线</UiButton
            ><UiSelect
              :value="selectedRouteKey || undefined"
              placeholder="选择连线"
              :options="
                design.links.map((item) => ({
                  value: item.routeKey,
                  label: `${item.title ?? '路径'}：${design.nodes.find((node) => node.nodeKey === item.sourceNodeKey)?.title} → ${design.nodes.find((node) => node.nodeKey === item.targetNodeKey)?.title}`,
                }))
              "
              @update:value="
                selectedRouteKey = String($event);
                selectedNodeKey = '';
                propertyOpen = true;
              "
            />
          </div>
          <RecordDetailDrawer
            render-mode="inline"
            :open="propertyOpen"
            :title="node?.title ? `节点属性 · ${node.title}` : '路径属性'"
            @close="propertyOpen = false"
          >
            <div v-if="node" class="property-form">
              <h3>节点属性 · {{ node.title }}</h3>
              <label
                >节点名称<UiInput
                  :value="node.title"
                  :disabled="!editable"
                  @update:value="updateNode({ title: $event })" /></label
              ><label v-if="['approval', 'task', 'milestone'].includes(node.nodeType)"
                >节点类型<UiSelect
                  :value="node.nodeType"
                  :options="typeOptions"
                  :disabled="!editable"
                  @update:value="updateNode({ nodeType: String($event) })"
              /></label>
              <WorkflowParticipantEditor
                v-if="['approval', 'task'].includes(node.nodeType)"
                :value="node.participantPolicyText"
                :http="context.http"
                :fields="fields"
                :disabled="!editable"
                @update:value="updateNode({ participantPolicyText: $event })"
              />
              <template v-if="node.nodeType === 'approval'"
                ><label
                  >审批规则<UiSelect
                    :value="node.approvalMode"
                    :options="
                      ['all', 'any', 'ratio', 'notice'].map((value) => ({
                        value,
                        label: workflowTitle(value),
                      }))
                    "
                    :disabled="!editable"
                    @update:value="updateNode({ approvalMode: String($event) })" /></label
                ><label v-if="node.approvalMode === 'ratio'"
                  >通过比例 %<UiInput
                    :value="node.approvalRatio ?? 100"
                    type="number"
                    :disabled="!editable"
                    @update:value="updateNode({ approvalRatio: Number($event) })" /></label
                ><UiCheckbox
                  :checked="node.autoApproveSameUser"
                  :disabled="!editable"
                  @update:checked="updateNode({ autoApproveSameUser: $event })"
                  >同一办理人连续审批自动通过</UiCheckbox
                ><UiCheckbox
                  :checked="node.allowReject"
                  :disabled="!editable"
                  @update:checked="updateNode({ allowReject: $event })"
                  >允许驳回</UiCheckbox
                ><UiCheckbox
                  :checked="node.requireRejectReason"
                  :disabled="!editable"
                  @update:checked="updateNode({ requireRejectReason: $event })"
                  >驳回必须填写原因</UiCheckbox
                ><UiCheckbox
                  :checked="node.allowRejectReturnToMe"
                  :disabled="!editable"
                  @update:checked="updateNode({ allowRejectReturnToMe: $event })"
                  >允许重提回到本审批人</UiCheckbox
                ><UiCheckbox
                  :checked="node.allowRollback"
                  :disabled="!editable"
                  @update:checked="updateNode({ allowRollback: $event })"
                  >允许回退</UiCheckbox
                ><UiCheckbox
                  :checked="node.allowAddSign"
                  :disabled="!editable"
                  @update:checked="updateNode({ allowAddSign: $event })"
                  >允许加签</UiCheckbox
                ><label
                  >预警时长（分钟）<UiInput
                    :value="node.warningDurationMinutes"
                    type="number"
                    :disabled="!editable"
                    @update:value="
                      updateNode({ warningDurationMinutes: $event ? Number($event) : undefined })
                    " /></label
                ><label
                  >超时时长（分钟）<UiInput
                    :value="node.overtimeDurationMinutes"
                    type="number"
                    :disabled="!editable"
                    @update:value="
                      updateNode({ overtimeDurationMinutes: $event ? Number($event) : undefined })
                    " /></label
              ></template>
              <template v-if="node.nodeType === 'branch'"
                ><label
                  >选路模式<UiSelect
                    :value="node.routeMode ?? 'auto'"
                    :options="['auto', 'manual'].map((value) => ({ value, label: workflowTitle(value) }))"
                    :disabled="!editable"
                    @update:value="
                      updateNode({
                        routeMode: String($event),
                        selectorNodeKey: undefined,
                        requireManualSelectionReason: false,
                      })
                    " /></label
                ><label
                  >配对汇聚节点<UiSelect
                    :value="node.convergeNodeKey"
                    :options="
                      nodeOptions.filter(
                        (option) =>
                          design.nodes.find((item) => item.nodeKey === option.value)?.nodeType === 'converge',
                      )
                    "
                    :disabled="!editable"
                    @update:value="updateNode({ convergeNodeKey: String($event) })" /></label
                ><label v-if="node.routeMode === 'manual'"
                  >路径选择节点<UiSelect
                    :value="node.selectorNodeKey"
                    :options="selectorNodeOptions"
                    :disabled="!editable"
                    @update:value="updateNode({ selectorNodeKey: String($event) })" /></label
                ><UiCheckbox
                  v-if="node.routeMode === 'manual'"
                  :checked="node.requireManualSelectionReason"
                  :disabled="!editable"
                  @update:checked="updateNode({ requireManualSelectionReason: $event })"
                  >路径选择必须填写原因</UiCheckbox
                >
                <p>
                  {{
                    node.routeMode === 'manual'
                      ? '由指定前序节点的实际办理人单选一条出口。条件与默认标记只提供建议，不限制人工选择。'
                      : '所有命中的非默认出口同时生效；未配置条件表示始终命中。只有全部未命中时才启用默认出口；无兜底则拒绝流转。'
                  }}
                </p>
                <div v-for="link in branchRoutes" :key="link.routeKey">
                  <UiButton
                    @click="
                      selectedRouteKey = link.routeKey;
                      selectedNodeKey = '';
                      propertyOpen = true;
                    "
                    >配置出口：{{ link.title ?? link.routeKey }}</UiButton
                  >
                  <span>{{
                    link.defaultRoute ? '默认出口' : link.conditionExpression || '无条件（始终命中）'
                  }}</span>
                </div>
                <UiButton :disabled="!editable" @click="appendBranchPath">追加分支出口</UiButton>
                <UiButton danger :disabled="!editable" @click="deleteBranch">删除整个分支</UiButton>
              </template>
              <template v-if="node.nodeType === 'converge'"
                ><label
                  >汇聚规则<UiSelect
                    :value="node.convergeMode ?? 'all'"
                    :options="[
                      { value: 'all', label: '全部到达' },
                      { value: 'any', label: '任一到达' },
                      { value: 'ratio', label: '比例到达' },
                    ]"
                    :disabled="!editable"
                    @update:value="updateNode({ convergeMode: String($event) })" /></label
                ><label v-if="node.convergeMode === 'ratio'"
                  >汇聚比例 %<UiInput
                    :value="node.convergeRatio ?? 100"
                    type="number"
                    :disabled="!editable"
                    @update:value="updateNode({ convergeRatio: Number($event) })"
                /></label>
                <p>只统计本次实际生效的出口；任一或比例达成后，剩余未完成路径及其待办自动取消。</p></template
              >
              <template v-if="node.nodeType === 'milestone'"
                ><label
                  >里程碑<UiSelect
                    :value="node.milestoneType ?? 'approval_completed'"
                    :options="[{ value: 'approval_completed', label: '审批完成' }]"
                    :disabled="!editable"
                    @update:value="updateNode({ milestoneType: String($event) })" /></label
              ></template>
              <template v-if="node.nodeType === 'task'">
                <label
                  >引用已配置任务定义<UiSelect
                    :options="catalog.tasks.map((item) => ({ value: item.id, label: item.title }))"
                    show-search
                    :value="node.taskDefinitionId"
                    :disabled="!editable"
                    @update:value="updateNode({ taskDefinitionId: $event ? String($event) : undefined })"
                /></label>
                <WorkflowBusinessTaskEditor
                  :key="node.nodeKey"
                  :value="node.nodeConfigText"
                  :module-alias="moduleAlias"
                  :fields="fields"
                  :catalog="catalog"
                  :actions="actionOptions"
                  :associations="associationOptions"
                  :disabled="!editable"
                  @update:value="updateNode({ nodeConfigText: $event })"
                />
              </template>
              <UiButton
                danger
                :disabled="!editable || ['start', 'end', 'branch', 'converge'].includes(node.nodeType)"
                @click="removeNode"
                >删除并连接前后节点</UiButton
              >
            </div>
            <div v-if="route" class="property-form">
              <h3>连线属性</h3>
              <label
                >路径名称<UiInput
                  :value="route.title"
                  :disabled="!editable"
                  @update:value="updateRoute({ title: $event })" /></label
              ><label
                >起点<UiSelect
                  :value="route.sourceNodeKey"
                  :options="nodeOptions"
                  :disabled="!editable"
                  @update:value="updateRoute({ sourceNodeKey: String($event) })" /></label
              ><label
                >终点<UiSelect
                  :value="route.targetNodeKey"
                  :options="nodeOptions"
                  :disabled="!editable"
                  @update:value="updateRoute({ targetNodeKey: String($event) })" /></label
              ><label v-if="routeSource?.nodeType === 'branch' && !route.defaultRoute"
                >出口条件<FormulaExpressionEditor
                  :fields="fields"
                  :value="route.conditionExpression ?? ''"
                  :disabled="!editable"
                  @update:value="updateRoute({ conditionExpression: $event })" /></label
              ><UiCheckbox
                v-if="routeSource?.nodeType === 'branch'"
                :checked="route.defaultRoute"
                :disabled="!editable"
                @update:checked="updateRoute({ defaultRoute: $event })"
                >默认兜底路径</UiCheckbox
              >
              <p v-if="routeSource?.nodeType === 'branch'">
                {{
                  route.defaultRoute
                    ? '其余条件均未命中时走此出口。默认出口不配置条件，每个分支最多一个。'
                    : '条件为空表示始终命中，可用于并行审批；互斥分支请使用互斥条件。'
                }}
              </p>
              <UiButton
                danger
                :disabled="!editable"
                @click="
                  design.links = design.links.filter((item) => item.routeKey !== selectedRouteKey);
                  selectedRouteKey = '';
                  dirty = true;
                "
                >删除连线</UiButton
              >
            </div>
            <template #operation
              ><UiButton :disabled="busy" @click="propertyOpen = false">完成配置</UiButton></template
            >
          </RecordDetailDrawer>
        </template>
        <p v-else>选择或新建流程，配置节点、参与人、路径和业务完成条件。</p>
      </RecordDetailPanel>
    </ManagementWorkspace>
    <RecordDetailDrawer
      render-mode="inline"
      :open="publishReviewOpen"
      title="确认发布流程"
      @close="publishReviewOpen = false"
    >
      <p>
        流程：{{ selected?.title }} · 版本 {{ version?.versionNo }}。发布后配置冻结；已有实例继续使用原版本。
      </p>
      <p>
        匹配范围：{{ selected?.organizationId ? '指定组织及下级组织' : '模块内全部组织' }}； 优先级
        {{ selected?.matchPriority ?? 0 }}；{{
          selected?.defaultDefinition ? '作为默认兜底流程' : '按匹配条件选择'
        }}。
        {{ selected?.matchExpression ? `匹配条件：${selected.matchExpression}` : '未设置额外条件' }}
      </p>
      <ul>
        <li
          v-for="item in design.nodes.filter((node) => ['approval', 'task'].includes(node.nodeType))"
          :key="item.nodeKey"
        >
          <strong>{{ item.title }}</strong> · {{ participantSummary(item.participantPolicyText) }}
          <WorkflowParticipantEditor
            :value="item.participantPolicyText"
            :http="context.http"
            :fields="fields"
            disabled
          />
          <p v-if="item.participantPolicyText?.includes('INITIATOR_SELF')">
            此节点由提交人本人办理，请确认符合业务要求。
          </p>
        </li>
      </ul>
      <div v-for="branch in design.nodes.filter((item) => item.nodeType === 'branch')" :key="branch.nodeKey">
        <strong
          >{{ branch.title }} ·
          {{ branch.routeMode === 'manual' ? '人工单选' : '自动判定（全部命中出口）' }}</strong
        >
        <p v-if="branch.routeMode === 'manual'">
          由
          {{
            design.nodes.find((item) => item.nodeKey === branch.selectorNodeKey)?.title ?? '未配置选择节点'
          }}
          的实际办理人选路；{{ branch.requireManualSelectionReason ? '必须填写原因' : '原因选填' }}。
        </p>
        <ul>
          <li
            v-for="link in design.links.filter((item) => item.sourceNodeKey === branch.nodeKey)"
            :key="link.routeKey"
          >
            {{ link.title ?? '出口' }}：{{
              link.defaultRoute ? '默认兜底' : link.conditionExpression || '无条件（始终命中）'
            }}
          </li>
        </ul>
        <p>汇聚：{{ branchConvergenceSummary(branch) }}，只计算有效出口。</p>
      </div>
      <WorkflowDiagram :nodes="design.nodes" :routes="design.links" />
      <template #operation
        ><UiButton type="primary" :loading="busy" @click="publish">确认发布</UiButton></template
      >
    </RecordDetailDrawer>
  </section>
</template>
<style scoped>
.workflow-configuration {
  padding: 12px;
  height: 100%;
  overflow: auto;
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
  grid-template-columns: repeat(2, minmax(180px, 1fr));
  gap: 14px;
  max-width: 850px;
}
.property-form h3 {
  grid-column: 1/-1;
}
.property-form p {
  grid-column: 1/-1;
}
label {
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
</style>
