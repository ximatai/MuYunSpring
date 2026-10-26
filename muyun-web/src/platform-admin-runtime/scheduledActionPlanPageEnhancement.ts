import type { ModulePageEnhancement } from '@muyun/dynamic-page-runtime';
import ScheduledActionTargetFields from './job/ScheduledActionTargetFields.vue';

/** The target is a module/code pair, not a foreign record ID. Keep the standard save boundary. */
export const scheduledActionPlanPageEnhancement: ModulePageEnhancement = {
  id: 'platform-scheduled-action-targets',
  target: { moduleAlias: 'platform.scheduled_action_plan' },
  form: {
    contributions: [
      {
        key: 'scheduled-targets',
        component: ScheduledActionTargetFields,
        location: { surface: 'record-card', fieldName: 'moduleAlias', placement: 'after' },
      },
    ],
    fieldPolicies: ['moduleAlias', 'actionCode'].map((fieldName) => ({ fieldName, visible: () => false })),
  },
};
