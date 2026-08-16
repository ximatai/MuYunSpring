import { inject, provide, type InjectionKey } from 'vue';
import type { PageDescriptor, RouteQueryValue } from '@muyun/web-contracts';

export interface OpenRouteOptions {
  /** Creates an independent cached page instance and writes InstanceKey to the URL. */
  newInstance?: boolean;
  /** Business query values to merge into the target URL. */
  query?: Record<string, RouteQueryValue>;
}

export interface WorkbenchNavigation {
  /** Opens the default or a new independent instance of a workbench route. */
  openRoute(path: string, options?: OpenRouteOptions): WorkbenchPageOpenResult;
  /** Changes the address and content of the current tab without adding a browser history entry. */
  replaceRoute(path: string, options?: OpenRouteOptions): WorkbenchPageOpenResult;
  /** Closes the current tab, then opens or focuses the supplied fallback address. */
  closeCurrentTab(fallbackPath: string): WorkbenchPageOpenResult;
  /** Opens a page and reports whether the target host was newly created. */
  openPage(descriptor: PageDescriptor): WorkbenchPageOpenResult;
  /** Replaces one existing host by its stable tab key without changing that tab's identity. */
  replacePage(pageKey: string, descriptor: PageDescriptor): void;
  /** Updates the visible title of the current tab without changing its address. */
  setTabName(name: string): void;
}

export interface WorkbenchPageOpenResult {
  created: boolean;
}

/**
 * Adds supplied address values and decides which page marker belongs in the address.
 * Only requests for a new page create a random marker. Callers cannot provide one.
 */
export function routeUrlWithOpenOptions(path: string, options: OpenRouteOptions = {}): string {
  const url = new URL(path, 'http://muyun.local');
  for (const [key, value] of Object.entries(options.query ?? {})) {
    url.searchParams.delete(key);
    if (Array.isArray(value)) {
      value.forEach((item) => {
        if (item !== null && item !== undefined) url.searchParams.append(key, String(item));
      });
    } else if (value !== null && value !== undefined) {
      url.searchParams.set(key, String(value));
    }
  }

  if (options.newInstance) {
    url.searchParams.set('InstanceKey', crypto.randomUUID());
  } else {
    url.searchParams.delete('InstanceKey');
  }
  return `${url.pathname}${url.search}${url.hash}`;
}

const workbenchNavigationKey: InjectionKey<WorkbenchNavigation> = Symbol('workbench-navigation');

export function provideWorkbenchNavigation(navigation: WorkbenchNavigation) {
  provide(workbenchNavigationKey, navigation);
}

export function useWorkbenchNavigation() {
  return inject(workbenchNavigationKey);
}
