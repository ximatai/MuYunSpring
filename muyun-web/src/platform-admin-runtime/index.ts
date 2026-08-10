/**
 * First-party management runtime shipped to management-oriented applications.
 *
 * It owns the platform administration route registry and the context APIs
 * required by those pages.  Application composition remains outside this
 * directory: an App supplies its startup state, navigation and HTTP factory.
 */
export { default as PlatformAdminRouteOutlet } from './PlatformAdminOutlet.vue';
export {
  isPlatformAdminRoutePage,
  platformAdminModuleRoutes,
  platformAdminRouteLayouts,
  platformAdminRoutePrefixes,
  platformAdminRoutes,
  resolvePlatformAdminRoute,
} from './platformAdminRoutes';
export type { PlatformAdminRoute } from './platformAdminRoutes';
export { provideCurrentUserContext, useCurrentUserContext } from './currentUserContext';
export { createBackendHttpClient } from './backendHttp';
// Realtime is an App-level capability: consuming applications own the shell
// lifecycle, while the platform owns the connection protocol and subscriptions.
export {
  connectAppRealtime,
  disconnectAppRealtime,
  subscribeAppBusinessEvents,
  subscribeAppDataChanges,
  subscribeAppModuleDataChanges,
} from './realtime';
export type { AppRealtimeConnection, AppRealtimeOptions } from './realtime';
export {
  clearAuthToken,
  effectiveAuthToken,
  saveAuthSessionId,
  saveAuthToken,
  storedAuthSessionId,
  storedAuthToken,
} from './authSession';
