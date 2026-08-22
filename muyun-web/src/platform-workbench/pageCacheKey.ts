import type { RouteLocationNormalizedLoaded } from 'vue-router';

export function pageCacheKey(route: RouteLocationNormalizedLoaded, pageInstanceKey?: string): string {
  return `${String(route.name)}:${pageInstanceKey ?? 'default'}`;
}
