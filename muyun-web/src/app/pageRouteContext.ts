import { computed, inject, provide, type ComputedRef, type InjectionKey } from 'vue';
import { useRoute, type RouteLocationNormalizedLoaded } from 'vue-router';

const pageRouteKey: InjectionKey<ComputedRef<RouteLocationNormalizedLoaded>> = Symbol('page-route');

/**
 * Provides the route owned by one cached page instance.
 *
 * `useRoute()` always follows the browser's active route. A page kept alive in
 * another tab must instead keep reading the route that created its own cache
 * entry, otherwise another tab's navigation can reset its local state.
 */
export function providePageRoute(route: () => RouteLocationNormalizedLoaded) {
  provide(pageRouteKey, computed(route));
}

/** Returns the page-instance route when rendered by the workbench, otherwise the active router route. */
export function usePageRoute(): ComputedRef<RouteLocationNormalizedLoaded> {
  const providedRoute = inject(pageRouteKey);
  if (providedRoute) return providedRoute;
  const activeRoute = useRoute();
  return computed(() => activeRoute);
}
