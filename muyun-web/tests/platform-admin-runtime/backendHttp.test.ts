import { afterEach, expect, it, vi } from 'vitest';
import { createBackendHttpClient } from '@/platform-admin-runtime/backendHttp';
import { saveAuthToken } from '@/platform-admin-runtime/authSession';

vi.mock('@/platform-admin-runtime/sessionRecovery', () => ({ recoverAuthentication: vi.fn() }));
import { recoverAuthentication } from '@/platform-admin-runtime/sessionRecovery';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

it('long-lived JSON and stream clients use the current login, but late failures retain their request identity', async () => {
  const storage = new Map<string, string>();
  vi.stubGlobal('window', {
    location: { origin: 'http://localhost' },
    localStorage: {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
    },
  });
  let rejectOld!: (response: Response) => void;
  const fetcher = vi
    .fn()
    .mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          rejectOld = resolve;
        }),
    )
    .mockResolvedValueOnce(new Response('data: done\n\n'))
    .mockResolvedValueOnce(new Response('{}'));
  vi.stubGlobal('fetch', fetcher);
  saveAuthToken('old-session');
  const client = createBackendHttpClient();
  const oldRequest = client.request({ path: '/conversation' });
  saveAuthToken('new-session');
  await client.stream({ path: '/turn/stream' });
  rejectOld(new Response(JSON.stringify({ code: 'AUTH_REQUIRED', message: 'expired' }), { status: 401 }));
  await expect(oldRequest).rejects.toThrow();
  expect(recoverAuthentication).toHaveBeenCalledWith(expect.anything(), 'old-session');
  expect(fetcher.mock.calls[1]?.[1].headers.Authorization).toBe('Bearer new-session');
  await createBackendHttpClient({ withAuth: false }).request({ path: '/login' });
  expect(fetcher.mock.calls[2]?.[1].headers.Authorization).toBeUndefined();
});
