import type { BusinessNotificationSession, RealtimeClientOptions } from '@muyun/web-core';
import type { WebBusinessNotification } from '@muyun/web-contracts';
import { afterEach, expect, it, vi } from 'vitest';

const realtimeClient = {
  connect: vi.fn(async () => undefined),
  disconnect: vi.fn(async () => undefined),
  publish: vi.fn(),
  state: vi.fn(() => 'idle'),
  subscribe: vi.fn(() => ({ unsubscribe: vi.fn() })),
};
const createRealtimeClient = vi.fn<(options: RealtimeClientOptions) => typeof realtimeClient>(
  () => realtimeClient,
);
let notificationHandler: ((notification: WebBusinessNotification) => void) | undefined;
const unsubscribe = vi.fn();

vi.mock('@muyun/web-core', () => ({
  createDataChangeDispatcher: () => ({ subscribe: vi.fn(), dispatch: vi.fn() }),
  createRealtimeClient,
  connectRealtimeBusinessEvents: () => ({ unsubscribe }),
  connectRealtimeBusinessNotifications: (
    _client: unknown,
    handler: (notification: WebBusinessNotification) => void,
  ) => {
    notificationHandler = handler;
    return { unsubscribe };
  },
  connectRealtimeDataChanges: () => ({ unsubscribe }),
  connectRealtimeUserNotifications: () => ({ unsubscribe }),
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

it('connects without binding a shared record topic', () => {
  connectAppRealtime({ token: 'consumer-token' });
  expect(realtimeClient.subscribe).not.toHaveBeenCalled();
});

it('binds notification recovery to connected state, transport loss, and identity disposal', async () => {
  const session: BusinessNotificationSession = {
    register: vi.fn(),
    receive: vi.fn(),
    dismiss: vi.fn(),
    snapshot: vi.fn(() => []),
    recover: vi.fn(async () => {}),
    cancelRecovery: vi.fn(),
    dispose: vi.fn(),
  };
  const connection = connectAppRealtime({ token: 'identity-a', notificationSession: session });
  const onStateChange = createRealtimeClient.mock.lastCall![0].onStateChange!;
  onStateChange('connected');
  onStateChange('reconnecting');
  onStateChange('connected');
  expect(session.recover).toHaveBeenCalledTimes(2);
  expect(session.cancelRecovery).toHaveBeenCalledOnce();
  const notification: WebBusinessNotification = {
    id: 'one',
    code: 'sample',
    title: 'Title',
    content: 'Content',
    dismissible: true,
    actions: [],
  };
  notificationHandler!(notification);
  expect(session.receive).toHaveBeenCalledWith(notification);
  onStateChange('unauthorized');
  expect(session.dispose).toHaveBeenCalledOnce();
  await connection.disconnect();
  expect(session.dispose).toHaveBeenCalledTimes(2);
});
