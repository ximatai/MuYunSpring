import { ref, toRaw } from 'vue';
import { normalizeError, userFacingErrorMessage, type ModuleContext } from '@muyun/web-core';
import type { QueryListRecord } from '@muyun/platform-components';
import {
  readCodeConfigurationAvailability,
  type CodeConfigurationAvailability,
} from '../views/codeConfiguration';
import { subscribeAppDataChanges } from './realtime';

type AvailabilityState = CodeConfigurationAvailability & {
  phase: 'loading' | 'ready' | 'failed';
  error?: string;
};
/** Keep model facts within their authorized page context; never share them across tenant scopes. */
const catalogs = new WeakMap<ModuleContext<QueryListRecord>, ReturnType<typeof createCatalog>>();
function createCatalog(module: ModuleContext<QueryListRecord>) {
  const states = ref<Record<string, AvailabilityState>>({});
  const revisions = new Map<string, number>();
  async function refresh(alias: string) {
    const revision = (revisions.get(alias) ?? 0) + 1;
    revisions.set(alias, revision);
    states.value[alias] = { phase: 'loading', configurable: false };
    try {
      const result = await readCodeConfigurationAvailability(module.http, alias);
      if (revisions.get(alias) === revision) states.value[alias] = { ...result, phase: 'ready' };
    } catch (cause) {
      if (revisions.get(alias) === revision)
        states.value[alias] = {
          phase: 'failed',
          configurable: false,
          error: userFacingErrorMessage(normalizeError(cause)),
        };
    }
  }
  return { states, refresh, dispose: () => revisions.clear() };
}
export function moduleCodeCatalog(module: ModuleContext<QueryListRecord>) {
  module = toRaw(module);
  let catalog = catalogs.get(module);
  if (!catalog) {
    catalog = createCatalog(module);
    catalogs.set(module, catalog);
  }
  return catalog;
}
export function activateModuleCodeCatalog(module: ModuleContext<QueryListRecord>) {
  module = toRaw(module);
  const catalog = moduleCodeCatalog(module);
  const subscription = subscribeAppDataChanges((event) => {
    if (
      event.changes.some((change) =>
        [
          'platform.module',
          'platform.metadata',
          'platform.metadata_field',
          'platform.module_metadata_relation',
          'platform.module_metadata_field',
        ].includes(change.moduleAlias),
      )
    )
      for (const alias of Object.keys(catalog.states.value)) void catalog.refresh(alias);
  });
  return () => {
    subscription.unsubscribe();
    catalog.dispose();
    catalogs.delete(module);
  };
}
export function canConfigureModuleCodes(module: ModuleContext<QueryListRecord>, alias: string) {
  const state = moduleCodeCatalog(module).states.value[alias];
  return state?.phase === 'ready' && state.configurable;
}
