import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import PersonalAccessTokens from '@/app/PersonalAccessTokens.vue';
import PersonalAccessTokenDialog from '@/app/PersonalAccessTokenDialog.vue';
import CurrentUserProfileDialog from '@/app/CurrentUserProfileDialog.vue';
import type { PersonalAccessTokenClient } from '@/web-core';
import { confirmAction } from '@muyun/vue-ui-antdv';

vi.mock('@muyun/vue-ui-antdv', async (original) => ({
  ...(await original<typeof import('@muyun/vue-ui-antdv')>()),
  confirmAction: vi.fn().mockResolvedValue(true),
}));
beforeEach(() => {
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockReturnValue({
      matches: false,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }),
  );
});
afterEach(() => vi.unstubAllGlobals());

const metadata = {
  id: 'key-1',
  name: 'USB 配装',
  displayPrefix: 'muyun_pat_12345678',
  createdAt: '2026-10-27T00:00:00Z',
  expiresAt: '2030-10-27T00:00:00Z',
};
function client(overrides: Partial<PersonalAccessTokenClient> = {}): PersonalAccessTokenClient {
  return {
    capabilities: vi.fn().mockResolvedValue({ canCreate: true }),
    list: vi.fn().mockResolvedValue([]),
    create: vi.fn().mockResolvedValue({ token: 'one-time-secret', accessToken: metadata }),
    remove: vi.fn(),
    revoke: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  };
}
function button(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find((candidate) => candidate.text().replace(/\s/g, '').includes(text))!;
}

