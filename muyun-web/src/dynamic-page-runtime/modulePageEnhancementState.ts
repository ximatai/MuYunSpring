import { shallowRef } from 'vue';
import type { ModulePageEnhancement, ModulePageEnhancementRegistry } from './modulePageEnhancements';

// Keep registration lifetime independent of the UI dependency graph. Re-evaluating
// the composition/validation module must not discard already registered owners.
export const modulePageEnhancementState = {
  owners: new Map<string, readonly ModulePageEnhancement[]>(),
  registry: shallowRef<ModulePageEnhancementRegistry>(),
};
