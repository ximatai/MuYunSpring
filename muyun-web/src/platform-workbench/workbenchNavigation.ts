import { inject, provide, type InjectionKey } from 'vue';
import type { PageDescriptor, RouteQueryValue } from '@muyun/web-contracts';

export interface OpenRouteOptions {
  /** Opens an independent internal tab instance without changing the public URL. */
  newInstance?: boolean;
  tabTitle?: string;
  query?: Record<string, RouteQueryValue>;
}

export interface WorkbenchNavigation {
  openRoute(path: string, options?: OpenRouteOptions): WorkbenchPageOpenResult;
  replaceRoute(path: string, options?: OpenRouteOptions): WorkbenchPageOpenResult;
  closeCurrentTab(fallbackPath: string): WorkbenchPageOpenResult;
  /** Opens a page and reports whether the target host was newly created. */
  openPage(descriptor: PageDescriptor): WorkbenchPageOpenResult;
  /** Replaces one existing host by its stable tab key without changing that tab's identity. */
  replacePage(pageKey: string, descriptor: PageDescriptor): void;
  /** Closes one workbench page through the owning shell's tab-state policy. */
  closePage(pageKey: string): void;
  /** Updates one page title without writing display data to the URL. */
  setTabName(instanceKey: string, name: string): void;
}

export interface WorkbenchPageOpenResult {
  created: boolean;
}

/** Builds a public workbench address. Page-instance identity never enters the address. */
export function routeUrlWithOpenOptions(path: string, options: OpenRouteOptions = {}): string {
  const url = new URL(path, 'http://muyun.local');
  url.searchParams.delete('InstanceKey');
  for (const [key, value] of Object.entries(options.query ?? {})) {
    if (key === 'InstanceKey') continue;
    url.searchParams.delete(key);
    if (Array.isArray(value)) {
      value.forEach((item) => {
        if (item !== null && item !== undefined) url.searchParams.append(key, String(item));
      });
    } else if (value !== null && value !== undefined) {
      url.searchParams.set(key, String(value));
    }
  }
  return `${url.pathname}${url.search}${url.hash}`;
}

const workbenchNavigationKey: InjectionKey<WorkbenchNavigation> = Symbol('workbench-navigation');

export function provideWorkbenchNavigation(navigation: WorkbenchNavigation) {
  provide(workbenchNavigationKey, navigation);
}

export function useWorkbenchNavigation() {
  return inject(workbenchNavigationKey, undefined);
}
