import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it, vi } from 'vitest';
import { provideModuleContextConfig, type HttpClient } from '@muyun/web-core';
import type { WebBusinessRealtimeEvent } from '@muyun/web-contracts';
import { useUserOnlineStatusStore } from '@/platform-admin-runtime/user/userOnlineStatusStore';

const events = vi.hoisted(() => new Set<(event: WebBusinessRealtimeEvent) => void>());
vi.mock('@/platform-admin-runtime/realtime', () => ({
  subscribeAppBusinessEvents: (handler: (event: WebBusinessRealtimeEvent) => void) => {
    events.add(handler);
    return { unsubscribe: () => events.delete(handler) };
  },
}));

function mountUsers(http: HttpClient, userIds = ['one', 'two']) {
  const Cell = defineComponent({
    props: { userId: { type: String, required: true } },
    setup(props) {
      const store = useUserOnlineStatusStore();
      store.observe(props.userId);
      return () => h('span', String(store.statusOf(props.userId)?.activeSessionCount ?? '-'));
    },
  });
  return mount(
    defineComponent({
      setup() {
        provideModuleContextConfig({ http });
        return () =>
          h(
            'div',
            userIds.map((userId) => h(Cell, { userId })),
          );
      },
    }),
  );
}

it('batches cells through their host transport, refreshes events, and releases the last observer', async () => {
  const request = vi.fn().mockResolvedValue([
    { userId: 'one', activeSessionCount: 2 },
    { userId: 'two', activeSessionCount: 3 },
  ]);
  const wrapper = mountUsers({ request });
  await flushPromises();
  expect(request).toHaveBeenCalledOnce();
  expect(request).toHaveBeenCalledWith({
    method: 'POST',
    path: '/iam.user/sessions/status',
    body: { userIds: ['one', 'two'] },
  });
  expect(wrapper.text()).toBe('23');
  expect(events.size).toBe(1);
  for (const handler of events)
    handler({ type: 'iam.user.session.collectionChanged', moduleAlias: 'iam.user', recordId: 'one' });
  await flushPromises();
  expect(request).toHaveBeenLastCalledWith({
    method: 'POST',
    path: '/iam.user/sessions/status',
    body: { userIds: ['one'] },
  });
  wrapper.unmount();
  expect(events.size).toBe(0);
});

it('isolates caches and pending responses across owner transports and disposed sessions', async () => {
  let finishOld!: (value: unknown) => void;
  const oldRequest = vi.fn().mockImplementation(
    () =>
      new Promise((resolve) => {
        finishOld = resolve;
      }),
  );
  const oldHttp = { request: oldRequest } as HttpClient;
  const old = mountUsers(oldHttp, ['same-user']);
  await flushPromises();
  const otherRequest = vi.fn().mockResolvedValue([{ userId: 'same-user', activeSessionCount: 9 }]);
  const other = mountUsers({ request: otherRequest }, ['same-user']);
  await flushPromises();
  expect(other.text()).toBe('9');
  expect(old.text()).toBe('-');
  old.unmount();
  oldRequest.mockResolvedValue([{ userId: 'same-user', activeSessionCount: 4 }]);
  const replacement = mountUsers(oldHttp, ['same-user']);
  await flushPromises();
  finishOld([{ userId: 'same-user', activeSessionCount: 1 }]);
  await flushPromises();
  expect(replacement.text()).toBe('4');
  expect(other.text()).toBe('9');
  other.unmount();
  replacement.unmount();
  expect(events.size).toBe(0);
});

it('refreshes only users observed by each scoped client and batches repeated events', async () => {
  const requestA = vi.fn().mockResolvedValue([{ userId: 'tenant-a-user', activeSessionCount: 2 }]);
  const requestB = vi.fn().mockResolvedValue([{ userId: 'tenant-b-user', activeSessionCount: 3 }]);
  const pageA = mountUsers({ request: requestA }, ['tenant-a-user']);
  const pageB = mountUsers({ request: requestB }, ['tenant-b-user']);
  await flushPromises();
  requestA.mockClear();
  requestB.mockClear();

  for (const recordId of ['tenant-a-user', 'tenant-a-user', 'unobserved-user']) {
    for (const handler of events)
      handler({ type: 'iam.user.session.collectionChanged', moduleAlias: 'iam.user', recordId });
  }
  await flushPromises();

  expect(requestA).toHaveBeenCalledOnce();
  expect(requestA).toHaveBeenCalledWith({
    method: 'POST',
    path: '/iam.user/sessions/status',
    body: { userIds: ['tenant-a-user'] },
  });
  expect(requestB).not.toHaveBeenCalled();
  expect(pageA.text()).toBe('2');
  expect(pageB.text()).toBe('3');
  pageA.unmount();
  pageB.unmount();
  expect(events.size).toBe(0);
});

it('tracks observed users before their first response and resets observation with the store lifetime', async () => {
  let finishInitial!: (value: unknown) => void;
  const request = vi
    .fn()
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishInitial = resolve;
        }),
    )
    .mockResolvedValue([{ userId: 'old-user', activeSessionCount: 2 }]);
  const http = { request } as HttpClient;
  const old = mountUsers(http, ['old-user']);
  await flushPromises();
  for (const handler of events)
    handler({ type: 'iam.user.session.collectionChanged', moduleAlias: 'iam.user', recordId: 'old-user' });
  await flushPromises();
  expect(request).toHaveBeenCalledTimes(2);
  old.unmount();

  request.mockResolvedValue([{ userId: 'new-user', activeSessionCount: 4 }]);
  const replacement = mountUsers(http, ['new-user']);
  await flushPromises();
  request.mockClear();
  for (const recordId of ['old-user', 'new-user']) {
    for (const handler of events)
      handler({ type: 'iam.user.session.collectionChanged', moduleAlias: 'iam.user', recordId });
  }
  finishInitial([{ userId: 'old-user', activeSessionCount: 1 }]);
  await flushPromises();
  expect(request).toHaveBeenCalledOnce();
  expect(request).toHaveBeenCalledWith({
    method: 'POST',
    path: '/iam.user/sessions/status',
    body: { userIds: ['new-user'] },
  });
  expect(replacement.text()).toBe('4');
  replacement.unmount();
  expect(events.size).toBe(0);
});
