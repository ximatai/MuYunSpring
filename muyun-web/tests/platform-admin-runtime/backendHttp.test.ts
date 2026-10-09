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

it('dispatches committed HTTP facts once across the HTTP and realtime paths without changing the receipt', async () => {
  const { appDataChangeDispatcher } = await import('@/platform-admin-runtime/realtime');
  const receipt = {
    resultType: 'action',
    data: { taskId: 'task', instanceStatus: 'COMPLETED' },
    changeSetId: 'http-committed-task',
    changes: [{ type: 'record-updated', moduleAlias: 'education.purchase_request', recordId: 'r' }],
  };
  const handler = vi.fn();
  const subscription = appDataChangeDispatcher.subscribe(handler);
  try {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(receipt)));
    const response = await createBackendHttpClient({ withAuth: false }).request({
      path: '/task',
      method: 'POST',
    });
    expect(response).toEqual(receipt);
    await appDataChangeDispatcher.dispatch(receipt);
    expect(handler).toHaveBeenCalledOnce();
    expect(handler).toHaveBeenCalledWith({ changeSetId: receipt.changeSetId, changes: receipt.changes });
  } finally {
    subscription.unsubscribe();
  }
});

it('does not dispatch failed or ordinary responses, or streams', async () => {
  const { appDataChangeDispatcher } = await import('@/platform-admin-runtime/realtime');
  const handler = vi.fn();
  const subscription = appDataChangeDispatcher.subscribe(handler);
  try {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(
          Response.json(
            {
              code: 'CONFLICT',
              changeSetId: 'failed-change',
              changes: [{ type: 'record-updated', moduleAlias: 'test' }],
            },
            { status: 409 },
          ),
        )
        .mockResolvedValueOnce(Response.json({ id: 'r', title: 'Record' }))
        .mockResolvedValueOnce(new Response('data: done\n\n')),
    );
    const client = createBackendHttpClient({ withAuth: false });
    await expect(client.request({ path: '/task', method: 'POST' })).rejects.toThrow();
    expect(await client.request({ path: '/view/r' })).toEqual({ id: 'r', title: 'Record' });
    await client.stream({ path: '/stream' });
    expect(handler).not.toHaveBeenCalled();
  } finally {
    subscription.unsubscribe();
  }
});
