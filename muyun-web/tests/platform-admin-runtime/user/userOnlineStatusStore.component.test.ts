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
