<script setup lang="ts">
import type { FormulaExpressionTools } from './formulaExpressionTools';
import { useLanguageContext } from '@muyun/web-core';
import { computed } from 'vue';
import type { HttpClient } from '@muyun/web-core';
import type {
  WorkflowDesign,
  WorkflowNode,
  WorkflowRoute,
  WorkflowConfigurationCatalog,
} from '@muyun/web-contracts';
import { UiButton, UiInput, UiSelect, UiCheckbox, UiRadioGroup } from '@muyun/vue-ui-antdv';
import WorkflowParticipantEditor from './WorkflowParticipantEditor.vue';
import WorkflowBusinessTaskEditor from './WorkflowBusinessTaskEditor.vue';
import FormulaExpressionEditor from './FormulaExpressionEditor.vue';
import {
  workflowNodeAccent,
  workflowNodeKind,
  workflowTitle as localizedWorkflowTitle,
} from './workflowPresentation';

const workflowLanguage = useLanguageContext();
function workflowTitle(value?: string) {
  return localizedWorkflowTitle(value, workflowLanguage);
}
defineOptions({ name: 'WorkflowDesignProperties' });
const props = defineProps<{
  conditionTools?: FormulaExpressionTools;
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
  'append-branch-path': [];
}>();
const node = computed(() => props.design.nodes.find((item) => item.nodeKey === props.nodeKey));
const route = computed(() => props.design.links.find((item) => item.routeKey === props.routeKey));
function updateNode(patch: Partial<WorkflowNode>) {
  if (props.editable) emit('update-node', patch);
}
function updateRoute(patch: Partial<WorkflowRoute>) {
  if (props.editable) emit('update-route', patch);
}
const nodeKind = computed(() => (node.value ? workflowNodeKind(node.value) : ''));
const typeOptions = computed(() =>
  ['approval', 'notice', 'task', 'milestone'].map((value) => ({
    value,
    label: workflowTitle(value),
    color: workflowNodeAccent(value),
    description: workflowLanguage.t(
      `platform.workflow-node-type.${value}-description`,
      {
        approval: '由参与人审批',
        notice: '知会参与人，流程继续',
        task: '执行业务任务',
        milestone: '标记审批完成',
      }[value] ?? '',
    ),
  })),
);
function changeType(value: string) {
  if (!props.editable || !node.value || value === nodeKind.value) return;
  if (!['approval', 'notice', 'task', 'milestone'].includes(value)) return;
  updateNode({
    nodeType: value === 'notice' ? 'approval' : value,
    approvalMode: value === 'notice' ? 'notice' : value === 'approval' ? 'all' : undefined,
    approvalRatio: undefined,
    autoApproveSameUser: false,
    allowReject: value === 'approval',
    requireRejectReason: value === 'approval',
    allowRejectReturnToMe: value === 'approval',
    allowRollback: value === 'approval',
    requireRollbackReason: value === 'approval',
    allowAddSign: value === 'approval',
    warningDurationMinutes: undefined,
    overtimeDurationMinutes: undefined,
    taskDefinitionId: value === 'task' ? node.value.taskDefinitionId : undefined,
    nodeConfigText: value === 'task' ? node.value.nodeConfigText : undefined,
    milestoneType: value === 'milestone' ? 'approval_completed' : undefined,
  });
}
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
    .filter((item) => ['start', 'approval', 'task'].includes(workflowNodeKind(item)))
    .map((item) => ({ value: item.nodeKey, label: item.title ?? item.nodeKey })),
);
</script>
<template>
  <div v-if="node" class="property-form">
    <label class="workflow-field"
      >{{ workflowLanguage.t('platform.screen.WorkflowDesignProperties.node-name', '节点名称')
      }}<UiInput :value="node.title" :disabled="!editable" @update:value="updateNode({ title: $event })"
    /></label>
    <div v-if="['approval', 'task', 'milestone'].includes(node.nodeType)" class="workflow-field">
      <span>{{ workflowLanguage.t('platform.screen.WorkflowDesignProperties.node-type', '节点类型') }}</span>
      <UiRadioGroup
        appearance="cards"
        :value="nodeKind"
        :options="typeOptions"
        :disabled="!editable"
        :aria-label="workflowLanguage.t('platform.screen.WorkflowDesignProperties.node-type', '节点类型')"
        @update:value="changeType"
      />
    </div>
    <WorkflowParticipantEditor
      v-if="['approval', 'task'].includes(node.nodeType)"
      :value="node.participantPolicyText"
      :http="http"
      :fields="fields"
      :disabled="!editable"
      @update:value="updateNode({ participantPolicyText: $event })"
    />
    <p v-if="nodeKind === 'notice'">
      {{
        workflowLanguage.t(
          'platform.workflow-node-type.notice-help',
          '参与人收到知会后可标记已阅，流程不会等待。',
        )
      }}
    </p>
    <template v-if="nodeKind === 'approval'"
      ><label class="workflow-field"
        >{{ workflowLanguage.t('platform.screen.WorkflowDesignProperties.approval-rule', '审批规则')
        }}<UiSelect
          :value="node.approvalMode"
          :options="
            ['all', 'any', 'ratio'].map((value) => ({
              value,
              label: workflowTitle(value),
            }))
          "
          :disabled="!editable"
          @update:value="updateNode({ approvalMode: String($event) })" /></label
      ><label class="workflow-field" v-if="node.approvalMode === 'ratio'"
        >{{ workflowLanguage.t('platform.screen.WorkflowDesignProperties.approval-ratio', '通过比例 %')
        }}<UiInput
          :value="node.approvalRatio ?? 100"
          type="number"
          :disabled="!editable"
          @update:value="updateNode({ approvalRatio: Number($event) })" /></label
      ><UiCheckbox
        :checked="node.autoApproveSameUser"
        :disabled="!editable"
        @update:checked="updateNode({ autoApproveSameUser: $event })"
        >{{
          workflowLanguage.t(
            'platform.screen.WorkflowDesignProperties.automatically-approve-consecutive-tasks-for-the-same',
            '同一办理人连续审批自动通过',
          )
        }}</UiCheckbox
      ><UiCheckbox
        :checked="node.allowReject"
        :disabled="!editable"
        @update:checked="updateNode({ allowReject: $event })"
        >{{
          workflowLanguage.t('platform.screen.WorkflowDesignProperties.allow-rejection', '允许驳回')
        }}</UiCheckbox
      ><UiCheckbox
        :checked="node.requireRejectReason"
        :disabled="!editable"
        @update:checked="updateNode({ requireRejectReason: $event })"
        >{{
          workflowLanguage.t(
            'platform.screen.WorkflowDesignProperties.require-a-rejection-reason',
            '驳回必须填写原因',
          )
        }}</UiCheckbox
      ><UiCheckbox
        :checked="node.allowRejectReturnToMe"
        :disabled="!editable"
        @update:checked="updateNode({ allowRejectReturnToMe: $event })"
        >{{
          workflowLanguage.t(
            'platform.screen.WorkflowDesignProperties.allow-resubmission-to-this-approver',
            '允许重提回到本审批人',
          )
        }}</UiCheckbox
      ><UiCheckbox
        :checked="node.allowRollback"
        :disabled="!editable"
        @update:checked="updateNode({ allowRollback: $event })"
        >{{
          workflowLanguage.t('platform.screen.WorkflowDesignProperties.allow-rollback', '允许回退')
        }}</UiCheckbox
      ><UiCheckbox
        :checked="node.allowAddSign"
        :disabled="!editable"
        @update:checked="updateNode({ allowAddSign: $event })"
        >{{
          workflowLanguage.t(
            'platform.screen.WorkflowDesignProperties.allow-additional-approvers',
            '允许加签',
          )
        }}</UiCheckbox
      ><label class="workflow-field"
        >{{
          workflowLanguage.t(
            'platform.screen.WorkflowDesignProperties.warning-duration-in-minutes',
            '预警时长（分钟）',
          )
        }}<UiInput
          :value="node.warningDurationMinutes"
          type="number"
          :disabled="!editable"
          @update:value="
            updateNode({ warningDurationMinutes: $event ? Number($event) : undefined })
          " /></label
      ><label class="workflow-field"
        >{{
          workflowLanguage.t(
            'platform.screen.WorkflowDesignProperties.overdue-duration-in-minutes',
            '超时时长（分钟）',
          )
        }}<UiInput
          :value="node.overtimeDurationMinutes"
          type="number"
          :disabled="!editable"
          @update:value="
            updateNode({ overtimeDurationMinutes: $event ? Number($event) : undefined })
          " /></label
    ></template>
    <template v-if="node.nodeType === 'branch'"
      ><label class="workflow-field"
        >{{ workflowLanguage.t('platform.screen.WorkflowDesignProperties.route-selection-mode', '选路模式')
        }}<UiSelect
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
      ><label class="workflow-field"
        >{{
          workflowLanguage.t(
            'platform.screen.WorkflowDesignProperties.paired-convergence-node',
            '配对汇聚节点',
          )
        }}<UiSelect
          :value="node.convergeNodeKey"
          :options="
            nodeOptions.filter(
              (option) => design.nodes.find((item) => item.nodeKey === option.value)?.nodeType === 'converge',
            )
          "
          :disabled="true" /></label
      ><label class="workflow-field" v-if="node.routeMode === 'manual'"
        >{{
          workflowLanguage.t('platform.screen.WorkflowDesignProperties.route-selector-node', '路径选择节点')
        }}<UiSelect
          :value="node.selectorNodeKey"
          :options="selectorNodeOptions"
          :disabled="!editable"
          @update:value="updateNode({ selectorNodeKey: String($event) })" /></label
      ><UiCheckbox
        v-if="node.routeMode === 'manual'"
        :checked="node.requireManualSelectionReason"
        :disabled="!editable"
        @update:checked="updateNode({ requireManualSelectionReason: $event })"
        >{{
          workflowLanguage.t(
            'platform.screen.WorkflowDesignProperties.require-a-route-selection-reason',
            '路径选择必须填写原因',
          )
        }}</UiCheckbox
      >
      <p>
        {{
          node.routeMode === 'manual'
            ? workflowLanguage.t(
                'platform.workflow-config.manual-help',
                '由指定前序节点的实际办理人单选一条出口。条件与默认标记只提供建议，不限制人工选择。',
              )
            : workflowLanguage.t(
                'platform.workflow-config.auto-help',
                '所有命中的非默认出口同时生效；未配置条件表示始终命中。只有全部未命中时才启用默认出口；无兜底则拒绝流转。',
              )
        }}
      </p>
      <div v-for="link in branchRoutes" :key="link.routeKey" class="branch-exit">
        <UiButton @click="emit('select-route', link.routeKey)">{{
          workflowLanguage.t('platform.workflow-config.configure-exit', '配置出口：{title}', {
            title: link.title ?? link.routeKey,
          })
        }}</UiButton>
        <span>{{
          link.defaultRoute
            ? workflowLanguage.t('platform.workflow-config.default-exit', '默认出口')
            : link.conditionExpression ||
              workflowLanguage.t('platform.workflow-config.unconditional', '无条件（始终命中）')
        }}</span>
      </div>
      <UiButton :disabled="!editable" @click="emit('append-branch-path')">{{
        workflowLanguage.t('platform.screen.WorkflowDesignProperties.add-branch-route', '增加分支路径')
      }}</UiButton>
    </template>
    <template v-if="node.nodeType === 'converge'"
      ><label class="workflow-field"
        >{{ workflowLanguage.t('platform.screen.WorkflowDesignProperties.convergence-rule', '汇聚规则')
        }}<UiSelect
          :value="node.convergeMode ?? 'all'"
          :options="[
            { value: 'all', label: workflowLanguage.t('platform.workflow-config.all-arrive', '全部到达') },
            { value: 'any', label: workflowLanguage.t('platform.workflow-config.any-arrive', '任一到达') },
            {
              value: 'ratio',
              label: workflowLanguage.t('platform.workflow-config.ratio-arrive', '比例到达'),
            },
          ]"
          :disabled="!editable"
          @update:value="updateNode({ convergeMode: String($event) })" /></label
      ><label class="workflow-field" v-if="node.convergeMode === 'ratio'"
        >{{ workflowLanguage.t('platform.screen.WorkflowDesignProperties.convergence-ratio', '汇聚比例 %')
        }}<UiInput
          :value="node.convergeRatio ?? 100"
          type="number"
          :disabled="!editable"
          @update:value="updateNode({ convergeRatio: Number($event) })"
      /></label>
      <p>
        {{
          workflowLanguage.t(
            'platform.screen.WorkflowDesignProperties.only-effective-routes-count-once-the-any',
            '只统计本次实际生效的出口；任一或比例达成后，剩余未完成路径及其待办自动取消。',
          )
        }}
      </p></template
    >
    <template v-if="node.nodeType === 'milestone'"
      ><label class="workflow-field"
        >{{ workflowLanguage.t('platform.screen.WorkflowDesignProperties.milestone', '里程碑')
        }}<UiSelect
          :value="node.milestoneType ?? 'approval_completed'"
          :options="[
            {
              value: 'approval_completed',
              label: workflowLanguage.t('platform.workflow.approval_completed', '审批完成'),
            },
          ]"
          :disabled="!editable"
          @update:value="updateNode({ milestoneType: String($event) })" /></label
    ></template>
    <template v-if="node.nodeType === 'task'">
      <label class="workflow-field property-section"
        >{{
          workflowLanguage.t(
            'platform.screen.WorkflowDesignProperties.use-a-configured-task-definition',
            '引用已配置任务定义',
          )
        }}<UiSelect
          :options="catalog.tasks.map((item) => ({ value: item.id, label: item.title }))"
          show-search
          :value="node.taskDefinitionId"
          :disabled="!editable"
          @update:value="updateNode({ taskDefinitionId: $event ? String($event) : undefined })"
      /></label>
      <WorkflowBusinessTaskEditor
        :condition-tools="conditionTools"
        class="property-section"
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
  </div>
  <div v-if="route" class="property-form">
    <label class="workflow-field"
      >{{ workflowLanguage.t('platform.screen.WorkflowDesignProperties.route-name', '路径名称')
      }}<UiInput
        :value="route.title"
        :disabled="!editable"
        @update:value="updateRoute({ title: $event })" /></label
    ><label class="workflow-field"
      >{{ workflowLanguage.t('platform.screen.WorkflowDesignProperties.source', '起点')
      }}<UiSelect :value="route.sourceNodeKey" :options="nodeOptions" :disabled="true" /></label
    ><label class="workflow-field"
      >{{ workflowLanguage.t('platform.screen.WorkflowDesignProperties.target', '终点')
      }}<UiSelect :value="route.targetNodeKey" :options="nodeOptions" :disabled="true"
    /></label>
    <div class="workflow-field" v-if="routeSource?.nodeType === 'branch' && !route.defaultRoute">
      <span>{{
        workflowLanguage.t('platform.screen.WorkflowDesignProperties.route-condition', '出口条件')
      }}</span
      ><FormulaExpressionEditor
        :tools="conditionTools"
        boolean-result
        :fields="fields"
        :value="route.conditionExpression ?? ''"
        :disabled="!editable"
        @update:value="updateRoute({ conditionExpression: $event })"
      />
    </div>
    <UiCheckbox
      v-if="routeSource?.nodeType === 'branch'"
      :checked="route.defaultRoute"
      :disabled="!editable"
      @update:checked="updateRoute({ defaultRoute: $event })"
      >{{
        workflowLanguage.t('platform.screen.WorkflowDesignProperties.default-fallback-route', '默认兜底路径')
      }}</UiCheckbox
    >
    <p v-if="routeSource?.nodeType === 'branch'">
      {{
        route.defaultRoute
          ? workflowLanguage.t(
              'platform.workflow-config.default-help',
              '其余条件均未命中时走此出口。默认出口不配置条件，每个分支最多一个。',
            )
          : workflowLanguage.t(
              'platform.workflow-config.condition-help',
              '条件为空表示始终命中，可用于并行审批；互斥分支请使用互斥条件。',
            )
      }}
    </p>
  </div>
</template>
<style scoped>
.property-form {
  display: grid;
  align-items: start;
  grid-template-columns: minmax(0, 1fr);
  gap: 16px;
  min-width: 0;
}
.property-form :deep(.participant-rule),
.property-form :deep(.formula-expression-editor__tools) {
  grid-template-columns: minmax(0, 1fr);
}
.property-form > *,
.workflow-field {
  min-width: 0;
}
.property-section,
.branch-exit {
  grid-column: 1/-1;
}
.property-form p {
  grid-column: 1/-1;
}
.workflow-field {
  display: grid;
  gap: 6px;
}
.branch-exit {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.branch-exit span {
  flex: 1 1 180px;
  overflow-wrap: anywhere;
  color: var(--muyun-text-muted);
}
</style>
