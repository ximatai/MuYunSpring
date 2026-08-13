import { afterEach, describe, expect, it, vi } from 'vitest';
import type { HttpClient } from '@/web-core/http.ts';
import { configureUserPreferenceBackend, userPreferences } from '@/web-core/userPreferences.ts';
import { configureUserPreferencePersistence } from '@/consumer/userPreferencePersistence.ts';

describe('configureUserPreferencePersistence', () => {
  afterEach(() => configureUserPreferenceBackend(undefined));

  it('uses the account-scoped platform endpoints through the consumer HTTP factory', async () => {
    const request = vi.fn().mockResolvedValue({ valueJson: '"midnight"' });
    configureUserPreferencePersistence(() => ({ request }) as HttpClient);

    await expect(userPreferences.restore('workbench.theme-skin', 'default', { persistence: 'backend' })).resolves.toBe('midnight');
    await userPreferences.set('workbench.theme-skin', 'daylight', { persistence: 'backend' });
    await userPreferences.remove('workbench.theme-skin', { persistence: 'backend' });

    expect(request).toHaveBeenNthCalledWith(1, {
      path: '/platform.user-preference/workbench.theme-skin',
      query: { clientType: 'WEB' },
    });
    expect(request).toHaveBeenNthCalledWith(2, {
      method: 'POST',
      path: '/platform.user-preference/workbench.theme-skin',
      body: { clientType: 'WEB', valueJson: '"daylight"' },
    });
    expect(request).toHaveBeenNthCalledWith(3, {
      method: 'DELETE',
      path: '/platform.user-preference/workbench.theme-skin',
      query: { clientType: 'WEB' },
    });
  });
});
