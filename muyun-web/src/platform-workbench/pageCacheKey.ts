import type { RouteLocationNormalizedLoaded } from 'vue-router';

export function pageCacheKey(route: RouteLocationNormalizedLoaded): string {
  return `${String(route.name)}:${String(route.query.InstanceKey ?? 'default')}`;
}
