<script setup lang="ts">
import { computed } from 'vue';
import type { WorkflowEvent, WorkflowNode, WorkflowTask } from '@muyun/web-contracts';
import { UiCheckbox } from '@muyun/vue-ui-antdv';
import { workflowTitle } from './workflowPresentation';

defineOptions({ name: 'WorkflowTimeline' });
const props = withDefaults(
  defineProps<{ events: WorkflowEvent[]; nodes: WorkflowNode[]; tasks?: WorkflowTask[] }>(),
  { tasks: () => [] },
);
const showTechnicalHistory = defineModel<boolean>('technical', { default: false });
function eventReason(event: WorkflowEvent) {
  if (event.reason) return event.reason;
  try {
    const reason = JSON.parse(event.payloadText ?? '{}').reason;
    if (reason) return String(reason);
  } catch {
    /* malformed legacy payload */
  }
  return [
    'task_completed',
    'task_rejected',
    'node_rolled_back',
    'task_resubmitted',
    'instance_revoked',
    'instance_terminated',
    'instance_reset',
    'task_transferred',
    'add_sign',
  ].includes(event.eventType.toLowerCase()) &&
    event.message &&
    event.message !==
      (
        {
          task_completed: 'workflow task completed',
          task_rejected: 'workflow task rejected',
          node_rolled_back: 'workflow node rolled back',
          task_resubmitted: 'workflow task resubmitted',
          task_transferred: 'workflow task transferred',
          add_sign: 'workflow runtime add sign',
          instance_revoked: 'workflow instance revoked',
          instance_terminated: 'workflow instance terminated',
          instance_reset: 'workflow instance reset',
        } as Record<string, string>
      )[event.eventType.toLowerCase()]
    ? event.message
    : '';
}
const businessEvents = computed(() =>
  props.events.filter((event) =>
    [
      'instance_started',
      'task_completed',
      'task_rejected',
      'task_resubmitted',
      'task_transferred',
      'add_sign',
      'node_rolled_back',
      'approval_completed',
      'instance_completed',
      'instance_revoked',
      'instance_reset',
      'instance_terminated',
    ].includes(event.eventType.toLowerCase()),
  ),
);
const visibleEvents = computed(() => (showTechnicalHistory.value ? props.events : businessEvents.value));
const timelineItems = computed(() =>
  visibleEvents.value.map((event) => {
    const task = event.taskId ? props.tasks.find((task) => task.id === event.taskId) : undefined;
    const nodeInstanceId = event.nodeInstanceId ?? task?.nodeInstanceId;
    const nodeKey = event.nodeKey ?? task?.nodeKey;
    const node =
      props.nodes.find((node) => Boolean(nodeInstanceId) && node.id === nodeInstanceId) ??
      props.nodes.find((node) => Boolean(nodeKey) && node.nodeKey === nodeKey);
    const reason = eventReason(event);
    const eventType = event.eventType.toLowerCase();
    return {
      ...event,
      title: workflowTitle(
        eventType.startsWith('instance_') || eventType === 'approval_completed'
          ? eventType
          : (event.actionCode ?? eventType),
      ),
      nodeTitle: node?.nodeTitle ?? node?.title,
      detail: showTechnicalHistory.value ? (event.message ?? reason) : reason,
    };
  }),
);
</script>
<template>
  <section class="workflow-timeline" aria-label="办理时间线">
    <header>
      <h4>办理时间线</h4>
      <UiCheckbox v-model:checked="showTechnicalHistory">展开完整技术审计</UiCheckbox>
    </header>
    <ol>
      <li v-for="event in timelineItems" :key="event.id">
        <div class="timeline-facts">
          <strong>{{ event.title }}</strong
          ><span v-if="event.nodeTitle">{{ event.nodeTitle }}</span
          ><span v-if="event.operatorTitle || event.operatorId">{{
            event.operatorTitle ?? event.operatorId
          }}</span
          ><time v-if="event.occurredAt" :datetime="event.occurredAt">{{
            new Date(event.occurredAt).toLocaleString()
          }}</time>
        </div>
        <p v-if="event.detail">{{ event.detail }}</p>
      </li>
    </ol>
  </section>
</template>
<style scoped>
.workflow-timeline {
  min-width: 0;
}
header,
.timeline-facts {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
}
h4 {
  margin: 0;
  margin-right: auto;
}
ol {
  list-style: none;
  padding: 0;
  margin: 12px 0 0;
}
li {
  position: relative;
  padding: 0 0 16px 20px;
  border-left: 1px solid var(--muyun-border);
  margin-left: 4px;
}
li::before {
  content: '';
  position: absolute;
  left: -4px;
  top: 6px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--muyun-primary);
}
li:last-child {
  padding-bottom: 0;
  border-left-color: transparent;
}
time {
  margin-left: auto;
  color: var(--muyun-text-muted);
  font-size: 12px;
}
p {
  margin: 8px 0 0;
  color: var(--muyun-text-muted);
  overflow-wrap: anywhere;
}
</style>
