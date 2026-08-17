import { inject, provide, type InjectionKey } from 'vue';
import type { PageDescriptor, RouteQueryValue } from '@muyun/web-contracts';

export interface OpenRouteOptions {
  /** Forces a new page instance when the supplied URL already contains an InstanceKey. */
  newInstance?: boolean;
  /** Visible tab title established when the page instance is created; it is never written to the URL. */
  tabTitle?: string;
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
  /** Updates the visible title of one explicit page instance without changing its address. */
  setTabName(instanceKey: string, name: string): void;
}

export interface WorkbenchPageOpenResult {
  created: boolean;
}

/**
 * Adds supplied address values and makes every workbench navigation address identify one page instance.
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

  if (options.newInstance || !url.searchParams.get('InstanceKey')) {
    url.searchParams.set('InstanceKey', crypto.randomUUID());
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
