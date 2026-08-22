import { computed, inject, provide, type ComputedRef, type InjectionKey } from 'vue';
import { useRoute, type RouteLocationNormalizedLoaded } from 'vue-router';
import type { PageDescriptor } from '@muyun/web-contracts';

export interface PageRoute {
  path: string;
  meta: RouteLocationNormalizedLoaded['meta'];
  query: RouteLocationNormalizedLoaded['query'];
  params: RouteLocationNormalizedLoaded['params'];
  matched: Array<Pick<RouteLocationNormalizedLoaded['matched'][number], 'path'>>;
}

const pageRouteKey: InjectionKey<ComputedRef<PageRoute>> = Symbol('page-route');
const pageDescriptorKey: InjectionKey<ComputedRef<PageDescriptor | undefined>> = Symbol('page-descriptor');

/**
 * Provides the route owned by one cached page instance.
 *
 * `useRoute()` always follows the browser's active route. A page kept alive in
 * another tab must instead keep reading the route that created its own cache
 * entry, otherwise another tab's navigation can reset its local state.
 */
export function providePageRoute(route: () => PageRoute) {
  provide(pageRouteKey, computed(route));
}

/** Provides the workbench-restored descriptor for the cached page instance. */
export function providePageDescriptor(descriptor: () => PageDescriptor | undefined) {
  provide(pageDescriptorKey, computed(descriptor));
}

/** Returns the page-instance route when rendered by the workbench, otherwise the active router route. */
export function usePageRoute(): ComputedRef<PageRoute> {
  const providedRoute = inject(pageRouteKey);
  if (providedRoute) return providedRoute;
  const activeRoute = useRoute();
  return computed(() => activeRoute);
}

/** Returns the descriptor restored from the current user's workbench state, when available. */
export function usePageDescriptor(): ComputedRef<PageDescriptor | undefined> {
  return inject(pageDescriptorKey) ?? computed(() => undefined);
}
