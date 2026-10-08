<script setup lang="ts">
import { computed } from 'vue';
import type { WorkflowEvent, WorkflowNode } from '@muyun/web-contracts';
import { UiCheckbox } from '@muyun/vue-ui-antdv';
import { workflowTitle } from './workflowPresentation';

defineOptions({ name: 'WorkflowTimeline' });
const props = defineProps<{ events: WorkflowEvent[]; nodes: WorkflowNode[] }>();
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
</script>
<template>
  <h4>办理时间线</h4>
  <UiCheckbox v-model:checked="showTechnicalHistory">展开完整技术审计</UiCheckbox>
  <ol>
    <li v-for="event in visibleEvents" :key="event.id">
      {{ event.occurredAt ? new Date(event.occurredAt).toLocaleString() : '' }} ·
      {{ workflowTitle(event.actionCode ?? event.eventType) }} ·
      {{ nodes.find((node) => node.id === event.nodeInstanceId)?.nodeTitle ?? '' }} ·
      {{ event.operatorTitle ?? event.operatorId ?? '' }} ·
      {{ showTechnicalHistory ? (event.message ?? eventReason(event)) : eventReason(event) }}
    </li>
  </ol>
</template>
