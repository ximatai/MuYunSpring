import type { InjectionKey } from 'vue';
import type { WebActionMessage } from '@muyun/web-contracts';

export interface ModuleActivationFeedback {
  message: WebActionMessage;
}

/** Recheck committed module configuration without coupling editors to its presentation. */
export const moduleRuntimeActivationRefreshKey: InjectionKey<
  (moduleAlias: string) => Promise<ModuleActivationFeedback | undefined>
> = Symbol('module-runtime-activation-refresh');
