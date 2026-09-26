import type { ConstructionPlanSnapshot } from '@muyun/web-contracts';
import { AssistantCapabilityUsageError } from '@muyun/web-core';

export interface ConstructionPlanState {
  saved?: ConstructionPlanSnapshot;
  generation: number;
  dirty: boolean;
  editing: boolean;
}

/** Bind each construction confirmation to the same reviewed plan and local edit generation. */
export function requireConfirmedConstructionPlan(current: () => ConstructionPlanState) {
  const { saved, generation, dirty, editing } = current();
  if (!saved || dirty || editing) throw new AssistantCapabilityUsageError('请先确认最新需求方案');
  const { planId, revision } = saved;
  return {
    saved,
    generation,
    isCurrent: () => {
      const state = current();
      return (
        state.saved?.planId === planId &&
        state.saved.revision === revision &&
        state.generation === generation &&
        !state.dirty &&
        !state.editing
      );
    },
  };
}
