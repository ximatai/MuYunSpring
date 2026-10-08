<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useModuleContext, createWorkflowDefinitionClient, createModuleContext } from '@muyun/web-core';
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
  WorkflowDesignProperties,
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
  createWorkflowDesign,
  createWorkflowApprovalNode,
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
const client = createWorkflowDefinitionClient(context.http, props.moduleAlias);
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
const catalog = ref<WorkflowConfigurationCatalog>({
  tasks: [],
  queries: [],
  generations: [],
  associations: [],
});
const actionOptions = ref<{ value: string; label: string }[]>([]);
async function loadCatalog(id: string) {
  catalog.value = await client.catalog(id);
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
    showSuccessMessage('流程匹配规则已保存，后续提交生效');
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
    showSuccessMessage('流程草稿已保存');
  });
}
async function validate() {
  if (dirty.value) await save();
  if (dirty.value || !version.value) return;
  await run(async () => {
    await client.validate(selected.value!.id, version.value!.id);
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
    showSuccessMessage('流程已发布，后续新申请使用该版本');
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
    !(await confirmAction({
      title: action === 'archive' ? '归档此流程？' : '停用此流程？',
      content: '此操作影响后续新申请，已有实例继续使用冻结版本。',
      danger: true,
    }))
  )
    return;
  await run(async () => {
    selected.value = await client.changeStatus(selected.value!.id, action, selected.value!.version);
    showSuccessMessage('流程状态已更新');
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
          <p v-if="frozen">此版本已冻结。修改流程请创建新版本；已有实例继续使用原版本。</p>
          <p v-if="dirty">草稿有未保存的修改</p>
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
              @update:value="selectRoute(String($event))"
            />
          </div>
          <RecordDetailDrawer
            render-mode="inline"
            :open="propertyOpen"
            :title="node?.title ? `节点属性 · ${node.title}` : '路径属性'"
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
      <WorkflowDiagram :nodes="design.nodes" :routes="design.links" :layout-json="design.layoutJson" />
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
