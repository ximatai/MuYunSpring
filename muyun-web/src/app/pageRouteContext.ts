import { computed, inject, provide, type ComputedRef, type InjectionKey } from 'vue';
import { useRoute, type RouteLocationNormalizedLoaded } from 'vue-router';

export interface PageRoute {
  path: string;
  meta: RouteLocationNormalizedLoaded['meta'];
  query: RouteLocationNormalizedLoaded['query'];
  params: RouteLocationNormalizedLoaded['params'];
  matched: Array<Pick<RouteLocationNormalizedLoaded['matched'][number], 'path'>>;
}

const pageRouteKey: InjectionKey<ComputedRef<PageRoute>> = Symbol('page-route');

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

/** Returns the page-instance route when rendered by the workbench, otherwise the active router route. */
export function usePageRoute(): ComputedRef<PageRoute> {
  const providedRoute = inject(pageRouteKey);
  if (providedRoute) return providedRoute;
  const activeRoute = useRoute();
  return computed(() => activeRoute);
}
