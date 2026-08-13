import type { HttpClient } from '../web-core/http';
import { configureUserPreferenceBackend } from '../web-core/userPreferences';

/**
 * Connects the consumer App's authenticated HTTP factory to the platform's
 * account-scoped preference endpoints. Consumers retain ownership of their
 * authentication lifecycle without depending on web-core's internal bridge.
 */
export function configureUserPreferencePersistence(httpFactory: () => HttpClient) {
  configureUserPreferenceBackend({
    load: async (key) => {
      const response = await httpFactory().request<{ valueJson?: string } | undefined>({
        path: `/platform.user-preference/${encodeURIComponent(key)}`,
        query: { clientType: 'WEB' },
      });
      return response?.valueJson;
    },
    save: (key, valueJson) =>
      httpFactory().request({
        method: 'POST',
        path: `/platform.user-preference/${encodeURIComponent(key)}`,
        body: { clientType: 'WEB', valueJson },
      }),
    remove: (key) =>
      httpFactory().request({
        method: 'DELETE',
        path: `/platform.user-preference/${encodeURIComponent(key)}`,
        query: { clientType: 'WEB' },
      }),
  });
}
