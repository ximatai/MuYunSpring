import { inject, provide, type InjectionKey } from 'vue';
import type { PageDescriptor } from '@muyun/web-contracts';

export interface WorkbenchNavigation {
  /** Opens a page and reports whether the target host was newly created. */
  openPage(descriptor: PageDescriptor): WorkbenchPageOpenResult;
  /** Replaces one existing host by its stable tab key without changing that tab's identity. */
  replacePage(pageKey: string, descriptor: PageDescriptor): void;
  /** Closes one workbench page through the owning shell's tab-state policy. */
  closePage(pageKey: string): void;
}

export interface WorkbenchPageOpenResult {
  created: boolean;
}

const workbenchNavigationKey: InjectionKey<WorkbenchNavigation> = Symbol('workbench-navigation');

export function provideWorkbenchNavigation(navigation: WorkbenchNavigation) {
  provide(workbenchNavigationKey, navigation);
}

export function useWorkbenchNavigation() {
  return inject(workbenchNavigationKey);
}
