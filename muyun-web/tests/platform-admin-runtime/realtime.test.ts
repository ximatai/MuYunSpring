import { afterEach, expect, it, vi } from 'vitest';

const realtimeClient = {
  connect: vi.fn(async () => undefined),
  disconnect: vi.fn(async () => undefined),
  publish: vi.fn(),
  state: vi.fn(() => 'idle'),
  subscribe: vi.fn(() => ({ unsubscribe: vi.fn() })),
};
const createRealtimeClient = vi.fn(() => realtimeClient);
const unsubscribe = vi.fn();

vi.mock('@muyun/web-core', () => ({
  createDataChangeDispatcher: () => ({ subscribe: vi.fn(), dispatch: vi.fn() }),
  createRealtimeClient,
  connectRealtimeBusinessEvents: () => ({ unsubscribe }),
  connectRealtimeBusinessNotifications: () => ({ unsubscribe }),
  connectRealtimeDataChanges: () => ({ unsubscribe }),
  connectRealtimeUserNotifications: () => ({ unsubscribe }),
  moduleDataChangeChannel: (moduleAlias: string) => moduleAlias,
  sessionActivityCommand: 'session-activity',
}));

const { connectAppRealtime, disconnectAppRealtime } = await import('@/platform-admin-runtime/realtime.ts');

afterEach(async () => {
  await disconnectAppRealtime();
  vi.clearAllMocks();
});

it('owns one realtime connection, forwards consumer runtime configuration, and releases it idempotently', async () => {
  const connection = connectAppRealtime({
    baseUrl: 'https://api.example.test',
    token: 'consumer-token',
  });

  expect(createRealtimeClient).toHaveBeenCalledWith(
    expect.objectContaining({
      baseUrl: 'https://api.example.test',
      token: 'consumer-token',
    }),
  );
  expect(() => connectAppRealtime()).toThrow('App realtime is already connected');

  await connection.disconnect();
  await connection.disconnect();

  expect(realtimeClient.disconnect).toHaveBeenCalledTimes(1);
  expect(unsubscribe).toHaveBeenCalledTimes(4);
  expect(() => connectAppRealtime()).not.toThrow();
});
