import type { ComputedRef, InjectionKey } from 'vue';

/** Reference drawers inherit the business scope of the page that owns their transport. */
export const assistantBusinessScopeKey: InjectionKey<ComputedRef<string>> =
  Symbol('assistant-business-scope');
