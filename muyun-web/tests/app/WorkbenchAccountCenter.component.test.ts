import { h } from 'vue';
import { flushPromises, shallowMount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import WorkbenchAccountCenter from '@/app/WorkbenchAccountCenter.vue';
import PersonalAccessTokenDialog from '@/app/PersonalAccessTokenDialog.vue';
import WorkbenchLanguageSettings from '@/platform-workbench/WorkbenchLanguageSettings.vue';
import ChangeOwnPasswordDialog from '@/app/ChangeOwnPasswordDialog.vue';
import CurrentUserProfileDialog from '@/app/CurrentUserProfileDialog.vue';
import type { WorkbenchAccountContext } from '@/platform-workbench/workbenchAccountMenu';

function fixture(canCreate = true) {
  const request = vi.fn(
    async ({ path }: { path: string }): Promise<unknown> =>
      path.endsWith('/capabilities') ? { canCreate } : { employee: undefined },
  );
  const context: WorkbenchAccountContext = {
    http: { request: request as never },
    token: 'session-a',
    preferencePersistence: 'local',
    currentUser: { userId: 'a', username: 'A', tenantId: 'tenant-a', system: false },
  };
  let commands: { items?: { key: string }[]; select: (key: string) => Promise<void> };
  const wrapper = shallowMount(WorkbenchAccountCenter, {
    props: { context },
    slots: {
      default: (slot: typeof commands) => {
        commands = slot;
        return h('div');
      },
    },
  });
  return { wrapper, request, context, commands: () => commands! };
}
it('exposes standard commands and handles token/profile dialogs without per-feature host wiring', async () => {
  const f = fixture();
  try {
    await flushPromises();
    expect(f.commands().items!.map((i) => i.key)).toContain('accessTokens');
    await f.commands().select('accessTokens');
    await flushPromises();
    expect(f.wrapper.findComponent(PersonalAccessTokenDialog).props('open')).toBe(true);
    f.wrapper.findComponent(PersonalAccessTokenDialog).vm.$emit('close');
    await flushPromises();
    expect(f.wrapper.findComponent(PersonalAccessTokenDialog).props('open')).toBe(false);
    expect(f.request).toHaveBeenCalledWith(
      expect.objectContaining({
        path: '/iam.auth/access-tokens/capabilities',
        headers: { Authorization: 'Bearer session-a' },
      }),
    );
    await f.commands().select('profile');
    await flushPromises();
    expect(f.wrapper.findComponent(CurrentUserProfileDialog).props('open')).toBe(true);
    expect(f.wrapper.findComponent(PersonalAccessTokenDialog).props('open')).toBe(false);
  } finally {
    f.wrapper.unmount();
  }
});
it('cannot force token management through hidden config or revoked server capability', async () => {
  const f = fixture(false);
  try {
    await flushPromises();
    await f.commands().select('accessTokens');
    expect(f.wrapper.findComponent(PersonalAccessTokenDialog).props('open')).toBe(false);
    f.request.mockResolvedValue({ canCreate: true });
    await f.wrapper.setProps({ context: { ...f.context, token: 'session-b' } });
    await flushPromises();
    expect(f.commands().items!.map((i) => i.key)).toContain('accessTokens');
    // Permission changes are rechecked when selecting, even after a visible menu was loaded.
    f.request.mockResolvedValue({ canCreate: false });
    await f.commands().select('accessTokens');
    await flushPromises();
    expect(f.wrapper.findComponent(PersonalAccessTokenDialog).props('open')).toBe(false);
    await f.wrapper.setProps({ menu: { hiddenCommands: ['accessTokens'] } });
    expect(f.commands().items!.map((i) => i.key)).not.toContain('accessTokens');
  } finally {
    f.wrapper.unmount();
  }
});
it('guards logout and business actions, and drops a late guard after account changes', async () => {
  const f = fixture();
  const guard = vi.fn().mockResolvedValue(false);
  try {
    await f.wrapper.setProps({
      menu: { contributions: [{ key: 'mr.tools', title: 'Tools' }] },
      beforeCommand: guard,
    });
    await flushPromises();
    await f.commands().select('logout');
    expect(f.wrapper.emitted('logout')).toBeUndefined();
    guard.mockResolvedValue(true);
    await f.commands().select('mr.tools');
    expect(f.wrapper.emitted('userCommand')).toEqual([['mr.tools']]);
    let finish!: (allow: boolean) => void;
    guard.mockImplementation(
      () =>
        new Promise<boolean>((resolve) => {
          finish = resolve;
        }),
    );
    const pending = f.commands().select('logout');
    await f.wrapper.setProps({ context: { ...f.context, token: 'session-b' } });
    finish(true);
    await pending;
    expect(f.wrapper.emitted('logout')).toBeUndefined();
    guard.mockResolvedValue(true);
    await f.commands().select('logout');
    expect(f.wrapper.emitted('logout')).toEqual([['requested']]);
  } finally {
    f.wrapper.unmount();
  }
});
it('clears private inputs and dialogs on session changes and drops stale profile responses', async () => {
  const f = fixture();
  try {
    await flushPromises();
    await f.commands().select('changePassword');
    f.wrapper.findComponent(ChangeOwnPasswordDialog).vm.$emit('update:newPassword', 'private-draft');
    await flushPromises();
    await f.wrapper.setProps({ context: { ...f.context, token: 'session-b' } });
    await flushPromises();
    const dialog = f.wrapper.findComponent(ChangeOwnPasswordDialog);
    expect(dialog.props('open')).toBe(false);
    expect(dialog.props('newPassword')).toBe('');
    let finish!: (value: unknown) => void;
    f.request.mockImplementation(({ path }) =>
      path.endsWith('/profile')
        ? new Promise((resolve) => {
            finish = resolve;
          })
        : Promise.resolve({ canCreate: true }),
    );
    const pending = f.commands().select('profile');
    await f.wrapper.setProps({ context: undefined });
    finish({ username: 'old-owner' });
    await pending;
    await flushPromises();
    expect(f.wrapper.findComponent(CurrentUserProfileDialog).props('open')).toBe(false);
    expect(f.wrapper.findComponent(CurrentUserProfileDialog).props('profile')).toBeUndefined();
  } finally {
    f.wrapper.unmount();
  }
});
it('mandatory password recovery remains available when the optional command is hidden', async () => {
  const f = fixture();
  try {
    await f.wrapper.setProps({ menu: { hiddenCommands: ['changePassword'] }, beforeCommand: () => false });
    await f.wrapper.vm.requestPasswordChange();
    // A capability refresh rebuilds optional menu items; it cannot dismiss mandatory recovery.
    await f.wrapper.setProps({ menu: { hiddenCommands: ['changePassword', 'accessTokens'] } });
    await flushPromises();
    expect(f.wrapper.findComponent(ChangeOwnPasswordDialog).props('open')).toBe(true);
  } finally {
    f.wrapper.unmount();
  }
});

it.each([false, true])(
  'password change stays bound to its initiating account (session changes: %s)',
  async (changes) => {
    const f = fixture();
    try {
      await flushPromises();
      await f.commands().select('changePassword');
      const dialog = f.wrapper.findComponent(ChangeOwnPasswordDialog);
      dialog.vm.$emit('update:currentPassword', 'old-test-password');
      dialog.vm.$emit('update:newPassword', 'new-test-password');
      dialog.vm.$emit('update:confirmPassword', 'new-test-password');
      await flushPromises();
      let finish!: (value: unknown) => void;
      f.request.mockImplementation(({ path }) =>
        path.endsWith('/changeOwnPassword')
          ? new Promise((resolve) => {
              finish = resolve;
            })
          : Promise.resolve({ canCreate: true }),
      );
      dialog.vm.$emit('submit');
      await flushPromises();
      expect(f.request).toHaveBeenCalledWith(
        expect.objectContaining({
          path: '/iam.auth/changeOwnPassword',
          headers: { Authorization: 'Bearer session-a' },
        }),
      );
      if (changes) await f.wrapper.setProps({ context: { ...f.context, token: 'session-b' } });
      finish(undefined);
      await flushPromises();
      expect(f.wrapper.emitted('logout')).toEqual(changes ? undefined : [['passwordChanged']]);
      expect(dialog.props('newPassword')).toBe('');
      expect(dialog.props('open')).toBe(false);
    } finally {
      f.wrapper.unmount();
    }
  },
);
it('hiding an open standard command clears its private draft without changing session authority', async () => {
  const f = fixture();
  try {
    await flushPromises();
    await f.commands().select('changePassword');
    const dialog = f.wrapper.findComponent(ChangeOwnPasswordDialog);
    dialog.vm.$emit('update:newPassword', 'private-draft');
    await flushPromises();
    await f.wrapper.setProps({ menu: { hiddenCommands: ['changePassword'] } });
    expect(dialog.props('open')).toBe(false);
    expect(dialog.props('newPassword')).toBe('');
    expect(f.wrapper.emitted('logout')).toBeUndefined();
  } finally {
    f.wrapper.unmount();
  }
});

it('does not reuse a capability response after the transport changes with the same token', async () => {
  const f = fixture();
  try {
    await flushPromises();
    let finish!: (value: unknown) => void;
    const request = vi.fn(
      ({ path }: { path: string }): Promise<unknown> =>
        path.endsWith('/capabilities')
          ? new Promise((resolve) => {
              finish = resolve;
            })
          : Promise.resolve({}),
    );
    await f.wrapper.setProps({ context: { ...f.context, http: { request: request as never } } });
    expect(f.commands().items!.map((item) => item.key)).not.toContain('accessTokens');
    finish({ canCreate: false });
    await flushPromises();
    expect(f.commands().items!.map((item) => item.key)).not.toContain('accessTokens');
  } finally {
    f.wrapper.unmount();
  }
});

it('keeps an open dialog and its capability when a host recreates equivalent context props', async () => {
  const f = fixture();
  try {
    await flushPromises();
    await f.commands().select('accessTokens');
    const calls = f.request.mock.calls.length;
    await f.wrapper.setProps({ context: { ...f.context, currentUser: { ...f.context.currentUser! } } });
    await flushPromises();
    expect(f.request).toHaveBeenCalledTimes(calls);
    expect(f.commands().items!.map((item) => item.key)).toContain('accessTokens');
    expect(f.wrapper.findComponent(PersonalAccessTokenDialog).props('open')).toBe(true);
  } finally {
    f.wrapper.unmount();
  }
});

it('uses account preferences only for signed-in backend hosts and delegates legacy language ownership', async () => {
  const f = fixture();
  try {
    const settings = () => f.wrapper.findComponent(WorkbenchLanguageSettings);
    expect(settings().props('identityScope')).toBeUndefined();
    await f.wrapper.setProps({ context: { ...f.context, preferencePersistence: 'backend' } });
    expect(settings().props('identityScope')).toContain('session-a');
    await f.wrapper.setProps({
      context: { ...f.context, token: undefined, preferencePersistence: 'backend' },
    });
    expect(settings().props('identityScope')).toBeUndefined();
    await f.wrapper.setProps({ context: undefined });
    expect(settings().exists()).toBe(false);
  } finally {
    f.wrapper.unmount();
  }
});

it('drops a closed profile load error instead of displaying it in a newly opened password dialog', async () => {
  const f = fixture();
  try {
    await flushPromises();
    let fail!: (cause: unknown) => void;
    f.request.mockImplementation(({ path }) =>
      path.endsWith('/profile')
        ? new Promise((_, reject) => {
            fail = reject;
          })
        : Promise.resolve({ canCreate: true }),
    );
    const pending = f.commands().select('profile');
    await flushPromises();
    f.wrapper.findComponent(CurrentUserProfileDialog).vm.$emit('close');
    await flushPromises();
    await f.commands().select('changePassword');
    fail(new Error('profile service failed'));
    await pending;
    await flushPromises();
    const password = f.wrapper.findComponent(ChangeOwnPasswordDialog);
    expect(password.props('open')).toBe(true);
    expect(password.props('error')).toBeUndefined();
  } finally {
    f.wrapper.unmount();
  }
});

it('keeps the new profile load busy when an earlier closed load finishes', async () => {
  const f = fixture();
  try {
    await flushPromises();
    const finishes: ((value: unknown) => void)[] = [];
    f.request.mockImplementation(({ path }) =>
      path.endsWith('/profile')
        ? new Promise((resolve) => {
            finishes.push(resolve);
          })
        : Promise.resolve({ canCreate: true }),
    );
    const first = f.commands().select('profile');
    await flushPromises();
    f.wrapper.findComponent(CurrentUserProfileDialog).vm.$emit('close');
    await flushPromises();
    const second = f.commands().select('profile');
    await flushPromises();
    finishes[0]!({ username: 'old response' });
    await first;
    await flushPromises();
    const profile = f.wrapper.findComponent(CurrentUserProfileDialog);
    expect(profile.props('open')).toBe(true);
    expect(profile.props('loading')).toBe(true);
    expect(profile.props('profile')?.username).not.toBe('old response');
    finishes[1]!({ username: 'current response' });
    await second;
    await flushPromises();
    expect(profile.props('loading')).toBe(false);
    expect(profile.props('profile')?.username).toBe('current response');
  } finally {
    f.wrapper.unmount();
  }
});

it('lets an active profile load finish after another command is rejected by its guard', async () => {
  const f = fixture();
  try {
    await flushPromises();
    let finish!: (value: unknown) => void;
    f.request.mockImplementation(({ path }) =>
      path.endsWith('/profile')
        ? new Promise((resolve) => {
            finish = resolve;
          })
        : Promise.resolve({ canCreate: true }),
    );
    const pending = f.commands().select('profile');
    await flushPromises();
    await f.wrapper.setProps({ beforeCommand: () => false });
    await f.commands().select('changePassword');
    finish({ username: 'current response' });
    await pending;
    await flushPromises();
    const profile = f.wrapper.findComponent(CurrentUserProfileDialog);
    expect(profile.props('open')).toBe(true);
    expect(profile.props('loading')).toBe(false);
    expect(profile.props('profile')?.username).toBe('current response');
  } finally {
    f.wrapper.unmount();
  }
});
