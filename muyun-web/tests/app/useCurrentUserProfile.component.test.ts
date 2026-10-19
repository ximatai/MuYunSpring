import { effectScope, ref } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import type { CurrentUser, CurrentUserProfile } from '@/web-contracts';
import { useCurrentUserProfile } from '@/app/useCurrentUserProfile';

function deferred() {
  let resolve!: (value: CurrentUserProfile) => void;
  let reject!: (cause: unknown) => void;
  const promise = new Promise<CurrentUserProfile>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

it('loads a profile at sign-in and shares explicit refresh and save results with the header', async () => {
  const user = ref<CurrentUser>();
  const client = {
    currentProfile: vi.fn().mockResolvedValue({ employee: { title: '张三' } }),
    updateCurrentProfile: vi.fn().mockResolvedValue({ employee: { title: '李四' } }),
  };
  const scope = effectScope();
  const state = scope.run(() =>
    useCurrentUserProfile({ currentUser: () => user.value, token: () => 'token', client }),
  )!;
  try {
    expect(client.currentProfile).not.toHaveBeenCalled();
    user.value = { userId: 'user', system: false };
    await flushPromises();
    expect(state.profile.value?.employee?.title).toBe('张三');
    expect(await state.save({ mobile: '123' })).toBe(true);
    expect(client.updateCurrentProfile).toHaveBeenCalledWith({ mobile: '123' }, 'token');
    expect(state.profile.value?.employee?.title).toBe('李四');
    await state.refresh();
    expect(state.profile.value?.employee?.title).toBe('张三');
    user.value = undefined;
    expect(state.profile.value).toBeUndefined();
  } finally {
    scope.stop();
  }
});

it('clears the previous identity immediately and ignores late responses after switching tenants or unmounting', async () => {
  const user = ref<CurrentUser>({ userId: 'user', tenantId: 'a', system: false });
  const old = deferred();
  const next = deferred();
  const pending = deferred();
  const client = {
    currentProfile: vi
      .fn()
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(next.promise)
      .mockReturnValueOnce(pending.promise),
    updateCurrentProfile: vi.fn(),
  };
  const scope = effectScope();
  const state = scope.run(() =>
    useCurrentUserProfile({ currentUser: () => user.value, token: () => 'token', client }),
  )!;
  user.value = { ...user.value, tenantId: 'b' };
  expect(state.profile.value).toBeUndefined();
  next.resolve({ employee: { title: '当前职员' } });
  await flushPromises();
  old.resolve({ employee: { title: '之前职员' } });
  await flushPromises();
  expect(state.profile.value?.employee?.title).toBe('当前职员');
  const loading = state.refresh();
  scope.stop();
  pending.resolve({ employee: { title: '卸载后返回' } });
  expect(await loading).toBe(false);
  expect(state.profile.value?.employee?.title).toBe('当前职员');
});

it('tolerates startup failure, reports explicit retry failures, and prevents a late load from overwriting saved data', async () => {
  const client = {
    currentProfile: vi.fn().mockRejectedValue(new Error('offline')),
    updateCurrentProfile: vi.fn().mockResolvedValue({ employee: { title: '已保存' } }),
  };
  const scope = effectScope();
  const state = scope.run(() =>
    useCurrentUserProfile({
      currentUser: () => ({ userId: 'user', system: false }),
      token: () => 'token',
      client,
    }),
  )!;
  try {
    await flushPromises();
    expect(state.profile.value).toBeUndefined();
    await expect(state.refresh()).rejects.toThrow('offline');
    const pending = deferred();
    client.currentProfile.mockReturnValueOnce(pending.promise);
    const loading = state.refresh();
    await state.save({ email: 'a@example.com' });
    pending.reject(new Error('late failure'));
    expect(await loading).toBe(false);
    expect(state.profile.value?.employee?.title).toBe('已保存');
  } finally {
    scope.stop();
  }
});
