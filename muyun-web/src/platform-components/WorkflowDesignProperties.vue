<script setup lang="ts">
import { computed } from 'vue';
import type { HttpClient } from '@muyun/web-core';
import type {
  WorkflowDesign,
  WorkflowNode,
  WorkflowRoute,
  WorkflowConfigurationCatalog,
} from '@muyun/web-contracts';
import { UiButton, UiInput, UiSelect, UiCheckbox } from '@muyun/vue-ui-antdv';
import WorkflowParticipantEditor from './WorkflowParticipantEditor.vue';
import WorkflowBusinessTaskEditor from './WorkflowBusinessTaskEditor.vue';
import FormulaExpressionEditor from './FormulaExpressionEditor.vue';
import { workflowTitle } from './workflowPresentation';

defineOptions({ name: 'WorkflowDesignProperties' });
const props = defineProps<{
  design: WorkflowDesign;
  nodeKey: string;
  routeKey: string;
  editable: boolean;
  http: HttpClient;
  moduleAlias: string;
  fields: readonly { name: string; label: string; valueType?: string; referenceModule?: string }[];
  catalog: WorkflowConfigurationCatalog;
  actions: readonly { value: string; label: string }[];
}>();
const emit = defineEmits<{
  'update-node': [patch: Partial<WorkflowNode>];
  'update-route': [patch: Partial<WorkflowRoute>];
  'select-route': [key: string];
  'remove-node': [];
  'remove-route': [];
  'append-branch-path': [];
  'delete-branch': [];
}>();
const node = computed(() => props.design.nodes.find((item) => item.nodeKey === props.nodeKey));
const route = computed(() => props.design.links.find((item) => item.routeKey === props.routeKey));
function updateNode(patch: Partial<WorkflowNode>) {
  if (props.editable) emit('update-node', patch);
}
function updateRoute(patch: Partial<WorkflowRoute>) {
  if (props.editable) emit('update-route', patch);
}
const typeOptions = ['approval', 'task', 'milestone'].map((value) => ({
  value,
  label: workflowTitle(value),
}));
const nodeOptions = computed(() =>
  props.design.nodes.map((item) => ({ value: item.nodeKey, label: item.title ?? item.nodeKey })),
);
const branchRoutes = computed(() =>
  props.design.links.filter((link) => link.sourceNodeKey === node.value?.nodeKey),
);
const routeSource = computed(() =>
  props.design.nodes.find((item) => item.nodeKey === route.value?.sourceNodeKey),
);
const selectorNodeOptions = computed(() =>
  props.design.nodes
    .filter((item) => ['start', 'approval', 'task'].includes(item.nodeType))
    .map((item) => ({ value: item.nodeKey, label: item.title ?? item.nodeKey })),
);
</script>
<template>
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
      :http="http"
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
              (option) => design.nodes.find((item) => item.nodeKey === option.value)?.nodeType === 'converge',
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
        <UiButton @click="emit('select-route', link.routeKey)"
          >配置出口：{{ link.title ?? link.routeKey }}</UiButton
        >
        <span>{{ link.defaultRoute ? '默认出口' : link.conditionExpression || '无条件（始终命中）' }}</span>
      </div>
      <UiButton :disabled="!editable" @click="emit('append-branch-path')">追加分支出口</UiButton>
      <UiButton danger :disabled="!editable" @click="emit('delete-branch')">删除整个分支</UiButton>
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
        :actions="actions"
        :associations="catalog.associations.map((item) => ({ value: item.id, label: item.title }))"
        :disabled="!editable"
        @update:value="updateNode({ nodeConfigText: $event })"
      />
    </template>
    <UiButton
      danger
      :disabled="!editable || ['start', 'end', 'branch', 'converge'].includes(node.nodeType)"
      @click="emit('remove-node')"
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
    <UiButton danger :disabled="!editable" @click="emit('remove-route')">删除连线</UiButton>
  </div>
</template>
<style scoped>
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
</style>