describe('personal access tokens', () => {
  it('shows the full secret only after creation and discards it on hide and remount', async () => {
    const api = client();
    const wrapper = mount(PersonalAccessTokens, { props: { client: api, sessionToken: 'session' } });
    await flushPromises();
    await wrapper.get('input').setValue('USB 配装');
    await wrapper.get('form').trigger('submit');
    await flushPromises();
    expect(api.create).toHaveBeenCalledExactlyOnceWith('session', { name: 'USB 配装', validDays: 90 });
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('one-time-secret');
    expect(JSON.stringify(window.localStorage)).not.toContain('one-time-secret');
    expect(JSON.stringify(window.sessionStorage)).not.toContain('one-time-secret');
    await button(wrapper, '已保存').trigger('click');
    expect(wrapper.find('textarea').exists()).toBe(false);
    wrapper.unmount();
    const next = mount(PersonalAccessTokens, {
      props: { client: client({ list: vi.fn().mockResolvedValue([metadata]) }), sessionToken: 'session' },
    });
    await flushPromises();
    expect(next.find('textarea').exists()).toBe(false);
    expect(next.text()).toContain('USB 配装');
    next.unmount();
  });

  it('revokes the selected key and reloads safe metadata', async () => {
    const api = client({
      list: vi
        .fn()
        .mockResolvedValueOnce([metadata])
        .mockResolvedValueOnce([{ ...metadata, revokedAt: '2026-10-27T00:00:00Z' }]),
    });
    const wrapper = mount(PersonalAccessTokens, { props: { client: api, sessionToken: 'session' } });
    await flushPromises();
    await wrapper.get('.access-token-list button').trigger('click');
    await flushPromises();
    expect(api.revoke).toHaveBeenCalledExactlyOnceWith('session', 'key-1');
    expect(wrapper.get('.access-token-list button').text().replace(/\s/g, '')).toBe('删除');
    wrapper.unmount();
  });

  it('only deletes revoked keys and reloads the list after confirmation', async () => {
    const revoked = { ...metadata, revokedAt: '2026-10-27T00:00:00Z' };
    const active = { ...metadata, id: 'active', name: '仍在使用' };
    const expired = { ...metadata, id: 'expired', name: '已过期', expiresAt: '2020-01-01T00:00:00Z' };
    const api = client({
      list: vi
        .fn()
        .mockResolvedValueOnce([revoked, active, expired])
        .mockResolvedValueOnce([active, expired]),
    });
    const wrapper = mount(PersonalAccessTokens, { props: { client: api, sessionToken: 'session' } });
    await flushPromises();
    const records = wrapper.findAll('tbody tr');
    expect(
      records
        .find((row) => row.text().includes('仍在使用'))!
        .get('button')
        .text()
        .replace(/\s/g, ''),
    ).toBe('撤销');
    expect(
      records
        .find((row) => row.text().includes('已过期'))!
        .get('button')
        .text()
        .replace(/\s/g, ''),
    ).toBe('撤销');
    await records
      .find((row) => row.text().includes(metadata.name))!
      .get('button')
      .trigger('click');
    await flushPromises();
    expect(api.remove).toHaveBeenCalledExactlyOnceWith('session', metadata.id);
    expect(wrapper.text()).not.toContain(metadata.name);
    expect(wrapper.text()).toContain('仍在使用');
    wrapper.unmount();
  });

  it('keeps revoked metadata on cancellation or deletion failure without retrying', async () => {
    const api = client({
      list: vi.fn().mockResolvedValue([{ ...metadata, revokedAt: '2026-10-27T00:00:00Z' }]),
      remove: vi.fn().mockRejectedValue(new Error('删除失败')),
    });
    const wrapper = mount(PersonalAccessTokens, { props: { client: api, sessionToken: 'session' } });
    await flushPromises();
    vi.mocked(confirmAction).mockResolvedValueOnce(false);
    await wrapper.get('.access-token-list button').trigger('click');
    await flushPromises();
    expect(api.remove).not.toHaveBeenCalled();
    await wrapper.get('.access-token-list button').trigger('click');
    await flushPromises();
    expect(api.remove).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain(metadata.name);
    expect(wrapper.get('[role="alert"]').text()).toContain('删除失败');
    wrapper.unmount();
  });

  it('does not retry issuance automatically and reports creation failures', async () => {
    const api = client({ create: vi.fn().mockRejectedValue(new Error('当前账号无创建权限')) });
    const wrapper = mount(PersonalAccessTokens, { props: { client: api, sessionToken: 'session' } });
    await flushPromises();
    await wrapper.get('input').setValue('tool');
    await wrapper.get('form').trigger('submit');
    await flushPromises();
    expect(api.create).toHaveBeenCalledTimes(1);
    expect(wrapper.get('[role="alert"]').text()).toContain('无创建权限');
    expect(wrapper.find('textarea').exists()).toBe(false);
    wrapper.unmount();
  });

  it('keeps a newly issued secret visible when another key is revoked', async () => {
    const old = { ...metadata, id: 'old', name: '旧工具' };
    const api = client({
      list: vi
        .fn()
        .mockResolvedValueOnce([old])
        .mockResolvedValueOnce([metadata, { ...old, revokedAt: '2026-10-27T00:00:00Z' }]),
    });
    const wrapper = mount(PersonalAccessTokens, { props: { client: api, sessionToken: 'session' } });
    await flushPromises();
    await wrapper.get('input').setValue('新工具');
    await wrapper.get('form').trigger('submit');
    await flushPromises();
    const oldRow = wrapper.findAll('tbody tr').find((row) => row.text().includes('旧工具'))!;
    await oldRow.get('button').trigger('click');
    await flushPromises();
    expect(api.revoke).toHaveBeenCalledWith('session', 'old');
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('one-time-secret');
    wrapper.unmount();
  });

  it('hides creation without permission while retaining revocation of existing keys', async () => {
    const api = client({
      capabilities: vi.fn().mockResolvedValue({ canCreate: false }),
      list: vi.fn().mockResolvedValue([metadata]),
    });
    const wrapper = mount(PersonalAccessTokens, { props: { client: api, sessionToken: 'session' } });
    await flushPromises();
    expect(wrapper.find('form').exists()).toBe(false);
    expect(wrapper.text()).toContain(metadata.name);
    await wrapper.get('.access-token-list button').trigger('click');
    await flushPromises();
    expect(api.revoke).toHaveBeenCalledExactlyOnceWith('session', metadata.id);
    expect(api.create).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('hides the section when the user cannot create and has no keys', async () => {
    const wrapper = mount(PersonalAccessTokens, {
      props: {
        client: client({ capabilities: vi.fn().mockResolvedValue({ canCreate: false }) }),
        sessionToken: 'session',
      },
    });
    await flushPromises();
    expect(wrapper.find('section').exists()).toBe(false);
    wrapper.unmount();
  });

  it('opens token management in its own dialog and discards its contents on close', async () => {
    const api = client();
    const wrapper = mount(PersonalAccessTokenDialog, {
      props: { open: true, sessionToken: 'session', client: api },
      global: { stubs: { UiModal: { template: '<div><slot /><slot name="operation" /></div>' } } },
    });
    await flushPromises();
    expect(api.list).toHaveBeenCalledExactlyOnceWith('session');
    await wrapper.get('input').setValue('tool');
    await wrapper.get('form').trigger('submit');
    await flushPromises();
    expect(wrapper.find('textarea').exists()).toBe(true);
    await wrapper.setProps({ open: false });
    expect(wrapper.findComponent(PersonalAccessTokens).exists()).toBe(false);
    await wrapper.setProps({ open: true });
    await flushPromises();
    expect(wrapper.find('textarea').exists()).toBe(false);
    await button(wrapper, '关闭').trigger('click');
    expect(wrapper.emitted('close')).toHaveLength(1);
    wrapper.unmount();
  });

  it('keeps token management out of personal information', () => {
    const wrapper = mount(CurrentUserProfileDialog, {
      props: { open: true, authToken: 'session' },
      global: { stubs: { UiModal: { template: '<div><slot /></div>' } } },
    });
    expect(wrapper.findComponent(PersonalAccessTokens).exists()).toBe(false);
    wrapper.unmount();
  });
});
