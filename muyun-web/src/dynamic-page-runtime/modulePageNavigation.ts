import { inject, provide, type InjectionKey } from 'vue';
import type { PageDescriptor } from '@muyun/web-contracts';
import type { ModulePageWorkspaceView, ModulePageWorkspaceViewInput } from './modulePageEnhancements';

/** Workbench bridge owned by the host, so module runtime does not depend on a concrete Workbench implementation. */
export interface ModulePageNavigation {
  openPage(descriptor: PageDescriptor): { created: boolean };
  openWorkspaceTab<TInput extends ModulePageWorkspaceViewInput>(
    view: ModulePageWorkspaceView<TInput>,
    input: TInput,
  ): void;
}

const modulePageNavigationKey: InjectionKey<ModulePageNavigation | undefined> =
  Symbol('module-page-navigation');

export function provideModulePageNavigation(navigation: ModulePageNavigation | undefined) {
  provide(modulePageNavigationKey, navigation);
}

export function useModulePageNavigation() {
  return inject(modulePageNavigationKey, undefined);
}
