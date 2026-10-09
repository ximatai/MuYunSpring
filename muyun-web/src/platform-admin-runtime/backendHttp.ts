import { createHttpClient, resolveWebActionResult, type HttpRequestOptions } from '@muyun/web-core';
import { effectiveAuthToken } from './authSession';
import { recoverAuthentication } from './sessionRecovery';
import { appDataChangeDispatcher } from './realtime';

export function createBackendHttpClient(options: { withAuth?: boolean } = {}) {
  const currentClient = () =>
    createHttpClient({
      baseUrl: import.meta.env.VITE_MUYUN_API_BASE_URL,
      token:
        options.withAuth === false ? undefined : effectiveAuthToken(import.meta.env.VITE_MUYUN_AUTH_TOKEN),
      credentials: credentialsOf(import.meta.env.VITE_MUYUN_CREDENTIALS),
      onAuthenticationRequired:
        options.withAuth === false ? undefined : (error, token) => recoverAuthentication(error, token),
    });
  // Resolve authentication once per request, including streams. Long-lived clients
  // survive login, while a late 401 must still identify the token it actually used.
  return {
    async request<T>(request: HttpRequestOptions) {
      const response = await currentClient().request<T>(request);
      const result = resolveWebActionResult(response);
      if (result.changeSetId) {
        void appDataChangeDispatcher.dispatch({ changeSetId: result.changeSetId, changes: result.changes });
      }
      return response;
    },
    stream(request: HttpRequestOptions) {
      return currentClient().stream(request);
    },
  };
}

function credentialsOf(value: string | undefined) {
  return value === 'include' || value === 'omit' || value === 'same-origin' ? value : undefined;
}
