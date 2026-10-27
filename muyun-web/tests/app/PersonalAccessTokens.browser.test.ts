import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import PersonalAccessTokens from '@/app/PersonalAccessTokens.vue';
import '@/styles.css';
import 'ant-design-vue/dist/reset.css';

it.each([800, 390])('keeps token creation and the one-time secret usable at width %s', async (width) => {
  await page.viewport(width, 900);
  const metadata = {
    id: 'key',
    name: 'USB 初始化工具',
    displayPrefix: 'muyun_pat_abcdefgh',
    createdAt: '2026-10-27T00:00:00Z',
    expiresAt: '2030-10-27T00:00:00Z',
  };
  const secret = 'muyun_pat_' + 'a'.repeat(43);
  const wrapper = mount(PersonalAccessTokens, {
    attachTo: document.body,
    props: {
      sessionToken: 'session',
      client: {
        capabilities: vi.fn().mockResolvedValue({ canCreate: true }),
        list: vi.fn().mockResolvedValue([]),
        create: vi.fn().mockResolvedValue({ token: secret, accessToken: metadata }),
        remove: vi.fn(),
        revoke: vi.fn(),
      },
    },
  });
  try {
    await flushPromises();
    (wrapper.element as HTMLElement).style.cssText = 'max-width:600px;margin:16px;';
    await wrapper.get('input').setValue(metadata.name);
    const create = wrapper.get('.access-token-create button').element as HTMLButtonElement;
    create.focus();
    expect(document.activeElement).toBe(create);
    expect(create.getBoundingClientRect().right).toBeLessThanOrEqual(width);
    await page.getByRole('button', { name: '创建密钥', exact: true }).click();
    await flushPromises();
    const textarea = wrapper.get('textarea').element as HTMLTextAreaElement;
    textarea.focus();
    expect(textarea.value).toBe(secret);
    expect(textarea.readOnly).toBe(true);
    expect(document.activeElement).toBe(textarea);
    expect(textarea.getBoundingClientRect().right).toBeLessThanOrEqual(width);
    expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(width);
  } finally {
    wrapper.unmount();
  }
});
